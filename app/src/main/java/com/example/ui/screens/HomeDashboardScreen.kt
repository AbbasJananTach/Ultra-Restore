package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.model.MediaType
import com.example.domain.model.StudioProject
import com.example.ui.ProjectFilter
import com.example.ui.components.ProjectStatusBadge
import com.example.ui.components.RenameProjectDialog
import com.example.ui.theme.BrightBorder
import com.example.ui.theme.CarbonCard
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonDark
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.CrimsonMuted
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.MatteBlack
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleBorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeDashboardScreen(
    projects: List<StudioProject>,
    selectedFilter: ProjectFilter,
    isImporting: Boolean,
    importStatusText: String,
    showOnlyProjectsWorkspace: Boolean,
    onSelectFilter: (ProjectFilter) -> Unit,
    onStartNewProject: () -> Unit,
    onOpenProject: (StudioProject) -> Unit,
    onReExportProject: (StudioProject) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (StudioProject) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingTarget by remember { mutableStateOf<StudioProject?>(null) }

    Box(modifier = modifier.fillMaxSize().background(MatteBlack)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!showOnlyProjectsWorkspace) {
                item {
                    CleanTopStudioHeader()
                }

                item {
                    UnifiedHeroNewProjectCard(
                        onStartNewProject = onStartNewProject,
                    )
                }
            } else {
                item {
                    ProjectsScreenHeader(
                        onStartNewProject = onStartNewProject,
                    )
                }
            }

            item {
                RecentProjectsHeaderBar(
                    selectedFilter = selectedFilter,
                    onSelectFilter = onSelectFilter,
                )
            }

            if (projects.isEmpty()) {
                item {
                    EmptyProjectsCard(
                        selectedFilter = selectedFilter,
                    )
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectWorkspaceCard(
                        project = project,
                        onResume = { onOpenProject(project) },
                        onReExport = { onReExportProject(project) },
                        onRename = { renamingTarget = project },
                        onDelete = { onDeleteProject(project) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        if (isImporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MatteBlack.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CarbonSurface,
                    border = BorderStroke(1.dp, CrimsonGlow),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = CrimsonGlow, strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = importStatusText,
                            style = MaterialTheme.typography.titleMedium,
                            color = CrimsonGlow,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Configuring OpenGL ES 3.0 restoration pipeline",
                            style = MaterialTheme.typography.bodySmall,
                            color = PureWhite,
                        )
                    }
                }
            }
        }
    }

    renamingTarget?.let { target ->
        RenameProjectDialog(
            initialName = target.name,
            onDismiss = { renamingTarget = null },
            onConfirm = { newName ->
                onRenameProject(target.id, newName)
                renamingTarget = null
            },
        )
    }
}

@Composable
private fun CleanTopStudioHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CrimsonDark)
                .border(1.dp, CrimsonGlow.copy(alpha = 0.8f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AutoFixHigh,
                contentDescription = "UltraRestore Logo",
                tint = CrimsonGlow,
                modifier = Modifier.size(23.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "UltraRestore",
                style = MaterialTheme.typography.headlineMedium,
                color = CrimsonGlow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Pro Video & Old Photo Restoration Studio",
                style = MaterialTheme.typography.bodySmall,
                color = MutedSilver,
            )
        }
    }
}

