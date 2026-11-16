package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.ImportSourceType
import com.example.domain.model.MediaType
import com.example.ui.MainViewModel
import com.example.ui.components.ExportModalDialog
import com.example.ui.components.MediaSourcePickerDialog
import com.example.ui.screens.EditorWorkspaceScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonMuted
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.MatteBlack
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.PureWhite
import com.example.ui.theme.UltraRestoreTheme

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_PROJECTS = "projects"
const val ROUTE_EDITOR = "editor"

private const val PKG_GOOGLE_PHOTOS = "com.google.android.apps.photos"
private const val PKG_GOOGLE_DRIVE = "com.google.android.apps.docs"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UltraRestoreTheme {
                UltraRestoreApp()
            }
        }
    }
}

@Composable
fun UltraRestoreApp(
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_DASHBOARD

    val projects by viewModel.filteredProjects.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importStatusText by viewModel.importStatusText.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingPickerMediaType by remember { mutableStateOf<MediaType?>(null) }
    var activeCloudMediaType by remember { mutableStateOf(MediaType.VIDEO) }
    var activeCloudSourceType by remember { mutableStateOf(ImportSourceType.GOOGLE_PHOTOS_CLOUD) }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    // 1. Local Mobile Gallery Photo Picker Launchers
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri, MediaType.VIDEO, ImportSourceType.LOCAL_GALLERY) {
                navController.navigate(ROUTE_EDITOR) {
                    launchSingleTop = true
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri, MediaType.PHOTO, ImportSourceType.LOCAL_GALLERY) {
                navController.navigate(ROUTE_EDITOR) {
                    launchSingleTop = true
                }
            }
        }
    }

    // 2. Cloud (Google Photos / Google Drive) Intent Launcher
    val cloudIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                viewModel.importMedia(uri, activeCloudMediaType, activeCloudSourceType) {
                    navController.navigate(ROUTE_EDITOR) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val openSourcePickerForVideo = {
        pendingPickerMediaType = MediaType.VIDEO
    }

    val openSourcePickerForPhoto = {
        pendingPickerMediaType = MediaType.PHOTO
    }

    val launchSelectedSource: (MediaType, ImportSourceType) -> Unit = { mediaType, sourceType ->
        pendingPickerMediaType = null
        val mime = if (mediaType == MediaType.VIDEO) "video/*" else "image/*"

        when (sourceType) {
            ImportSourceType.LOCAL_GALLERY -> {
                if (mediaType == MediaType.VIDEO) {
                    videoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                } else {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            }

            ImportSourceType.GOOGLE_PHOTOS_CLOUD -> {
                activeCloudMediaType = mediaType
                activeCloudSourceType = ImportSourceType.GOOGLE_PHOTOS_CLOUD
                val photosIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mime
                    putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setPackage(PKG_GOOGLE_PHOTOS)
                }
                val canResolvePhotos = photosIntent.resolveActivity(context.packageManager) != null
                if (canResolvePhotos) {
                    cloudIntentLauncher.launch(photosIntent)
                } else {
                    val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = mime
                        putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    cloudIntentLauncher.launch(Intent.createChooser(fallbackIntent, "Select from Google Photos"))
                }
            }

            ImportSourceType.GOOGLE_DRIVE_CLOUD -> {
                activeCloudMediaType = mediaType
                activeCloudSourceType = ImportSourceType.GOOGLE_DRIVE_CLOUD
                val driveIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = mime
                    putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setPackage(PKG_GOOGLE_DRIVE)
                }
                val canResolveDrive = driveIntent.resolveActivity(context.packageManager) != null
                if (canResolveDrive) {
                    cloudIntentLauncher.launch(driveIntent)
                } else {
                    val safCloudIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = mime
                        putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    cloudIntentLauncher.launch(safCloudIntent)
                }
            }
        }
    }

    val showBottomBar = currentRoute != ROUTE_EDITOR

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MatteBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = CarbonElevated,
                    contentColor = PureWhite,
                    actionColor = CrimsonRed,
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = CarbonSurface,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_DASHBOARD,
                        onClick = {
                            navController.navigate(ROUTE_DASHBOARD) {
                                popUpTo(ROUTE_DASHBOARD) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == ROUTE_DASHBOARD) Icons.Filled.AutoFixHigh else Icons.Outlined.AutoFixHigh,
                                contentDescription = "Studio Home",
                            )
                        },
                        label = { Text("Studio") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CrimsonRed,
                            selectedTextColor = CrimsonRed,
                            indicatorColor = CrimsonMuted,
                            unselectedIconColor = MutedSilver,
                            unselectedTextColor = MutedSilver,
                        ),
                        modifier = Modifier.testTag("nav_studio"),
                    )

                    NavigationBarItem(
                        selected = currentRoute == ROUTE_PROJECTS,
                        onClick = {
                            navController.navigate(ROUTE_PROJECTS) {
                                popUpTo(ROUTE_DASHBOARD)
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == ROUTE_PROJECTS) Icons.Filled.FolderCopy else Icons.Outlined.FolderCopy,
                                contentDescription = "Recent Projects",
                            )
                        },
                        label = { Text("Projects") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CrimsonRed,
                            selectedTextColor = CrimsonRed,
                            indicatorColor = CrimsonMuted,
                            unselectedIconColor = MutedSilver,
                            unselectedTextColor = MutedSilver,
                        ),
                        modifier = Modifier.testTag("nav_projects"),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_DASHBOARD,
            ) {
                composable(ROUTE_DASHBOARD) {
                    HomeDashboardScreen(
                        projects = projects,
                        selectedFilter = selectedFilter,
                        isImporting = isImporting,
                        importStatusText = importStatusText,
                        showOnlyProjectsWorkspace = false,
                        onSelectFilter = viewModel::setFilter,
                        onPickVideo = openSourcePickerForVideo,
                        onPickPhoto = openSourcePickerForPhoto,
                        onOpenProject = { project ->
                            viewModel.openProject(project.id) {
                                navController.navigate(ROUTE_EDITOR) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onReExportProject = { project ->
                            viewModel.startExport(project)
                        },
                        onRenameProject = viewModel::renameProject,
                        onDeleteProject = viewModel::deleteProject,
                    )
                }

                composable(ROUTE_PROJECTS) {
                    HomeDashboardScreen(
                        projects = projects,
                        selectedFilter = selectedFilter,
                        isImporting = isImporting,
                        importStatusText = importStatusText,
                        showOnlyProjectsWorkspace = true,
                        onSelectFilter = viewModel::setFilter,
                        onPickVideo = openSourcePickerForVideo,
                        onPickPhoto = openSourcePickerForPhoto,
                        onOpenProject = { project ->
                            viewModel.openProject(project.id) {
                                navController.navigate(ROUTE_EDITOR) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onReExportProject = { project ->
                            viewModel.startExport(project)
                        },
                        onRenameProject = viewModel::renameProject,
                        onDeleteProject = viewModel::deleteProject,
                    )
                }

                composable(ROUTE_EDITOR) {
                    val project = activeProject
                    if (project != null) {
                        EditorWorkspaceScreen(
                            project = project,
                            activePresetId = activePresetId,
                            isExporting = exportState.isRunning,
                            onBack = {
                                viewModel.saveProjectStateNow()
                                navController.popBackStack()
                            },
                            onRenameProject = { newName ->
                                viewModel.renameProject(project.id, newName)
                            },
                            onUpdateRestoration = viewModel::updateRestoration,
                            onUpdateExportSettings = viewModel::updateExportSettings,
                            onApplyPreset = viewModel::applyPreset,
                            onExportClick = { viewModel.startExport() },
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            navController.popBackStack()
                        }
                    }
                }
            }
        }
    }

    pendingPickerMediaType?.let { targetMediaType ->
        MediaSourcePickerDialog(
            mediaType = targetMediaType,
            onDismiss = { pendingPickerMediaType = null },
            onSelectSource = { sourceType ->
                launchSelectedSource(targetMediaType, sourceType)
            },
        )
    }

    activeProject?.let { current ->
        ExportModalDialog(
            project = current,
            exportState = exportState,
            mediaStorage = viewModel.mediaStorage,
            onTogglePause = viewModel::togglePauseExport,
            onCancel = viewModel::cancelExport,
            onSaveState = viewModel::saveProjectStateNow,
            onSaveToGallery = viewModel::saveExportedFileToGallery,
            onDismiss = viewModel::dismissExportModal,
        )
    }
}
