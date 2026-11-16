package com.example.ui.screens

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOff
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.model.ExportSettings
import com.example.domain.model.MediaType
import com.example.domain.model.RestorationSettings
import com.example.domain.model.SmartPreset
import com.example.domain.model.SmartPresets
import com.example.domain.model.StudioProject
import com.example.domain.model.TargetFps
import com.example.domain.model.TargetResolution
import com.example.domain.model.TurboSpeed
import com.example.engine.gl.StudioViewportRenderer
import com.example.ui.components.RenameProjectDialog
import com.example.ui.components.StudioPillButton
import com.example.ui.components.StudioSlider
import com.example.ui.theme.BrightBorder
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.MatteBlack
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleBorder
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private enum class ControlTab(val title: String) {
    RESTORATION("Restoration"),
    QUALITY("Quality & FPS"),
    PRESETS("Presets"),
}

@Composable
fun EditorWorkspaceScreen(
    project: StudioProject,
    activePresetId: String?,
    isExporting: Boolean,
    onBack: () -> Unit,
    onRenameProject: (String) -> Unit,
    onUpdateRestoration: ((RestorationSettings) -> RestorationSettings) -> Unit,
    onUpdateExportSettings: ((ExportSettings) -> ExportSettings) -> Unit,
    onApplyPreset: (SmartPreset) -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ControlTab.RESTORATION) }
    var splitPos by remember { mutableFloatStateOf(0.5f) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var showRenameDialog by remember { mutableStateOf(false) }

    var videoPosMs by remember { mutableLongStateOf(0L) }
    var videoDurationMs by remember { mutableLongStateOf(project.durationMs.coerceAtLeast(1L)) }
    var isVideoPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }

    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember(project.id) {
        StudioViewportRenderer(
            context = context,
            onRequestRender = { glSurfaceViewRef?.requestRender() },
            onVideoProgressUpdate = { pos, dur, playing ->
                videoPosMs = pos
                videoDurationMs = dur.coerceAtLeast(1L)
                isVideoPlaying = playing
            },
        )
    }

    val (outW, outH) = remember(project.originalWidth, project.originalHeight, project.exportSettings.resolution) {
        project.exportSettings.resolution.computeOutputSize(project.originalWidth, project.originalHeight)
    }

    LaunchedEffect(project.id, project.sourcePath) {
        renderer.loadMedia(
            type = project.mediaType,
            path = project.sourcePath,
            srcW = project.originalWidth,
            srcH = project.originalHeight,
            outW = outW,
            outH = outH,
        )
    }

    LaunchedEffect(project.restoration, outW, outH) {
        renderer.updateSettings(project.restoration, outW, outH)
    }

    LaunchedEffect(splitPos, zoomLevel, panX, panY) {
        renderer.updateViewportControls(splitPos, zoomLevel, panX, panY)
    }

    LaunchedEffect(isExporting) {
        if (isExporting) {
            renderer.pausePlayback()
        }
    }

    LaunchedEffect(isMuted) {
        renderer.setMuted(isMuted)
    }

    LaunchedEffect(project.id, project.mediaType, isVideoPlaying) {
        if (project.mediaType == MediaType.VIDEO) {
            while (true) {
                renderer.pollVideoProgress()
                delay(250L)
            }
        }
    }

    DisposableEffect(project.id) {
        onDispose {
            glSurfaceViewRef?.queueEvent {
                renderer.release()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MatteBlack),
    ) {
        // Compact Top Studio Bar with Crimson Red Export Button & White Text
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CarbonSurface)
                .border(1.dp, SubtleBorder)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("editor_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Studio",
                        tint = PureWhite,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showRenameDialog = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = CrimsonRed,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename project",
                            tint = PureWhite,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Text(
                        text = if (project.mediaType == MediaType.VIDEO) {
                            "${project.inputResolutionTag} → ${project.exportSettings.resolution.shortLabel} • ${project.exportSettings.fps.fps} FPS"
                        } else {
                            "${project.inputResolutionTag} → ${outW}x${outH}"
                        },
                        style = MonoTelemetryStyle,
                        color = PureWhite,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onExportClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrimsonRed,
                    contentColor = PureWhite,
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("editor_export_button"),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = PureWhite, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export", style = MaterialTheme.typography.labelLarge, color = PureWhite, fontWeight = FontWeight.Bold)
            }
        }

        // Interactive Before / After Live GPU Viewport
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MatteBlack),
        ) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        glSurfaceViewRef = this
                    }
                },
                update = { view ->
                    glSurfaceViewRef = view
                    view.requestRender()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(zoomLevel) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val nextZoom = (zoomLevel * zoomChange).coerceIn(1f, 4f)
                            zoomLevel = nextZoom
                            if (nextZoom > 1.02f) {
                                panX = (panX + pan.x / (widthPx * nextZoom)).coerceIn(-0.45f, 0.45f)
                                panY = (panY - pan.y / (heightPx * nextZoom)).coerceIn(-0.45f, 0.45f)
                            } else {
                                panX = 0f
                                panY = 0f
                            }
                        }
                    },
            )

            // Split-Screen Draggable Crimson Red Wipe Divider with Pure White Inner Handle Icon
            val lineOffsetPx = (splitPos * widthPx).roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(lineOffsetPx - 24.dp.roundToPx(), 0) }
                    .width(48.dp)
                    .fillMaxHeight()
                    .pointerInput(widthPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            splitPos = (splitPos + dragAmount.x / widthPx).coerceIn(0.05f, 0.95f)
                        }
                    }
                    .testTag("split_wipe_slider"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                listOf(CrimsonRed, CrimsonGlow, CrimsonRed),
                            ),
                        ),
                )
                Surface(
                    shape = CircleShape,
                    color = CrimsonRed,
                    border = BorderStroke(2.dp, PureWhite),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                            contentDescription = "Drag Before/After comparison line",
                            tint = PureWhite,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // ORIGINAL & ENHANCED Top Corner Badges
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CarbonSurface.copy(alpha = 0.85f))
                    .border(1.dp, BrightBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "ORIGINAL",
                    style = MonoTelemetryStyle,
                    color = PureWhite,
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CarbonSurface.copy(alpha = 0.9f))
                    .border(1.dp, CrimsonRed, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "ENHANCED",
                    style = MonoTelemetryStyle,
                    color = CrimsonRed,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Floating Bottom Overlay for Small Inner Editing Tool Controls (Zoom 1x/2x/4x, Play/Pause, Red Seekbar, Mute/Unmute)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Small Inner Zoom Toggle Buttons
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(1f, 2f, 4f).forEach { targetZ ->
                        val active = kotlin.math.abs(zoomLevel - targetZ) < 0.25f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (active) CrimsonRed else CarbonElevated.copy(alpha = 0.92f))
                                .border(1.dp, if (active) PureWhite else BrightBorder, RoundedCornerShape(7.dp))
                                .clickable {
                                    zoomLevel = targetZ
                                    if (targetZ == 1f) {
                                        panX = 0f
                                        panY = 0f
                                    }
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("zoom_${targetZ.toInt()}x_button"),
                        ) {
                            Text(
                                text = "${targetZ.toInt()}x",
                                style = MonoTelemetryStyle,
                                color = PureWhite,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Video Play/Pause + Red Timeline Seekbar + Mute/Unmute Floating Bar
                if (project.mediaType == MediaType.VIDEO) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CarbonSurface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, BrightBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { renderer.togglePlayPause() },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(CrimsonRed)
                                    .border(1.dp, PureWhite.copy(alpha = 0.7f), CircleShape)
                                    .testTag("video_play_pause_button"),
                            ) {
                                Icon(
                                    imageVector = if (isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isVideoPlaying) "Pause video" else "Play video",
                                    tint = PureWhite,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatMs(videoPosMs),
                                style = MonoTelemetryStyle,
                                color = PureWhite,
                            )
                            Slider(
                                value = (videoPosMs.toFloat() / videoDurationMs.toFloat()).coerceIn(0f, 1f),
                                onValueChange = { frac ->
                                    val targetMs = (frac * videoDurationMs).toLong()
                                    videoPosMs = targetMs
                                    renderer.seekTo(targetMs)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                                    .testTag("video_scrubber_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = PureWhite,
                                    activeTrackColor = CrimsonRed,
                                    inactiveTrackColor = SubtleBorder,
                                ),
                            )
                            Text(
                                text = formatMs(videoDurationMs),
                                style = MonoTelemetryStyle,
                                color = MutedSilver,
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { isMuted = !isMuted },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (isMuted) CarbonElevated else CrimsonRed)
                                    .border(1.dp, if (isMuted) BrightBorder else PureWhite.copy(alpha = 0.7f), CircleShape)
                                    .testTag("video_mute_toggle_button"),
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isMuted) "Unmute video audio" else "Mute video audio",
                                    tint = PureWhite,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = SubtleBorder, thickness = 1.dp)

        // Compact Bottom Control Panel (290.dp) with Red Active Tab Title & White Unselected Tool Text
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .background(CarbonSurface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MatteBlack)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ControlTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { selectedTab = tab }
                            .testTag("editor_tab_${tab.name.lowercase()}"),
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) CarbonSurface else CarbonElevated,
                        border = BorderStroke(1.dp, if (selected) CrimsonRed else BrightBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when (tab) {
                                    ControlTab.RESTORATION -> Icons.Default.Tune
                                    ControlTab.QUALITY -> Icons.Default.Settings
                                    ControlTab.PRESETS -> Icons.Default.AutoAwesome
                                },
                                contentDescription = null,
                                tint = if (selected) CrimsonRed else PureWhite,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) CrimsonRed else PureWhite,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                when (selectedTab) {
                    ControlTab.RESTORATION -> RestorationSlidersTab(
                        settings = project.restoration,
                        onUpdate = onUpdateRestoration,
                    )
                    ControlTab.QUALITY -> QualityTurboTab(
                        project = project,
                        outW = outW,
                        outH = outH,
                        onUpdate = onUpdateExportSettings,
                    )
                    ControlTab.PRESETS -> SmartPresetsTab(
                        activePresetId = activePresetId,
                        onApplyPreset = onApplyPreset,
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameProjectDialog(
            initialName = project.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                onRenameProject(newName)
                showRenameDialog = false
            },
        )
    }
}

