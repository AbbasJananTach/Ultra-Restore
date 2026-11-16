package com.example.engine.gl

import android.content.Context
import android.graphics.Bitmap
import com.example.data.local.MediaStorageHelper
import com.example.domain.model.StudioProject
import com.example.engine.ai.MultiStageAiPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PhotoExportProgress(
    val progress: Float,
    val framesProcessed: Int,
    val totalFrames: Int,
    val currentFps: Float,
    val statusLabel: String = "5-Stage TFLite GPU Active",
)

class OffscreenPhotoExporter(private val context: Context) {

    suspend fun exportPhoto(
        project: StudioProject,
        outputFile: File,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onProgress: (PhotoExportProgress) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()
        onProgress(
            PhotoExportProgress(
                progress = 0.03f,
                framesProcessed = 0,
                totalFrames = 5,
                currentFps = 0f,
                statusLabel = "Stage 1/5 • Initializing 5-Stage AI Engine...",
            ),
        )

        while (isPaused()) {
            if (isCancelled()) throw InterruptedException("Render cancelled")
            delay(100)
        }
        if (isCancelled()) throw InterruptedException("Render cancelled")

        // Cap source decode size to prevent OutOfMemoryError before tiled 4x upscale
        val maxSrcDim = if (project.exportSettings.resolution.multiplier >= 4) 1024 else 1600
        val srcBitmap = MediaStorageHelper.loadExifOrientedBitmap(project.sourcePath, maxDimension = maxSrcDim)
            ?: throw IllegalStateException("Failed to decode source photo.")

        val pipeline = MultiStageAiPipeline(context)

        try {
            val (outW, outH) = project.exportSettings.resolution.computeOutputSize(
                srcBitmap.width,
                srcBitmap.height,
                maxDimension = 4096,
            )

            // Run real 5-Stage Sequential TFLite + GPU pipeline (Stage 1/5 .. Stage 5/5)
            val enhancedBitmap = pipeline.processPhotoFiveStages(
                srcBitmap = srcBitmap,
                targetWidth = outW,
                targetHeight = outH,
                settings = project.restoration,
                isPaused = isPaused,
                isCancelled = isCancelled,
                onStageProgress = { sp ->
                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000f).coerceAtLeast(0.01f)
                    val stagesPerSec = sp.currentStage / elapsedSec
                    val overallProgress = (0.04f + sp.overallProgress * 0.90f).coerceIn(0.04f, 0.94f)
                    onProgress(
                        PhotoExportProgress(
                            progress = overallProgress,
                            framesProcessed = sp.currentStage,
                            totalFrames = sp.totalStages,
                            currentFps = stagesPerSec,
                            statusLabel = sp.statusLabel,
                        ),
                    )
                },
            )

            while (isPaused()) {
                if (isCancelled()) {
                    enhancedBitmap.recycle()
                    throw InterruptedException("Render cancelled")
                }
                delay(100)
            }
            if (isCancelled()) {
                enhancedBitmap.recycle()
                throw InterruptedException("Render cancelled")
            }

            onProgress(
                PhotoExportProgress(
                    progress = 0.96f,
                    framesProcessed = 5,
                    totalFrames = 5,
                    currentFps = 1f,
                    statusLabel = "Stage 5/5 • Encoding Ultra-HD Output...",
                ),
            )

            FileOutputStream(outputFile).use { stream ->
                val format = if (project.exportSettings.photoFormatPng) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                enhancedBitmap.compress(format, 100, stream)
            }
            enhancedBitmap.recycle()

            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000f).coerceAtLeast(0.01f)
            onProgress(
                PhotoExportProgress(
                    progress = 1.0f,
                    framesProcessed = 5,
                    totalFrames = 5,
                    currentFps = 5f / elapsedSec,
                    statusLabel = "Complete • ${pipeline.activeDelegateDescription}",
                ),
            )
            outputFile
        } finally {
            srcBitmap.recycle()
            pipeline.close()
        }
    }
}
