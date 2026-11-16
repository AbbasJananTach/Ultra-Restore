package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.ImportSourceType
import com.example.domain.model.MediaType
import com.example.domain.model.ProjectStatus
import com.example.ui.theme.BrightBorder
import com.example.ui.theme.CarbonCard
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonDark
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.CrimsonMuted
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.SubtleBorder
import kotlin.math.roundToInt

@Composable
fun StudioSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    icon: ImageVector,
    testTag: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unitSuffix: String = "%",
    signedPrefix: Boolean = false,
) {
    val intVal = value.roundToInt()
    val formatted = buildString {
        if (signedPrefix && intVal > 0) append("+")
        append(intVal)
        append(unitSuffix)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(defaultValue) {
                detectTapGestures(
                    onDoubleTap = { onValueChange(defaultValue) },
                )
            }
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CrimsonGlow,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kotlin.math.abs(value - defaultValue) > 1f) {
                    Text(
                        text = "2x reset",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSilver.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CarbonElevated)
                        .border(1.dp, if (intVal == 0) SubtleBorder else CrimsonGlow.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = formatted,
                        style = MonoTelemetryStyle,
                        color = if (intVal == 0) MutedSilver else CrimsonGlow,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = PureWhite,
                activeTrackColor = CrimsonRed,
                inactiveTrackColor = SubtleBorder,
                activeTickColor = CrimsonGlow,
                inactiveTickColor = BrightBorder,
            ),
        )
    }
}

