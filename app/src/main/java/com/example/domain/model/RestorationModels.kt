package com.example.domain.model

import kotlin.math.max
import kotlin.math.roundToInt

fun scaleToMaxDimension(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
    val safeW = max(width, 16)
    val safeH = max(height, 16)
    val currentMax = max(safeW, safeH)
    val scale = if (currentMax > maxDim) maxDim.toFloat() / currentMax.toFloat() else 1f
    val alignedW = (((safeW * scale).roundToInt() + 2) / 4 * 4).coerceAtLeast(16)
    val alignedH = (((safeH * scale).roundToInt() + 2) / 4 * 4).coerceAtLeast(16)
    return alignedW to alignedH
}

enum class MediaType {
    VIDEO,
    PHOTO,
}

enum class ProjectStatus(val displayLabel: String) {
    DRAFT("Draft"),
    RENDERING("Rendering"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
}

enum class AiModelMode(
    val assetFileName: String,
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    ESRGAN_4X(
        assetFileName = "esrgan.tflite",
        label = "ESRGAN 4x Ultra HD",
        shortLabel = "ESRGAN 4x",
        description = "4x Ultra HD Super-Resolution (esrgan.tflite)",
    ),
    FACE_RESTORE(
        assetFileName = "face_restore.tflite",
        label = "Face & Portrait Restore",
        shortLabel = "Face Restore",
        description = "Portrait & Face Detail Reconstruction (face_restore.tflite)",
    ),
    VIDEO_ENHANCER(
        assetFileName = "video_enhancer.tflite",
        label = "Fast Video Frame Enhancer",
        shortLabel = "Video AI",
        description = "Fast Video Denoising & HD Upscaling (video_enhancer.tflite)",
    );

    companion object {
        fun fromName(name: String?): AiModelMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ESRGAN_4X
    }
}

enum class TargetResolution(
    val label: String,
    val shortLabel: String,
    val longEdge: Int,
    val shortEdge: Int,
    val isScaleMultiplier: Boolean = false,
    val multiplier: Int = 1,
    val photoOnly: Boolean = false,
) {
    HD_720P("720p HD (1280x720)", "720p HD", 1280, 720),
    FHD_1080P("1080p Full HD (1920x1080)", "1080p FHD", 1920, 1080),
    QHD_2K("2K QHD (2560x1440)", "2K QHD", 2560, 1440),
    UHD_4K("4K Ultra HD (3840x2160)", "4K UHD", 3840, 2160),
    SCALE_2X("2x Super Scale", "2x Scale", 0, 0, isScaleMultiplier = true, multiplier = 2, photoOnly = true),
    SCALE_4X("4x Super Scale", "4x Scale", 0, 0, isScaleMultiplier = true, multiplier = 4, photoOnly = true);

    fun computeOutputSize(srcWidth: Int, srcHeight: Int, maxDimension: Int = 4096): Pair<Int, Int> {
        val safeW = max(srcWidth, 64)
        val safeH = max(srcHeight, 64)
        val isPortrait = safeH > safeW

        val (rawW, rawH) = if (isScaleMultiplier) {
            (safeW * multiplier) to (safeH * multiplier)
        } else {
            val aspect = safeW.toFloat() / safeH.toFloat()
            if (isPortrait) {
                val targetH = longEdge
                val targetW = (targetH * aspect).roundToInt()
                targetW to targetH
            } else {
                val targetW = longEdge
                val targetH = (targetW / aspect).roundToInt()
                targetW to targetH
            }
        }

        val scaleClamp = if (max(rawW, rawH) > maxDimension) {
            maxDimension.toFloat() / max(rawW, rawH).toFloat()
        } else {
            1f
        }

        // Align to multiples of 4 for hardware MediaCodec & GPU texture alignment
        val alignedW = (((rawW * scaleClamp).roundToInt() + 2) / 4 * 4).coerceAtLeast(64)
        val alignedH = (((rawH * scaleClamp).roundToInt() + 2) / 4 * 4).coerceAtLeast(64)
        return alignedW to alignedH
    }

    companion object {
        fun forMediaType(type: MediaType): List<TargetResolution> = when (type) {
            MediaType.VIDEO -> listOf(HD_720P, FHD_1080P, QHD_2K, UHD_4K)
            MediaType.PHOTO -> listOf(HD_720P, FHD_1080P, QHD_2K, UHD_4K, SCALE_2X, SCALE_4X)
        }
    }
}

enum class TargetFps(val fps: Int, val label: String, val shortLabel: String) {
    FPS_24(24, "24 FPS (Cinematic)", "24 FPS"),
    FPS_30(30, "30 FPS (Standard)", "30 FPS"),
    FPS_50(50, "50 FPS", "50 FPS"),
    FPS_60(60, "60 FPS (Ultra Smooth)", "60 FPS");

