package com.example.engine.gl

import android.content.Context
import com.example.domain.model.StudioProject
import java.io.File

data class VideoExportProgress(
    val progress: Float,
    val framesProcessed: Int,
    val totalFrames: Int,
    val currentFps: Float,
    val elapsedMs: Long,
    val estimatedRemainingMs: Long,
    val statusLabel: String = "5-Stage TFLite + GPU Video Pipeline",
)

class OffscreenVideoExporter(context: Context) {

    private val videoEnhancerPipeline = VideoEnhancerPipeline(context)

    suspend fun exportVideo(
        project: StudioProject,
        outputFile: File,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onProgress: (VideoExportProgress) -> Unit,
    ): File = videoEnhancerPipeline.exportEnhancedVideo(
        project = project,
        outputFile = outputFile,
        isPaused = isPaused,
        isCancelled = isCancelled,
        onProgress = onProgress,
    )
}
