package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ImportSourceType
import com.example.data.local.MediaStorageHelper
import com.example.data.local.StudioDatabase
import com.example.data.repository.ProjectRepository
import com.example.domain.model.ExportProgressState
import com.example.domain.model.ExportSettings
import com.example.domain.model.MediaType
import com.example.domain.model.ProjectStatus
import com.example.domain.model.RestorationSettings
import com.example.domain.model.SmartPreset
import com.example.domain.model.StudioProject
import com.example.engine.ai.MultiStageAiPipeline
import com.example.engine.gl.OffscreenPhotoExporter
import com.example.engine.gl.OffscreenVideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ProjectFilter(val label: String) {
    ALL("All Projects"),
    VIDEO("Video Projects"),
    PHOTO("Photo Projects"),
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StudioDatabase.getInstance(application)
    val mediaStorage = MediaStorageHelper(application)
    private val repository = ProjectRepository(database.projectDao(), mediaStorage)

    private val photoExporter = OffscreenPhotoExporter(application)
    private val videoExporter = OffscreenVideoExporter(application)

    val projects: StateFlow<List<StudioProject>> = repository.observeAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedFilter = MutableStateFlow(ProjectFilter.ALL)
    val selectedFilter: StateFlow<ProjectFilter> = _selectedFilter.asStateFlow()

    val filteredProjects: StateFlow<List<StudioProject>> = combine(projects, _selectedFilter) { list, filter ->
        when (filter) {
            ProjectFilter.ALL -> list
            ProjectFilter.VIDEO -> list.filter { it.mediaType == MediaType.VIDEO }
            ProjectFilter.PHOTO -> list.filter { it.mediaType == MediaType.PHOTO }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeProject = MutableStateFlow<StudioProject?>(null)
    val activeProject: StateFlow<StudioProject?> = _activeProject.asStateFlow()

    private val _activePresetId = MutableStateFlow<String?>(null)
    val activePresetId: StateFlow<String?> = _activePresetId.asStateFlow()

    private val _isAutoAnalyzing = MutableStateFlow(false)
    val isAutoAnalyzing: StateFlow<Boolean> = _isAutoAnalyzing.asStateFlow()

    private val _autoEnhanceSummary = MutableStateFlow<String?>(null)
    val autoEnhanceSummary: StateFlow<String?> = _autoEnhanceSummary.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importStatusText = MutableStateFlow("Preparing Studio Workspace...")
    val importStatusText: StateFlow<String> = _importStatusText.asStateFlow()

    private val _exportState = MutableStateFlow(ExportProgressState())
    val exportState: StateFlow<ExportProgressState> = _exportState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var saveDebounceJob: Job? = null
    private var exportJob: Job? = null
    private val pauseRenderFlag = AtomicBoolean(false)
    private val cancelRenderFlag = AtomicBoolean(false)

    fun setFilter(filter: ProjectFilter) {
        _selectedFilter.value = filter
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun importMedia(
        uri: Uri,
        mediaType: MediaType,
        sourceType: ImportSourceType = ImportSourceType.LOCAL_GALLERY,
        onOpened: () -> Unit,
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatusText.value = if (sourceType != ImportSourceType.LOCAL_GALLERY) {
                "Downloading from Cloud..."
            } else {
                "Preparing Studio Workspace..."
            }
            try {
                val created = repository.createProjectFromUri(
                    uri = uri,
                    mediaType = mediaType,
                    sourceType = sourceType,
                    onStatusUpdate = { status -> _importStatusText.value = status },
                )
                _activeProject.value = created
                _activePresetId.value = null
                _autoEnhanceSummary.value = null
                onOpened()
            } catch (t: Throwable) {
                _snackbarMessage.value = "Failed to import media: ${t.localizedMessage ?: "Unknown error"}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun openProject(projectId: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            val loaded = repository.getProject(projectId)
            if (loaded != null) {
                _activeProject.value = loaded
                _activePresetId.value = null
                _autoEnhanceSummary.value = null
                onOpened()
            } else {
                _snackbarMessage.value = "Project could not be found."
            }
        }
    }

    fun autoEnhanceCurrentProject() {
        val current = _activeProject.value ?: return
        if (_isAutoAnalyzing.value) return
        viewModelScope.launch {
            _isAutoAnalyzing.value = true
            try {
                val analysis = withContext(Dispatchers.Default) {
                    MultiStageAiPipeline(getApplication()).use { pipeline ->
                        pipeline.analyzeMediaSource(
                            sourcePath = current.sourcePath,
                            mediaType = current.mediaType,
                            originalWidth = current.originalWidth,
                            originalHeight = current.originalHeight,
                            currentAiMode = current.restoration.aiModelMode,
                        )
                    }
                }
                val updated = current.copy(restoration = analysis.recommendedSettings)
                _activeProject.value = updated
                _activePresetId.value = null
                _autoEnhanceSummary.value = analysis.summaryBadge
                scheduleSave(updated)
                _snackbarMessage.value = "Auto Enhance applied (${analysis.summaryBadge}) — Live Preview updated!"
            } catch (t: Throwable) {
                _snackbarMessage.value = "Auto Enhance applied default 5-stage profile"
            } finally {
                _isAutoAnalyzing.value = false
            }
        }
    }

    fun updateRestoration(transform: (RestorationSettings) -> RestorationSettings) {
        val current = _activeProject.value ?: return
        val updated = current.copy(restoration = transform(current.restoration))
        _activeProject.value = updated
        _activePresetId.value = null
        scheduleSave(updated)
    }

    fun updateExportSettings(transform: (ExportSettings) -> ExportSettings) {
        val current = _activeProject.value ?: return
        val updated = current.copy(exportSettings = transform(current.exportSettings))
        _activeProject.value = updated
        scheduleSave(updated)
    }

    fun applyPreset(preset: SmartPreset) {
        val current = _activeProject.value ?: return
        val updatedExport = current.exportSettings.let { exp ->
            exp.copy(
                fps = preset.recommendedFps ?: exp.fps,
                motionInterpolation = preset.recommendedMotionInterpolation ?: exp.motionInterpolation,
            )
        }
        val updated = current.copy(
            restoration = preset.restoration,
            exportSettings = updatedExport,
        )
        _activeProject.value = updated
        _activePresetId.value = preset.id
        _autoEnhanceSummary.value = "Preset: ${preset.name}"
        scheduleSave(updated)
    }

    fun renameProject(projectId: String, newName: String) {
        viewModelScope.launch {
            repository.renameProject(projectId, newName)
            val current = _activeProject.value
            if (current?.id == projectId && newName.trim().isNotEmpty()) {
                _activeProject.value = current.copy(name = newName.trim())
            }
        }
    }

    fun deleteProject(project: StudioProject) {
        viewModelScope.launch {
            repository.deleteProject(project)
            if (_activeProject.value?.id == project.id) {
                _activeProject.value = null
            }
            _snackbarMessage.value = "Deleted \"${project.name}\""
        }
    }

    fun saveProjectStateNow() {
        val current = _activeProject.value ?: return
        viewModelScope.launch {
            repository.saveProject(current)
            _snackbarMessage.value = "Project state saved locally"
        }
    }

    private fun scheduleSave(project: StudioProject) {
        saveDebounceJob?.cancel()
        saveDebounceJob = viewModelScope.launch {
            delay(350L)
            repository.saveProject(project)
        }
    }

    fun startExport(projectOverride: StudioProject? = null) {
        val target = projectOverride ?: _activeProject.value ?: return
        _activeProject.value = target
        exportJob?.cancel()
        pauseRenderFlag.set(false)
        cancelRenderFlag.set(false)

        val outputFile = mediaStorage.createExportOutputFile(
            projectId = target.id,
            mediaType = target.mediaType,
            isPng = target.exportSettings.photoFormatPng,
        )

        _exportState.value = ExportProgressState(
            isVisible = true,
            isRunning = true,
            isPaused = false,
            isCompleted = false,
            progress = 0f,
            statusLabel = "Initializing ${target.restoration.aiModelMode.assetFileName}...",
            outputFilePath = outputFile.absolutePath,
        )

        exportJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            repository.saveProject(target.copy(status = ProjectStatus.RENDERING, lastProgress = 0f))

            try {
                if (target.mediaType == MediaType.PHOTO) {
                    photoExporter.exportPhoto(
                        project = target,
                        outputFile = outputFile,
                        isPaused = { pauseRenderFlag.get() },
                        isCancelled = { cancelRenderFlag.get() },
                        onProgress = { p ->
                            val elapsed = System.currentTimeMillis() - startMs
                            val eta = if (p.progress > 0.05f) {
                                ((elapsed / p.progress) * (1f - p.progress)).toLong()
                            } else 0L
                            _exportState.value = _exportState.value.copy(
                                progress = p.progress,
                                framesProcessed = p.framesProcessed,
                                totalFrames = p.totalFrames,
                                currentFps = p.currentFps,
                                elapsedMs = elapsed,
                                estimatedRemainingMs = eta,
                                statusLabel = p.statusLabel,
                            )
                        },
                    )
                } else {
                    videoExporter.exportVideo(
                        project = target,
                        outputFile = outputFile,
                        isPaused = { pauseRenderFlag.get() },
                        isCancelled = { cancelRenderFlag.get() },
                        onProgress = { p ->
                            _exportState.value = _exportState.value.copy(
                                progress = p.progress,
                                framesProcessed = p.framesProcessed,
                                totalFrames = p.totalFrames,
                                currentFps = p.currentFps,
                                elapsedMs = p.elapsedMs,
                                estimatedRemainingMs = p.estimatedRemainingMs,
                                statusLabel = p.statusLabel,
                            )
                        },
                    )
                }

                // Automatically save to Pictures/UltraRestore or Movies/UltraRestore via MediaStore
                val autoGalleryUri = runCatching {
                    repository.saveExportToGallery(target, outputFile)
                }.getOrNull()

                repository.updateExportStatus(
                    id = target.id,
                    status = ProjectStatus.COMPLETED,
                    progress = 1f,
                    exportedPath = outputFile.absolutePath,
                )
                _activeProject.value = _activeProject.value?.copy(
                    status = ProjectStatus.COMPLETED,
                    lastProgress = 1f,
                    exportedPath = outputFile.absolutePath,
                )
                _exportState.value = _exportState.value.copy(
                    isRunning = false,
                    isPaused = false,
                    isCompleted = true,
                    progress = 1f,
                    outputFilePath = outputFile.absolutePath,
                    savedToGalleryUri = autoGalleryUri,
                )
            } catch (_: InterruptedException) {
                runCatching { outputFile.delete() }
                repository.updateExportStatus(
                    id = target.id,
                    status = ProjectStatus.PAUSED,
                    progress = _exportState.value.progress,
                    exportedPath = target.exportedPath,
                )
                _exportState.value = ExportProgressState(isVisible = false)
                _snackbarMessage.value = "Render cancelled — project settings preserved"
            } catch (t: Throwable) {
                runCatching { outputFile.delete() }
                repository.updateExportStatus(
                    id = target.id,
                    status = ProjectStatus.DRAFT,
                    progress = 0f,
                    exportedPath = target.exportedPath,
                )
                _exportState.value = _exportState.value.copy(
                    isRunning = false,
                    isPaused = false,
                    isCompleted = false,
                    errorMessage = t.localizedMessage ?: "Hardware export failed",
                )
            }
        }
    }

    fun togglePauseExport() {
        val nextPaused = !pauseRenderFlag.get()
        pauseRenderFlag.set(nextPaused)
        _exportState.value = _exportState.value.copy(isPaused = nextPaused)
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            repository.updateExportStatus(
                id = project.id,
                status = if (nextPaused) ProjectStatus.PAUSED else ProjectStatus.RENDERING,
                progress = _exportState.value.progress,
                exportedPath = project.exportedPath,
            )
        }
    }

    fun cancelExport() {
        cancelRenderFlag.set(true)
        pauseRenderFlag.set(false)
    }

    fun saveExportedFileToGallery() {
        val project = _activeProject.value ?: return
        val path = _exportState.value.outputFilePath ?: project.exportedPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _snackbarMessage.value = "Exported file not found"
            return
        }
        viewModelScope.launch {
            try {
                val galleryUri = repository.saveExportToGallery(project, file)
                _exportState.value = _exportState.value.copy(savedToGalleryUri = galleryUri)
                _snackbarMessage.value = "Saved to Device Gallery!"
            } catch (t: Throwable) {
                _snackbarMessage.value = "Gallery save failed: ${t.localizedMessage}"
            }
        }
    }

    fun dismissExportModal() {
        if (_exportState.value.isRunning) {
            cancelExport()
        } else {
            _exportState.value = ExportProgressState(isVisible = false)
        }
    }
}