@Composable
fun StudioPillButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) CrimsonRed else CarbonElevated,
        label = "pill_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) CrimsonGlow else BrightBorder,
        label = "pill_border",
    )

    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = PureWhite,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MediaSourcePickerDialog(
    initialMediaType: MediaType? = null,
    onDismiss: () -> Unit,
    onSelectMediaAndSource: (MediaType, ImportSourceType) -> Unit,
) {
    var selectedMediaType by remember(initialMediaType) { mutableStateOf(initialMediaType) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("media_source_picker_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = CarbonSurface,
            border = BorderStroke(1.dp, CrimsonGlow.copy(alpha = 0.7f)),
            tonalElevation = 10.dp,
        ) {
            AnimatedContent(
                targetState = selectedMediaType,
                label = "wizard_step_transition",
            ) { currentStepMedia ->
                if (currentStepMedia == null) {
                    // STEP 1: Choose Media Type (Enhance Video vs. Restore Old Photo)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "What would you like to restore?",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = CrimsonGlow,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Step 1 of 2 • Select restoration pipeline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSilver,
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("close_source_picker_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close dialog",
                                    tint = PureWhite,
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))

                        // Card 1: Enhance Video (Up to 1080p • 60 FPS)
                        WizardMediaTypeCard(
                            icon = Icons.Default.MovieCreation,
                            title = "Enhance Video",
                            subtitle = "Up to 1080p • 60 FPS",
                            detailText = "Deblur • Denoise • Motion Smooth",
                            badgeText = "VIDEO",
                            testTag = "wizard_step1_video",
                            onClick = { selectedMediaType = MediaType.VIDEO },
                        )

                        // Card 2: Restore Old Photo (4x Super Scale • Scratch & Face Fix)
                        WizardMediaTypeCard(
                            icon = Icons.Default.PhotoLibrary,
                            title = "Restore Old Photo",
                            subtitle = "4x Super Scale • Scratch & Face Fix",
                            detailText = "Scratch Removal • Clarity • Color",
                            badgeText = "PHOTO",
                            testTag = "wizard_step1_photo",
                            onClick = { selectedMediaType = MediaType.PHOTO },
                        )
                    }
                } else {
                    // STEP 2: Choose Source (Mobile Gallery, Google Photos, Google Drive)
                    val isVideo = currentStepMedia == MediaType.VIDEO
                    val mediaLabel = if (isVideo) "video" else "photo"
                    val mediaBadge = if (isVideo) "ENHANCE VIDEO" else "RESTORE PHOTO"

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                IconButton(
                                    onClick = { selectedMediaType = null },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(CarbonElevated)
                                        .border(1.dp, BrightBorder, RoundedCornerShape(10.dp))
                                        .testTag("wizard_step2_back_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Media Type",
                                        tint = PureWhite,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "Select Media Source",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = CrimsonGlow,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Step 2 of 2 • $mediaBadge",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedSilver,
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("close_source_picker_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close source picker",
                                    tint = PureWhite,
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))

                        // Option 1: Mobile Gallery (Local Device Storage)
                        SourceOptionCard(
                            icon = Icons.Default.PhoneAndroid,
                            title = "Mobile Gallery",
                            subtitle = "Local Device Storage • Pick $mediaLabel",
                            badgeText = "LOCAL",
                            testTag = "source_option_local_gallery",
                            onClick = {
                                onSelectMediaAndSource(currentStepMedia, ImportSourceType.LOCAL_GALLERY)
                            },
                        )

                        // Option 2: Google Photos (Cloud Library)
                        SourceOptionCard(
                            icon = Icons.Default.Collections,
                            title = "Google Photos (Cloud Library)",
                            subtitle = "Import $mediaLabel from Google Photos cloud",
                            badgeText = "CLOUD",
                            testTag = "source_option_google_photos",
                            onClick = {
                                onSelectMediaAndSource(currentStepMedia, ImportSourceType.GOOGLE_PHOTOS_CLOUD)
                            },
                        )

                        // Option 3: Google Drive (Cloud Storage)
                        SourceOptionCard(
                            icon = Icons.Default.CloudDownload,
                            title = "Google Drive (Cloud Storage)",
                            subtitle = "Browse Google Drive & cloud folders",
                            badgeText = "DRIVE",
                            testTag = "source_option_google_drive",
                            onClick = {
                                onSelectMediaAndSource(currentStepMedia, ImportSourceType.GOOGLE_DRIVE_CLOUD)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardMediaTypeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    detailText: String,
    badgeText: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = CarbonCard,
        border = BorderStroke(1.dp, BrightBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            CrimsonDark.copy(alpha = 0.65f),
                            CarbonCard,
                            CarbonCard,
                        ),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CrimsonMuted)
                        .border(1.dp, CrimsonGlow.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = CrimsonGlow,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(CrimsonDark)
                                .border(1.dp, CrimsonGlow.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = CrimsonGlow,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonGlow,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSilver,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SourceOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = CarbonElevated,
        border = BorderStroke(1.dp, BrightBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(CrimsonMuted)
                        .border(1.dp, CrimsonGlow.copy(alpha = 0.55f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = CrimsonGlow,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(CrimsonDark)
                                .border(1.dp, CrimsonGlow.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = CrimsonGlow,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun ProjectStatusBadge(status: ProjectStatus) {
    val (bg, fg, border) = when (status) {
        ProjectStatus.COMPLETED -> Triple(StatusGreen.copy(alpha = 0.14f), StatusGreen, StatusGreen.copy(alpha = 0.4f))
        ProjectStatus.RENDERING -> Triple(CrimsonMuted, CrimsonGlow, CrimsonGlow)
        ProjectStatus.PAUSED -> Triple(StatusAmber.copy(alpha = 0.14f), StatusAmber, StatusAmber.copy(alpha = 0.4f))
        ProjectStatus.DRAFT -> Triple(CarbonElevated, MutedSilver, SubtleBorder)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = status.displayLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
fun CircularCrimsonProgress(
    progress: Float,
    size: Dp = 124.dp,
    strokeWidth: Dp = 9.dp,
    centerContent: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size),
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)

            drawArc(
                color = SubtleBorder,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(CrimsonRed, CrimsonGlow, CrimsonRed),
                ),
                startAngle = -90f,
                sweepAngle = (progress.coerceIn(0f, 1f) * 360f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        centerContent()
    }
}

@Composable
fun RenameProjectDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        titleContentColor = CrimsonGlow,
        textContentColor = PureWhite,
        title = {
            Text(
                text = "Rename Project",
                style = MaterialTheme.typography.headlineSmall,
                color = CrimsonGlow,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Project Name") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PureWhite,
                    unfocusedTextColor = PureWhite,
                    focusedBorderColor = CrimsonGlow,
                    unfocusedBorderColor = SubtleBorder,
                    focusedLabelColor = CrimsonGlow,
                    unfocusedLabelColor = MutedSilver,
                    cursorColor = CrimsonGlow,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_project_input"),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed, contentColor = PureWhite),
                border = BorderStroke(1.dp, CrimsonGlow.copy(alpha = 0.6f)),
                modifier = Modifier.testTag("confirm_rename_button"),
            ) {
                Text("Save", color = PureWhite, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MutedSilver)
            }
        },
    )
}
