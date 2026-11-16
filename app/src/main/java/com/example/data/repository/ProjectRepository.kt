package com.example.data.repository

import android.net.Uri
import com.example.data.local.ImportSourceType
import com.example.data.local.MediaStorageHelper
import com.example.data.local.ProjectDao
import com.example.data.local.ProjectEntity
import com.example.domain.model.AiModelMode
import com.example.domain.model.ExportSettings
import com.example.domain.model.MediaType
import com.example.domain.model.ProjectStatus
import com.example.domain.model.RestorationSettings
import com.example.domain.model.StudioProject
import com.example.domain.model.TargetFps
import com.example.domain.model.TargetResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

class ProjectRepository(
    private val projectDao: ProjectDao,
    val mediaStorage: MediaStorageHelper,
) {
    fun observeAllProjects(): Flow<List<StudioProject>> =
        projectDao.observeAllProjects().map { list -> list.map { it.toDomain() } }

    suspend fun getProject(id: String): StudioProject? =
        projectDao.getProjectById(id)?.toDomain()

    suspend fun createProjectFromUri(
        uri: Uri,
        mediaType: MediaType,
        sourceType: ImportSourceType = ImportSourceType.LOCAL_GALLERY,
        onStatusUpdate: (String) -> Unit = {},
    ): StudioProject {
        val projectId = UUID.randomUUID().toString()
        val imported = mediaStorage.importMediaToProject(
            projectId = projectId,
            uri = uri,
            mediaType = mediaType,
            sourceType = sourceType,
            onStatusUpdate = onStatusUpdate,
        )
        val now = System.currentTimeMillis()
        val defaultResolution = if (mediaType == MediaType.VIDEO) {
            TargetResolution.FHD_1080P
        } else {
            TargetResolution.SCALE_4X
        }
        val defaultRestoration = if (mediaType == MediaType.PHOTO) {
            RestorationSettings(
                deblur = 52f,
                sharpness = 58f,
                denoise = 50f,
                clarity = 48f,
                shadowRecovery = 22f,
                vibrance = 28f,
                aiModelMode = AiModelMode.ESRGAN_4X,
            )
        } else {
            RestorationSettings(
                deblur = 48f,
                sharpness = 56f,
                denoise = 40f,
                clarity = 36f,
                shadowRecovery = 14f,
                vibrance = 18f,
                aiModelMode = AiModelMode.VIDEO_ENHANCER,
            )
        }

        val defaultFps = selectSmartDefaultFps(imported.fps)

        val project = StudioProject(
            id = projectId,
            name = imported.displayName,
            mediaType = mediaType,
            sourcePath = imported.sourcePath,
            thumbnailPath = imported.thumbnailPath,
            exportedPath = null,
            originalWidth = imported.width,
            originalHeight = imported.height,
            originalFps = imported.fps,
            durationMs = imported.durationMs,
            restoration = defaultRestoration,
            exportSettings = ExportSettings(
                resolution = defaultResolution,
                fps = defaultFps,
                motionInterpolation = mediaType == MediaType.VIDEO &&
                    (defaultFps == TargetFps.FPS_60 || defaultFps == TargetFps.FPS_50),
                bitrateMbps = 24f,
            ),
            status = ProjectStatus.DRAFT,
            lastProgress = 0f,
            updatedAt = now,
            createdAt = now,
        )
        projectDao.upsertProject(ProjectEntity.fromDomain(project))
        return project
    }

    suspend fun saveProject(project: StudioProject) {
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        projectDao.upsertProject(ProjectEntity.fromDomain(updated))
    }

    suspend fun renameProject(id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            projectDao.renameProject(id, clean, System.currentTimeMillis())
        }
    }

    suspend fun updateExportStatus(
        id: String,
        status: ProjectStatus,
        progress: Float,
        exportedPath: String?,
    ) {
        projectDao.updateProjectExportStatus(
            id = id,
            status = status.name,
            progress = progress,
            exportedPath = exportedPath,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deleteProject(project: StudioProject) {
        projectDao.deleteProjectById(project.id)
        mediaStorage.deleteProjectFiles(project.id, project.exportedPath)
    }

    suspend fun saveExportToGallery(project: StudioProject, exportedFile: File): String {
        return mediaStorage.saveExportToGallery(exportedFile, project.mediaType, project.name)
    }

    companion object {
        fun selectSmartDefaultFps(sourceFps: Float): TargetFps = when {
            sourceFps >= 55f -> TargetFps.FPS_60
            sourceFps >= 45f -> TargetFps.FPS_50
            sourceFps in 1f..26f -> TargetFps.FPS_24
            else -> TargetFps.FPS_30
        }
    }
}