    companion object {
        fun fromInt(value: Int): TargetFps = entries.firstOrNull { it.fps == value } ?: FPS_60
    }
}

enum class TurboSpeed(val multiplier: Int, val label: String, val shortLabel: String) {
    X1(1, "1x (Precision)", "1x Precision"),
    X2(2, "2x (Fast)", "2x Fast"),
    X3(3, "3x (Turbo)", "3x Turbo"),
    X4(4, "4x (Turbo HW)", "4x Turbo HW");

    companion object {
        fun fromInt(value: Int): TurboSpeed = entries.firstOrNull { it.multiplier == value } ?: X3
    }
}

data class RestorationSettings(
    val deblock: Float = 42f,          // Stage 1: De-Block Strength (0..100)
    val deblur: Float = 48f,           // Stage 3: Super-Resolution & Detail Recovery (0..100)
    val sharpness: Float = 56f,        // Stage 4: Face & Edge Sharpness / CAS (0..100)
    val denoise: Float = 40f,          // Stage 2: AI Denoise & Smoothness (0..100)
    val clarity: Float = 36f,          // Stage 5: HDR Color & Contrast (0..100)
    val shadowRecovery: Float = 14f,   // -100..100
    val vibrance: Float = 18f,         // -100..100
    val aiModelMode: AiModelMode = AiModelMode.ESRGAN_4X,
) {
    companion object {
        val Default = RestorationSettings()
        val Neutral = RestorationSettings(
            deblock = 0f,
            deblur = 0f,
            sharpness = 0f,
            denoise = 0f,
            clarity = 0f,
            shadowRecovery = 0f,
            vibrance = 0f,
            aiModelMode = AiModelMode.ESRGAN_4X,
        )
    }
}

data class ExportSettings(
    val resolution: TargetResolution = TargetResolution.FHD_1080P,
    val fps: TargetFps = TargetFps.FPS_60,
    val motionInterpolation: Boolean = true,
    val bitrateMbps: Float = 28f, // 25..35 Mbps High-Profile H.264 default
    val turboSpeed: TurboSpeed = TurboSpeed.X3,
    val photoFormatPng: Boolean = true,
)

data class SmartPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val restoration: RestorationSettings,
    val recommendedFps: TargetFps? = null,
    val recommendedMotionInterpolation: Boolean? = null,
)

