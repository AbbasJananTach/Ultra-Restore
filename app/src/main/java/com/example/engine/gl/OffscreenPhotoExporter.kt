package com.example.engine.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.example.data.local.MediaStorageHelper
import com.example.domain.model.StudioProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PhotoExportProgress(
    val progress: Float,
    val framesProcessed: Int,
    val totalFrames: Int,
    val currentFps: Float,
)

class OffscreenPhotoExporter {

    suspend fun exportPhoto(
        project: StudioProject,
        outputFile: File,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onProgress: (PhotoExportProgress) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()
        onProgress(PhotoExportProgress(0.08f, 0, 4, 0f))

        while (isPaused()) {
            if (isCancelled()) throw InterruptedException("Render cancelled")
            delay(100)
        }
        if (isCancelled()) throw InterruptedException("Render cancelled")

        val srcBitmap = MediaStorageHelper.loadExifOrientedBitmap(project.sourcePath, maxDimension = 3072)
            ?: throw IllegalStateException("Failed to decode source photo.")

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        val pipeline = GlShaderPipeline()
        val texArr = IntArray(1)

        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                configAttribs[1] = EGL14.EGL_OPENGL_ES2_BIT
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            }
            val eglConfig = configs[0] ?: throw IllegalStateException("No EGLConfig found for offscreen photo GPU.")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            val probePbuffer = EGL14.eglCreatePbufferSurface(
                eglDisplay,
                eglConfig,
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE),
                0,
            )
            EGL14.eglMakeCurrent(eglDisplay, probePbuffer, probePbuffer, eglContext)
            val maxTexArr = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexArr, 0)
            val maxTexSize = maxTexArr[0].coerceIn(1024, 4096)
            EGL14.eglDestroySurface(eglDisplay, probePbuffer)

            val (outW, outH) = project.exportSettings.resolution.computeOutputSize(
                srcBitmap.width,
                srcBitmap.height,
                maxDimension = maxTexSize,
            )

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, outW,
                EGL14.EGL_HEIGHT, outH,
                EGL14.EGL_NONE,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            onProgress(PhotoExportProgress(0.25f, 1, 4, 1f))
            while (isPaused()) {
                if (isCancelled()) throw InterruptedException("Render cancelled")
                delay(100)
            }
            if (isCancelled()) throw InterruptedException("Render cancelled")

            pipeline.init()
            GLES30.glGenTextures(1, texArr, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texArr[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, srcBitmap, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            onProgress(PhotoExportProgress(0.50f, 2, 4, 2f))

            // Match StudioViewportRenderer upright coordinate space (flipInputY = true)
            // so directional kernels and scratch healing match live preview pixel-for-pixel.
            pipeline.process2DTexture(
                inputTexId = texArr[0],
                srcWidth = srcBitmap.width,
                srcHeight = srcBitmap.height,
                dstWidth = outW,
                dstHeight = outH,
                settings = project.restoration,
                flipInputY = true,
                isPhoto = true,
                turboMode = 1,
            )

            // Flip vertically back before glReadPixels (bottom-to-top) so Bitmap row 0 is top of image.
            pipeline.renderToCurrentSurface(
                textureId = pipeline.enhancedTextureId,
                surfaceWidth = outW,
                surfaceHeight = outH,
                flipY = true,
            )
            GLES30.glFinish()

            onProgress(PhotoExportProgress(0.75f, 3, 4, 3f))
            while (isPaused()) {
                if (isCancelled()) throw InterruptedException("Render cancelled")
                delay(100)
            }
            if (isCancelled()) throw InterruptedException("Render cancelled")

            val pixelBuffer = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer)
            pixelBuffer.rewind()

            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            outBitmap.copyPixelsFromBuffer(pixelBuffer)

            FileOutputStream(outputFile).use { stream ->
                val format = if (project.exportSettings.photoFormatPng) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                outBitmap.compress(format, 100, stream)
            }
            outBitmap.recycle()

            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000f).coerceAtLeast(0.01f)
            onProgress(PhotoExportProgress(1.0f, 4, 4, 4f / elapsedSec))
            outputFile
        } finally {
            srcBitmap.recycle()
            if (texArr[0] != 0) GLES30.glDeleteTextures(1, texArr, 0)
            pipeline.release()
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }
}
