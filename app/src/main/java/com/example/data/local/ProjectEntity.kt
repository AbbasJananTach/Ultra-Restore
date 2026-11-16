package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.AiModelMode
import com.example.domain.model.ExportSettings
import com.example.domain.model.MediaType
import com.example.domain.model.ProjectStatus
import com.example.domain.model.RestorationSettings
import com.example.domain.model.StudioProject
import com.example.domain.model.TargetFps
import com.example.domain.model.TargetResolution
import com.example.domain.model.TurboSpeed

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: String,
    val sourcePath: String,
    val thumbnailPath: String,
    val exportedPath: String?,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalFps: Float,
    val durationMs: Long,
    @ColumnInfo(defaultValue = "42.0")
    val deblock: Float = 42f,
    val deblur: Float,
    val sharpness: Float,
    val denoise: Float,
    val clarity: Float,
    val shadowRecovery: Float,
    val vibrance: Float,
    @ColumnInfo(defaultValue = "ESRGAN_4X")
    val aiModelMode: String = "ESRGAN_4X",
    val targetResolutionName: String,
    val targetFps: Int,
    val motionInterpolation: Boolean,
    val bitrateMbps: Float,
    val turboSpeed: Int,
    val photoFormatPng: Boolean,
    val status: String,
    val lastProgress: Float,
    val updatedAt: Long,
    val createdAt: Long,
) {
    fun toDomain(): StudioProject {
        val parsedType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.PHOTO)
        val parsedResolution = runCatching { TargetResolution.valueOf(targetResolutionName) }
            .getOrDefault(TargetResolution.FHD_1080P)
        val parsedStatus = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.DRAFT)
        val parsedAiMode = AiModelMode.fromName(aiModelMode)

        return StudioProject(
            id = id,
            name = name,
            mediaType = parsedType,
            sourcePath = sourcePath,
            thumbnailPath = thumbnailPath,
            exportedPath = exportedPath,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            originalFps = originalFps,
            durationMs = durationMs,
            restoration = RestorationSettings(
                deblock = deblock,
                deblur = deblur,
                sharpness = sharpness,
                denoise = denoise,
                clarity = clarity,
                shadowRecovery = shadowRecovery,
                vibrance = vibrance,
                aiModelMode = parsedAiMode,
            ),
            exportSettings = ExportSettings(
                resolution = parsedResolution,
                fps = TargetFps.fromInt(targetFps),
                motionInterpolation = motionInterpolation,
                bitrateMbps = bitrateMbps,
                turboSpeed = TurboSpeed.fromInt(turboSpeed),
                photoFormatPng = photoFormatPng,
            ),
            status = parsedStatus,
            lastProgress = lastProgress,
            updatedAt = updatedAt,
            createdAt = createdAt,
        )
    }

    companion object {
        fun fromDomain(project: StudioProject): ProjectEntity = ProjectEntity(
            id = project.id,
            name = project.name,
            mediaType = project.mediaType.name,
            sourcePath = project.sourcePath,
            thumbnailPath = project.thumbnailPath,
            exportedPath = project.exportedPath,
            originalWidth = project.originalWidth,
            originalHeight = project.originalHeight,
            originalFps = project.originalFps,
            durationMs = project.durationMs,
            deblock = project.restoration.deblock,
            deblur = project.restoration.deblur,
            sharpness = project.restoration.sharpness,
            denoise = project.restoration.denoise,
            clarity = project.restoration.clarity,
            shadowRecovery = project.restoration.shadowRecovery,
            vibrance = project.restoration.vibrance,
            aiModelMode = project.restoration.aiModelMode.name,
            targetResolutionName = project.exportSettings.resolution.name,
            targetFps = project.exportSettings.fps.fps,
            motionInterpolation = project.exportSettings.motionInterpolation,
            bitrateMbps = project.exportSettings.bitrateMbps,
            turboSpeed = project.exportSettings.turboSpeed.multiplier,
            photoFormatPng = project.exportSettings.photoFormatPng,
            status = project.status.name,
            lastProgress = project.lastProgress,
            updatedAt = project.updatedAt,
            createdAt = project.createdAt,
        )
    }
}
