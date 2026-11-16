package com.example.engine.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.example.data.local.MediaStorageHelper
import com.example.domain.model.StudioProject
import com.example.domain.model.scaleToMaxDimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class VideoExportProgress(
    val progress: Float,
    val framesProcessed: Int,
    val totalFrames: Int,
    val currentFps: Float,
    val elapsedMs: Long,
    val estimatedRemainingMs: Long,
)

class OffscreenVideoExporter(private val context: Context) {

    private class MuxerContext(
        val muxer: MediaMuxer,
        val audioInputFormat: MediaFormat?,
        var videoTrackIndex: Int = -1,
        var audioTrackIndex: Int = -1,
        var started: Boolean = false,
        var encoderDone: Boolean = false,
    )

    suspend fun exportVideo(
        project: StudioProject,
        outputFile: File,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onProgress: (VideoExportProgress) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var sourcePfd: ParcelFileDescriptor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var encoderInputSurface: Surface? = null
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null

        val pipeline = GlShaderPipeline()
        val oesTexArr = IntArray(1)
        var prevFrameFbo = 0
        var prevFrameTex = 0

        try {
            if (MediaStorageHelper.isContentUri(project.sourcePath)) {
                val uri = Uri.parse(project.sourcePath)
                sourcePfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (sourcePfd != null) {
                    extractor.setDataSource(sourcePfd.fileDescriptor)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
            } else {
                extractor.setDataSource(project.sourcePath)
            }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoInputFormat: MediaFormat? = null
            var compatibleAudioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrackIndex < 0 && mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoInputFormat = fmt
                } else if (audioTrackIndex < 0 && mime.startsWith("audio/")) {
                    if (mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) ||
                        mime.startsWith("audio/mp4a", ignoreCase = true)
                    ) {
                        audioTrackIndex = i
                        compatibleAudioFormat = fmt
                    }
                }
            }

            if (videoTrackIndex < 0 || videoInputFormat == null) {
                throw IllegalStateException("No video track found in imported media.")
            }

            val srcMime = videoInputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val durationUs = if (videoInputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoInputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                (project.durationMs * 1000L).coerceAtLeast(1_000_000L)
            }

            // SurfaceTexture.getTransformMatrix(stMatrix) already rotates the decoded OES frame
            // into upright visual orientation (project.originalWidth x project.originalHeight).
            // Ensure outW x outH preserves upright orientation and MediaMuxer orientationHint = 0.
            val (desiredW, desiredH) = project.exportSettings.resolution.computeOutputSize(
                project.originalWidth,
                project.originalHeight,
                maxDimension = 2560,
            )
            val (outW, outH) = clampToEncoderCapabilities(desiredW, desiredH)
            val (srcScaledW, srcScaledH) = scaleToMaxDimension(
                project.originalWidth,
                project.originalHeight,
                maxDim = 1920,
            )