object SmartPresets {
    val All: List<SmartPreset> = listOf(
        SmartPreset(
            id = "480p_to_1080p",
            name = "480p to 1080p Crisp Fix",
            subtitle = "5-Stage AI deblock + ESRGAN edge sharpen for SD footage",
            restoration = RestorationSettings(
                deblock = 62f,
                deblur = 55f,
                sharpness = 65f,
                denoise = 48f,
                clarity = 35f,
                shadowRecovery = 10f,
                vibrance = 15f,
                aiModelMode = AiModelMode.VIDEO_ENHANCER,
            ),
            recommendedFps = TargetFps.FPS_60,
        ),
        SmartPreset(
            id = "heavy_blur",
            name = "Heavy Blur Recovery",
            subtitle = "Aggressive ESRGAN neural edge rebuild & unsharp recovery",
            restoration = RestorationSettings(
                deblock = 48f,
                deblur = 88f,
                sharpness = 80f,
                denoise = 35f,
                clarity = 45f,
                shadowRecovery = 8f,
                vibrance = 10f,
                aiModelMode = AiModelMode.ESRGAN_4X,
            ),
        ),
        SmartPreset(
            id = "vintage_photo",
            name = "Old Vintage Photo Revival",
            subtitle = "Face Restore AI + scratch healing & faded tone recovery",
            restoration = RestorationSettings(
                deblock = 54f,
                deblur = 52f,
                sharpness = 58f,
                denoise = 62f,
                clarity = 74f,
                shadowRecovery = 32f,
                vibrance = 44f,
                aiModelMode = AiModelMode.FACE_RESTORE,
            ),
        ),
        SmartPreset(
            id = "low_light",
            name = "Low-Light Clean & Sharp",
            subtitle = "Deep shadow lift with neural bilateral chroma denoise",
            restoration = RestorationSettings(
                deblock = 58f,
                deblur = 42f,
                sharpness = 48f,
                denoise = 78f,
                clarity = 30f,
                shadowRecovery = 65f,
                vibrance = 20f,
                aiModelMode = AiModelMode.VIDEO_ENHANCER,
            ),
        ),
        SmartPreset(
            id = "60fps_ultra_hd",
            name = "60 FPS Ultra Smooth HD",
            subtitle = "Motion optical frame blending + ESRGAN cinema contrast",
            restoration = RestorationSettings(
                deblock = 40f,
                deblur = 50f,
                sharpness = 62f,
                denoise = 38f,
                clarity = 42f,
                shadowRecovery = 16f,
                vibrance = 24f,
                aiModelMode = AiModelMode.ESRGAN_4X,
            ),
            recommendedFps = TargetFps.FPS_60,
            recommendedMotionInterpolation = true,
        ),
    )
}

data class StudioProject(
    val id: String,
    val name: String,
    val mediaType: MediaType,
    val sourcePath: String,
    val thumbnailPath: String,
    val exportedPath: String?,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalFps: Float,
    val durationMs: Long,
    val restoration: RestorationSettings,
    val exportSettings: ExportSettings,
    val status: ProjectStatus,
    val lastProgress: Float,
    val updatedAt: Long,
    val createdAt: Long,
) {
    val inputResolutionTag: String
        get() {
            val shortEdge = minOf(originalWidth, originalHeight)
            return when {
                shortEdge >= 2160 -> "4K"
                shortEdge >= 1440 -> "2K"
                shortEdge >= 1080 -> "1080p"
                shortEdge >= 720 -> "720p"
                shortEdge >= 480 -> "480p"
                shortEdge >= 360 -> "360p"
                else -> "${originalWidth}x${originalHeight}"
            }
        }

    val outputSummaryTag: String
        get() = if (mediaType == MediaType.VIDEO) {
            "$inputResolutionTag -> ${exportSettings.resolution.shortLabel} | ${exportSettings.fps.fps} FPS | ${restoration.aiModelMode.shortLabel}"
        } else {
            val (outW, outH) = exportSettings.resolution.computeOutputSize(originalWidth, originalHeight)
            "$inputResolutionTag -> ${outW}x${outH} | ${restoration.aiModelMode.shortLabel}"
        }
}

data class ExportProgressState(
    val isVisible: Boolean = false,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val progress: Float = 0f,
    val elapsedMs: Long = 0L,
    val estimatedRemainingMs: Long = 0L,
    val currentFps: Float = 0f,
    val framesProcessed: Int = 0,
    val totalFrames: Int = 0,
    val statusLabel: String = "TFLite GPU Active",
    val outputFilePath: String? = null,
    val savedToGalleryUri: String? = null,
    val errorMessage: String? = null,
)
