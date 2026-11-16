package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.MediaStorageHelper
import com.example.domain.model.ExportProgressState
import com.example.domain.model.MediaType
import com.example.domain.model.StudioProject
import com.example.ui.theme.BrightBorder
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.SubtleBorder
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ExportModalDialog(
    project: StudioProject,
    exportState: ExportProgressState,
    mediaStorage: MediaStorageHelper,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onSaveState: () -> Unit,
    onSaveToGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!exportState.isVisible) return

    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(exportState.isRunning) {
        if (exportState.isRunning) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    Dialog(
        onDismissRequest = {
            if (!exportState.isRunning) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !exportState.isRunning,
            dismissOnClickOutside = !exportState.isRunning,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = CarbonSurface,
            border = BorderStroke(1.dp, CrimsonRed.copy(alpha = 0.65f)),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                exportState.errorMessage != null -> "Render Failed"
                                exportState.isCompleted -> "Restoration Complete"
                                exportState.isPaused -> "Render Paused"
                                else -> "Rendering Media"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            color = CrimsonRed,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (!exportState.isRunning) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("close_export_modal_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close export modal",
                                tint = MutedSilver,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (exportState.errorMessage != null) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Render error",
                        tint = CrimsonGlow,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = exportState.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PureWhite,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed, contentColor = PureWhite),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                } else {
                    CircularCrimsonProgress(
                        progress = if (exportState.isCompleted) 1f else exportState.progress,
                        size = 128.dp,
                        strokeWidth = 9.dp,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (exportState.isCompleted) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Export complete",
                                    tint = CrimsonRed,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                            }
                            Text(
                                text = "${(exportState.progress * 100f).roundToInt().coerceIn(0, 100)}%",
                                style = MaterialTheme.typography.headlineMedium,
                                color = CrimsonRed,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (exportState.isCompleted) "READY" else if (exportState.isPaused) "PAUSED" else "ACTIVE",
                                style = MonoTelemetryStyle,
                                color = PureWhite,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CarbonElevated)
                            .border(1.dp, SubtleBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TelemetryStat(
                            label = "ELAPSED",
                            value = formatDurationMs(exportState.elapsedMs),
                        )
                        TelemetryStat(
                            label = "REMAINING",
                            value = if (exportState.isCompleted) "00:00" else formatDurationMs(exportState.estimatedRemainingMs),
                        )
                        TelemetryStat(
                            label = "SPEED",
                            value = String.format(Locale.US, "%.1f FPS", exportState.currentFps),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = project.outputSummaryTag,
                        style = MonoTelemetryStyle,
                        color = MutedSilver,
                    )

                    Spacer(Modifier.height(18.dp))

                    if (exportState.isCompleted) {
                        val saved = exportState.savedToGalleryUri != null
                        Button(
                            onClick = onSaveToGallery,
                            enabled = !saved,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonRed,
                                contentColor = PureWhite,
                                disabledContainerColor = CarbonElevated,
                                disabledContentColor = StatusGreen,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_to_gallery_button"),
                        ) {
                            Icon(
                                imageVector = if (saved) Icons.Default.CheckCircle else Icons.Default.Download,
                                contentDescription = null,
                                tint = if (saved) StatusGreen else PureWhite,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (saved) "Saved to Device Gallery" else "Save to Gallery",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (saved) StatusGreen else PureWhite,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val path = exportState.outputFilePath ?: return@OutlinedButton
                                    val file = File(path)
                                    if (file.exists()) {
                                        val shareUri = mediaStorage.getShareUri(file)
                                        val mime = if (project.mediaType == MediaType.VIDEO) "video/mp4" else "image/*"
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = mime
                                            putExtra(Intent.EXTRA_STREAM, shareUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share Restored Media"))
                                    }
                                },
                                border = BorderStroke(1.dp, BrightBorder),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("share_export_button"),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = PureWhite, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", color = PureWhite)
                            }

                            OutlinedButton(
                                onClick = onDismiss,
                                border = BorderStroke(1.dp, BrightBorder),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                            ) {
                                Text("Done", color = PureWhite)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onTogglePause,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CarbonElevated,
                                    contentColor = PureWhite,
                                ),
                                border = BorderStroke(1.dp, BrightBorder),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("pause_resume_render_button"),
                            ) {
                                Icon(
                                    imageVector = if (exportState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (exportState.isPaused) "Resume" else "Pause",
                                    color = PureWhite,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CrimsonRed,
                                    contentColor = PureWhite,
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("cancel_render_button"),
                            ) {
                                Text("Cancel", color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onSaveState,
                            border = BorderStroke(1.dp, BrightBorder),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("save_project_state_button"),
                        ) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = PureWhite, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Save Project State", color = PureWhite)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedSilver,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MonoTelemetryStyle,
            color = PureWhite,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