            val targetFps = project.exportSettings.fps.fps
            val frameIntervalUs = 1_000_000L / targetFps.toLong()
            val bitrateBps = (project.exportSettings.bitrateMbps * 1_000_000f).toInt().coerceIn(2_000_000, 40_000_000)
            val turboMultiplier = project.exportSettings.turboSpeed.multiplier
            val estimatedTotalFrames = ((durationUs / 1_000_000.0) * targetFps).toInt().coerceAtLeast(1)

            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (turboMultiplier >= 3) 2 else 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (turboMultiplier >= 2) {
                        setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps * turboMultiplier)
                    }
                }
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val eglVersion = IntArray(2)
            EGL14.eglInitialize(eglDisplay, eglVersion, 0, eglVersion, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                configAttribs[1] = EGL14.EGL_OPENGL_ES2_BIT
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            }
            val eglConfig = configs[0] ?: throw IllegalStateException("Unable to configure EGL for video encoder.")
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                encoderInputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            pipeline.init()
            pipeline.ensureFbos(
                srcWidth = srcScaledW,
                srcHeight = srcScaledH,
                dstWidth = outW,
                dstHeight = outH,
            )
            val useInterpolation = project.exportSettings.motionInterpolation && turboMultiplier < 3
            if (useInterpolation) {
                val prevPair = GlShaderPipeline.createFboWithTexture(outW, outH)
                prevFrameFbo = prevPair.first
                prevFrameTex = prevPair.second
            }

            GLES30.glGenTextures(1, oesTexArr, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexArr[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val frameSync = Object()
            val frameAvailable = AtomicBoolean(false)
            surfaceTexture = SurfaceTexture(oesTexArr[0]).apply {
                setOnFrameAvailableListener {
                    synchronized(frameSync) {
                        frameAvailable.set(true)
                        frameSync.notifyAll()
                    }
                }
            }
            decoderSurface = Surface(surfaceTexture)

            extractor.selectTrack(videoTrackIndex)
            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(videoInputFormat, decoderSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(0) // Upright orientation already applied by stMatrix in GLES
            }
            val muxerCtx = MuxerContext(muxer, compatibleAudioFormat)

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            val stMatrix = FloatArray(16)

            var inputDone = false
            var decoderDone = false
            var hasPrevFrame = false
            var nextEncodePtsUs = 0L
            var renderedFrames = 0
            var totalPausedMs = 0L

            val hasAudioTrack = compatibleAudioFormat != null && audioTrackIndex >= 0
            val videoPhaseMaxProgress = if (hasAudioTrack) 0.95f else 0.99f

            while (!muxerCtx.encoderDone) {
                while (isPaused()) {
                    if (isCancelled()) throw InterruptedException("Render cancelled")
                    val pauseTick = System.currentTimeMillis()
                    delay(120)
                    totalPausedMs += System.currentTimeMillis() - pauseTick
                }
                if (isCancelled()) throw InterruptedException("Render cancelled")

                // 1. Feed Decoder Input Buffers in a tight loop
                while (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val ptsUs = extractor.sampleTime
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                        extractor.advance()
                    }
                }

                // 2. Drain Decoder Output -> Skip redundant frames strictly when srcPtsUs < nextEncodePtsUs
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decoderInfo, 2_000L)
                    if (outIdx >= 0) {
                        val isEos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val srcPtsUs = decoderInfo.presentationTimeUs.coerceAtLeast(0L)
                        val isValidSize = decoderInfo.size > 0

                        val isFirstFrame = (nextEncodePtsUs == 0L && renderedFrames == 0)
                        val shouldRenderToSurface = isValidSize && (isFirstFrame || srcPtsUs >= nextEncodePtsUs)

                        decoder.releaseOutputBuffer(outIdx, shouldRenderToSurface)

                        if (shouldRenderToSurface) {
                            synchronized(frameSync) {
                                val deadline = System.currentTimeMillis() + 500L
                                while (!frameAvailable.get() && System.currentTimeMillis() < deadline) {
                                    frameSync.wait(25)
                                }
                                frameAvailable.set(false)
                            }
                            surfaceTexture.updateTexImage()
                            surfaceTexture.getTransformMatrix(stMatrix)

                            pipeline.processOesFrame(
                                oesTexId = oesTexArr[0],
                                stMatrix = stMatrix,
                                srcWidth = srcScaledW,
                                srcHeight = srcScaledH,
                                dstWidth = outW,
                                dstHeight = outH,
                                settings = project.restoration,
                                turboMode = turboMultiplier,
                            )

                            if (isFirstFrame) {
                                pipeline.renderToCurrentSurface(pipeline.enhancedTextureId, outW, outH)
                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, 0L)
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                                drainEncoder(encoder, encoderInfo, muxerCtx)
                                renderedFrames++
                                nextEncodePtsUs += frameIntervalUs
                            } else if (srcPtsUs >= nextEncodePtsUs) {
                                var step = 0
                                val maxSubFrames = if (turboMultiplier >= 3) 2 else 4
                                while (srcPtsUs >= nextEncodePtsUs && step < maxSubFrames) {
                                    if (useInterpolation && hasPrevFrame && srcPtsUs > nextEncodePtsUs) {
                                        pipeline.renderBlendedToCurrentSurface(
                                            texA = prevFrameTex,
                                            texB = pipeline.enhancedTextureId,
                                            blendFactor = 0.5f,
                                            surfaceWidth = outW,
                                            surfaceHeight = outH,
                                        )
                                    } else {
                                        pipeline.renderToCurrentSurface(pipeline.enhancedTextureId, outW, outH)
                                    }
                                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nextEncodePtsUs * 1000L)
                                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                                    drainEncoder(encoder, encoderInfo, muxerCtx)
                                    renderedFrames++
                                    nextEncodePtsUs += frameIntervalUs
                                    step++
                                }
                                if (srcPtsUs > nextEncodePtsUs) {
                                    nextEncodePtsUs = srcPtsUs + frameIntervalUs
                                }
                            }

                            if (useInterpolation) {
                                pipeline.copyEnhancedToTexture(prevFrameTex, prevFrameFbo)
                                hasPrevFrame = true
                            }

                            val progress = ((srcPtsUs.toFloat() / durationUs.coerceAtLeast(1L).toFloat()) * videoPhaseMaxProgress)
                                .coerceIn(0f, videoPhaseMaxProgress)
                            val activeElapsedMs = (System.currentTimeMillis() - startMs - totalPausedMs).coerceAtLeast(1L)
                            val currentFps = renderedFrames * 1000f / activeElapsedMs.toFloat()
                            val etaMs = if (progress > 0.02f) {
                                ((activeElapsedMs / progress) * (1f - progress)).toLong()
                            } else {
                                0L
                            }
                            onProgress(
                                VideoExportProgress(
                                    progress = progress,
                                    framesProcessed = renderedFrames,
                                    totalFrames = estimatedTotalFrames,
                                    currentFps = currentFps,
                                    elapsedMs = activeElapsedMs,
                                    estimatedRemainingMs = etaMs,
                                )
                            )
                        }

                        if (isEos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // 3. Always drain pending encoder output packets
                drainEncoder(encoder, encoderInfo, muxerCtx)
            }

            // 4. Safe AAC Audio Passthrough with Smooth 0.95 -> 1.00 Progress Updates
            if (muxerCtx.started && muxerCtx.audioTrackIndex >= 0 && audioTrackIndex >= 0) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val audioBuf = ByteBuffer.allocateDirect(256 * 1024)
                val audioInfo = MediaCodec.BufferInfo()
                var audioSampleCount = 0
                while (true) {
                    if (isCancelled()) break
                    val size = extractor.readSampleData(audioBuf, 0)
                    if (size < 0) break
                    audioInfo.offset = 0
                    audioInfo.size = size
                    audioInfo.presentationTimeUs = extractor.sampleTime
                    audioInfo.flags = extractor.sampleFlags
                    runCatching {
                        muxer.writeSampleData(muxerCtx.audioTrackIndex, audioBuf, audioInfo)
                    }
                    audioSampleCount++
                    if (audioSampleCount % 25 == 0) {
                        val audioFrac = (audioInfo.presentationTimeUs.toFloat() / durationUs.coerceAtLeast(1L).toFloat())
                            .coerceIn(0f, 1f)
                        val totalProgress = 0.95f + audioFrac * 0.05f
                        val activeElapsedMs = (System.currentTimeMillis() - startMs - totalPausedMs).coerceAtLeast(1L)
                        onProgress(
                            VideoExportProgress(
                                progress = totalProgress.coerceIn(0.95f, 0.999f),
                                framesProcessed = renderedFrames,
                                totalFrames = renderedFrames.coerceAtLeast(1),
                                currentFps = renderedFrames * 1000f / activeElapsedMs.toFloat(),
                                elapsedMs = activeElapsedMs,
                                estimatedRemainingMs = 0L,
                            )
                        )
                    }
                    extractor.advance()
                }
            }

            val finalElapsed = (System.currentTimeMillis() - startMs - totalPausedMs).coerceAtLeast(1L)
            onProgress(
                VideoExportProgress(
                    progress = 1.0f,
                    framesProcessed = renderedFrames,
                    totalFrames = renderedFrames.coerceAtLeast(1),
                    currentFps = renderedFrames * 1000f / finalElapsed.toFloat(),
                    elapsedMs = finalElapsed,
                    estimatedRemainingMs = 0L,
                )
            )
            outputFile
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { sourcePfd?.close() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { encoderInputSurface?.release() }

            if (prevFrameFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(prevFrameFbo), 0)
            if (prevFrameTex != 0) GLES30.glDeleteTextures(1, intArrayOf(prevFrameTex), 0)
            if (oesTexArr[0] != 0) GLES30.glDeleteTextures(1, oesTexArr, 0)
            pipeline.release()

            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        encoderInfo: MediaCodec.BufferInfo,
        muxerCtx: MuxerContext,
    ) {
        while (true) {
            val encIdx = encoder.dequeueOutputBuffer(encoderInfo, 0L)
            when {
                encIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerCtx.started) {
                        muxerCtx.videoTrackIndex = muxerCtx.muxer.addTrack(encoder.outputFormat)
                        val audioFmt = muxerCtx.audioInputFormat
                        if (audioFmt != null) {
                            muxerCtx.audioTrackIndex = runCatching {
                                muxerCtx.muxer.addTrack(audioFmt)
                            }.getOrDefault(-1)
                        }
                        muxerCtx.muxer.start()
                        muxerCtx.started = true
                    }
                }
                encIdx >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(encIdx)
                    if (encodedData != null && encoderInfo.size > 0 &&
                        (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        if (muxerCtx.started && muxerCtx.videoTrackIndex >= 0) {
                            encodedData.position(encoderInfo.offset)
                            encodedData.limit(encoderInfo.offset + encoderInfo.size)
                            muxerCtx.muxer.writeSampleData(muxerCtx.videoTrackIndex, encodedData, encoderInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(encIdx, false)
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        muxerCtx.encoderDone = true
                        break
                    }
                }
            }
        }
    }

    private fun clampToEncoderCapabilities(width: Int, height: Int): Pair<Int, Int> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
                ?: continue
            if (caps.isSizeSupported(width, height)) {
                return width to height
            }
            val isPortrait = height > width
            val maxLong = maxOf(caps.supportedWidths.upper, caps.supportedHeights.upper)
            val maxShort = minOf(caps.supportedWidths.upper, caps.supportedHeights.upper)
            val limitW = if (isPortrait) maxShort else maxLong
            val limitH = if (isPortrait) maxLong else maxShort
            val scale = minOf(limitW.toFloat() / width.toFloat(), limitH.toFloat() / height.toFloat(), 1f)
            val alignedW = (((width * scale).toInt() + 8) / 16 * 16).coerceAtLeast(128)
            val alignedH = (((height * scale).toInt() + 8) / 16 * 16).coerceAtLeast(128)
            if (caps.isSizeSupported(alignedW, alignedH) || caps.isSizeSupported(alignedH, alignedW)) {
                return alignedW to alignedH
            }
        }
        return ((width + 8) / 16 * 16) to ((height + 8) / 16 * 16)
    }
}