@Composable
private fun ProjectsScreenHeader(
    onStartNewProject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(CrimsonDark)
                    .border(1.dp, CrimsonGlow.copy(alpha = 0.75f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = "UltraRestore Logo",
                    tint = CrimsonGlow,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Projects Workspace",
                    style = MaterialTheme.typography.titleLarge,
                    color = CrimsonGlow,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Saved GPU restoration sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSilver,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Button(
            onClick = onStartNewProject,
            colors = ButtonDefaults.buttonColors(
                containerColor = CrimsonRed,
                contentColor = PureWhite,
            ),
            border = BorderStroke(1.dp, CrimsonGlow),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier
                .height(40.dp)
                .testTag("projects_tab_new_project_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "New Project",
                style = MaterialTheme.typography.labelLarge,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun UnifiedHeroNewProjectCard(
    onStartNewProject: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onStartNewProject)
            .testTag("hero_unified_new_project_card"),
        shape = RoundedCornerShape(18.dp),
        color = CarbonCard,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                listOf(CrimsonGlow, BrightBorder, CrimsonGlow.copy(alpha = 0.6f)),
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            CrimsonDark.copy(alpha = 0.72f),
                            CarbonCard,
                            CarbonSurface,
                        ),
                    ),
                )
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CrimsonMuted)
                        .border(1.dp, CrimsonGlow.copy(alpha = 0.75f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = CrimsonGlow,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "New Restoration Project",
                        style = MaterialTheme.typography.titleLarge,
                        color = CrimsonGlow,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "1080p 60FPS Video Enhancer • 4x Old Photo Restorer",
                        style = MaterialTheme.typography.bodySmall,
                        color = PureWhite,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Mobile Gallery • Google Photos • Google Drive",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSilver,
                    )
                }
            }

            // Pipeline Capability Pills Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CarbonElevated)
                        .border(1.dp, SubtleBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.MovieCreation,
                        contentDescription = null,
                        tint = CrimsonGlow,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Video • 1080p 60FPS",
                        style = MaterialTheme.typography.labelMedium,
                        color = PureWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CarbonElevated)
                        .border(1.dp, SubtleBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = CrimsonGlow,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Photo • 4x Scratch Fix",
                        style = MaterialTheme.typography.labelMedium,
                        color = PureWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Single Primary CTA Button
            Button(
                onClick = onStartNewProject,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrimsonRed,
                    contentColor = PureWhite,
                ),
                border = BorderStroke(1.dp, CrimsonGlow),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("start_new_project_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Start New Project",
                    style = MaterialTheme.typography.titleSmall,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun RecentProjectsHeaderBar(
    selectedFilter: ProjectFilter,
    onSelectFilter: (ProjectFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Recent Projects",
            style = MaterialTheme.typography.headlineSmall,
            color = CrimsonGlow,
            fontWeight = FontWeight.Bold,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ProjectFilter.ALL to "All",
                ProjectFilter.VIDEO to "Videos",
                ProjectFilter.PHOTO to "Photos",
            ).forEach { (filter, shortLabel) ->
                val selected = filter == selectedFilter
                Surface(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("filter_${filter.name.lowercase()}")
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelectFilter(filter) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) CrimsonRed else CarbonElevated,
                    border = BorderStroke(1.dp, if (selected) CrimsonGlow else BrightBorder),
                ) {
                    Text(
                        text = shortLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = PureWhite,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectWorkspaceCard(
    project: StudioProject,
    onResume: () -> Unit,
    onReExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val compactSpecTag = if (project.mediaType == MediaType.VIDEO) {
        "${project.inputResolutionTag} → ${project.exportSettings.resolution.shortLabel} • ${project.exportSettings.fps.fps} FPS"
    } else {
        val (outW, outH) = project.exportSettings.resolution.computeOutputSize(project.originalWidth, project.originalHeight)
        "${project.inputResolutionTag} → ${outW}x${outH}"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onResume)
            .testTag("project_card_${project.id}"),
        shape = RoundedCornerShape(14.dp),
        color = CarbonSurface,
        border = BorderStroke(1.dp, SubtleBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CarbonElevated)
                    .border(1.dp, BrightBorder, RoundedCornerShape(10.dp)),
            ) {
                AsyncImage(
                    model = File(project.thumbnailPath),
                    contentDescription = project.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (project.mediaType == MediaType.VIDEO) CrimsonRed else CarbonSurface.copy(alpha = 0.9f))
                        .border(0.5.dp, if (project.mediaType == MediaType.VIDEO) CrimsonGlow else BrightBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = project.mediaType.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (project.mediaType == MediaType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MatteBlack.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = PureWhite,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ProjectStatusBadge(status = project.status)
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = compactSpecTag,
                    style = MonoTelemetryStyle,
                    color = CrimsonGlow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = dateFormatter.format(Date(project.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSilver,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonRed,
                        contentColor = PureWhite,
                    ),
                    border = BorderStroke(1.dp, CrimsonGlow.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("resume_project_button_${project.id}"),
                ) {
                    Text(
                        text = "Open",
                        style = MaterialTheme.typography.labelLarge,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                    )
                }

                IconButton(
                    onClick = onReExport,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(CarbonElevated)
                        .border(1.dp, BrightBorder, RoundedCornerShape(9.dp))
                        .testTag("reexport_project_button_${project.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Re-Export project",
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(CarbonElevated)
                            .border(1.dp, BrightBorder, RoundedCornerShape(9.dp))
                            .testTag("more_project_button_${project.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More project options",
                            tint = PureWhite,
                            modifier = Modifier.size(17.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(CarbonElevated),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = PureWhite) },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = PureWhite, modifier = Modifier.size(17.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                            modifier = Modifier.testTag("rename_project_button_${project.id}"),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = CrimsonGlow) },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = CrimsonGlow, modifier = Modifier.size(17.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            modifier = Modifier.testTag("delete_project_button_${project.id}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProjectsCard(
    selectedFilter: ProjectFilter,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("empty_projects_card"),
        shape = RoundedCornerShape(16.dp),
        color = CarbonSurface,
        border = BorderStroke(1.dp, SubtleBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(CrimsonMuted)
                    .border(1.dp, CrimsonGlow.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = CrimsonGlow,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when (selectedFilter) {
                    ProjectFilter.ALL -> "No Recent Projects Yet"
                    ProjectFilter.VIDEO -> "No Video Projects Yet"
                    ProjectFilter.PHOTO -> "No Photo Projects Yet"
                },
                style = MaterialTheme.typography.titleMedium,
                color = CrimsonGlow,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "No recent projects yet. Tap Start New Project above to begin.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedSilver,
                textAlign = TextAlign.Center,
            )
        }
    }
}