@Composable
private fun RestorationSlidersTab(
    settings: RestorationSettings,
    onUpdate: ((RestorationSettings) -> RestorationSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Restoration",
                style = MaterialTheme.typography.titleMedium,
                color = CrimsonRed,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { onUpdate { RestorationSettings.Default } },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.testTag("reset_all_sliders_button"),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, tint = PureWhite, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset All", color = PureWhite, style = MaterialTheme.typography.labelLarge)
            }
        }

        StudioSlider(
            label = "Deblur & Edge Rebuild",
            value = settings.deblur,
            valueRange = 0f..100f,
            defaultValue = RestorationSettings.Default.deblur,
            icon = Icons.Default.BlurOff,
            testTag = "slider_deblur",
            onValueChange = { v -> onUpdate { it.copy(deblur = v) } },
        )

        StudioSlider(
            label = "Fine Detail Sharpness (CAS)",
            value = settings.sharpness,
            valueRange = 0f..100f,
            defaultValue = RestorationSettings.Default.sharpness,
            icon = Icons.Default.CenterFocusStrong,
            testTag = "slider_sharpness",
            onValueChange = { v -> onUpdate { it.copy(sharpness = v) } },
        )

        StudioSlider(
            label = "Denoise & Deblock",
            value = settings.denoise,
            valueRange = 0f..100f,
            defaultValue = RestorationSettings.Default.denoise,
            icon = Icons.Default.Grain,
            testTag = "slider_denoise",
            onValueChange = { v -> onUpdate { it.copy(denoise = v) } },
        )

        StudioSlider(
            label = "Clarity & Dehaze",
            value = settings.clarity,
            valueRange = 0f..100f,
            defaultValue = RestorationSettings.Default.clarity,
            icon = Icons.Default.Contrast,
            testTag = "slider_clarity",
            onValueChange = { v -> onUpdate { it.copy(clarity = v) } },
        )

        StudioSlider(
            label = "Shadow & Light Recovery",
            value = settings.shadowRecovery,
            valueRange = -100f..100f,
            defaultValue = RestorationSettings.Default.shadowRecovery,
            icon = Icons.Default.WbSunny,
            testTag = "slider_shadow_recovery",
            signedPrefix = true,
            onValueChange = { v -> onUpdate { it.copy(shadowRecovery = v) } },
        )

        StudioSlider(
            label = "Color Vibrance & Skin Warmth",
            value = settings.vibrance,
            valueRange = -100f..100f,
            defaultValue = RestorationSettings.Default.vibrance,
            icon = Icons.Default.Palette,
            testTag = "slider_vibrance",
            signedPrefix = true,
            onValueChange = { v -> onUpdate { it.copy(vibrance = v) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityTurboTab(
    project: StudioProject,
    outW: Int,
    outH: Int,
    onUpdate: ((ExportSettings) -> ExportSettings) -> Unit,
) {
    val exp = project.exportSettings
    val resolutions = remember(project.mediaType) { TargetResolution.forMediaType(project.mediaType) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Target Resolution Selector
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Quality & FPS",
                    style = MaterialTheme.typography.titleMedium,
                    color = CrimsonRed,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${outW}x${outH}",
                    style = MonoTelemetryStyle,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                resolutions.forEach { res ->
                    StudioPillButton(
                        text = res.shortLabel,
                        selected = exp.resolution == res,
                        onClick = { onUpdate { it.copy(resolution = res) } },
                        testTag = "res_pill_${res.name.lowercase()}",
                    )
                }
            }
        }

        if (project.mediaType == MediaType.VIDEO) {
            // 2. Frame Rate (FPS) Selector + Motion Interpolation
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Frame Rate",
                    style = MaterialTheme.typography.titleSmall,
                    color = CrimsonRed,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TargetFps.entries.forEach { fpsOption ->
                        StudioPillButton(
                            text = fpsOption.shortLabel,
                            selected = exp.fps == fpsOption,
                            onClick = { onUpdate { it.copy(fps = fpsOption) } },
                            testTag = "fps_pill_${fpsOption.fps}",
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CarbonElevated,
                    border = BorderStroke(1.dp, if (exp.motionInterpolation) CrimsonRed.copy(alpha = 0.65f) else BrightBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smooth Motion Interpolation",
                                style = MaterialTheme.typography.titleSmall,
                                color = PureWhite,
                            )
                            Text(
                                text = "Frame blending for 50/60 FPS exports",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedSilver,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = exp.motionInterpolation,
                            onCheckedChange = { checked -> onUpdate { it.copy(motionInterpolation = checked) } },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = CrimsonRed,
                                uncheckedThumbColor = MutedSilver,
                                uncheckedTrackColor = MatteBlack,
                            ),
                            modifier = Modifier.testTag("motion_interpolation_switch"),
                        )
                    }
                }
            }

            // 3. Bitrate / Quality Controller Slider
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioSlider(
                    label = "Target Video Bitrate",
                    value = exp.bitrateMbps,
                    valueRange = 4f..35f,
                    defaultValue = 18f,
                    icon = Icons.Default.Details,
                    unitSuffix = " Mbps",
                    testTag = "slider_bitrate",
                    onValueChange = { mbps -> onUpdate { it.copy(bitrateMbps = mbps) } },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "Standard (10M)" to 10f,
                        "High (18M)" to 18f,
                        "Ultra (30M)" to 30f,
                    ).forEach { (label, mbps) ->
                        StudioPillButton(
                            text = label,
                            selected = kotlin.math.abs(exp.bitrateMbps - mbps) < 1.5f,
                            onClick = { onUpdate { it.copy(bitrateMbps = mbps) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 4. High-Speed Turbo Render Mode
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = CrimsonRed, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Turbo Hardware Render Speed",
                        style = MaterialTheme.typography.titleSmall,
                        color = CrimsonRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TurboSpeed.entries.forEach { speed ->
                        StudioPillButton(
                            text = speed.shortLabel,
                            selected = exp.turboSpeed == speed,
                            onClick = { onUpdate { it.copy(turboSpeed = speed) } },
                            testTag = "turbo_pill_${speed.multiplier}x",
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Photo Export Format",
                    style = MaterialTheme.typography.titleSmall,
                    color = CrimsonRed,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioPillButton(
                        text = "PNG (Lossless)",
                        selected = exp.photoFormatPng,
                        onClick = { onUpdate { it.copy(photoFormatPng = true) } },
                        modifier = Modifier.weight(1f),
                        testTag = "photo_format_png",
                    )
                    StudioPillButton(
                        text = "JPG (100% Quality)",
                        selected = !exp.photoFormatPng,
                        onClick = { onUpdate { it.copy(photoFormatPng = false) } },
                        modifier = Modifier.weight(1f),
                        testTag = "photo_format_jpg",
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartPresetsTab(
    activePresetId: String?,
    onApplyPreset: (SmartPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Presets",
            style = MaterialTheme.typography.titleMedium,
            color = CrimsonRed,
            fontWeight = FontWeight.Bold,
        )

        SmartPresets.All.forEach { preset ->
            val selected = preset.id == activePresetId
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onApplyPreset(preset) }
                    .testTag("preset_card_${preset.id}"),
                shape = RoundedCornerShape(12.dp),
                color = CarbonElevated,
                border = BorderStroke(1.dp, if (selected) CrimsonRed else BrightBorder),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) CrimsonRed else PureWhite,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = preset.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSilver,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) CrimsonRed else CarbonSurface)
                            .border(1.dp, if (selected) CrimsonGlow else BrightBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = if (selected) "ACTIVE" else "Apply",
                            style = MaterialTheme.typography.labelLarge,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
