package com.example.engine.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.Surface
import com.example.data.local.MediaStorageHelper
import com.example.domain.model.MediaType
import com.example.domain.model.RestorationSettings
import com.example.domain.model.scaleToMaxDimension
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class StudioViewportRenderer(
    private val context: Context,
    private val onRequestRender: () -> Unit,
    private val onVideoProgressUpdate: (positionMs: Long, durationMs: Long, isPlaying: Boolean) -> Unit,
) : GLSurfaceView.Renderer {

    private val pipeline = GlShaderPipeline()
    private val stMatrix = FloatArray(16)
    private val oesFrameAvailable = AtomicBoolean(false)

    @Volatile private var mediaType: MediaType = MediaType.PHOTO
    @Volatile private var sourcePath: String = ""
    @Volatile private var mediaWidth: Int = 1280
    @Volatile private var mediaHeight: Int = 720
    @Volatile private var targetOutWidth: Int = 1920
    @Volatile private var targetOutHeight: Int = 1080
    @Volatile private var settings: RestorationSettings = RestorationSettings.Default
    @Volatile private var splitPos: Float = 0.5f
    @Volatile private var zoom: Float = 1f
    @Volatile private var panX: Float = 0f
    @Volatile private var panY: Float = 0f
    @Volatile private var isMuted: Boolean = false

    @Volatile private var viewportWidth: Int = 1
    @Volatile private var viewportHeight: Int = 1
    @Volatile private var pendingPhotoReload: Boolean = false
    @Volatile private var pendingVideoReload: Boolean = false
    @Volatile private var needsShaderReprocess: Boolean = true

    private var photoTextureId: Int = 0
    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlayerPrepared: Boolean = false
    private var hasReceivedVideoFrame: Boolean = false

    fun loadMedia(
        type: MediaType,
        path: String,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
    ) {
        val pathChanged = sourcePath != path || mediaType != type
        mediaType = type
        sourcePath = path
        mediaWidth = srcW.coerceAtLeast(16)
        mediaHeight = srcH.coerceAtLeast(16)
        val (scaledOutW, scaledOutH) = scaleToMaxDimension(outW, outH, 2048)
        targetOutWidth = scaledOutW
        targetOutHeight = scaledOutH
        if (pathChanged) {
            if (type == MediaType.PHOTO) {
                pendingPhotoReload = true
            } else {
                pendingVideoReload = true
            }
        }
        needsShaderReprocess = true
        onRequestRender()
    }

    fun updateSettings(newSettings: RestorationSettings, outW: Int, outH: Int) {
        settings = newSettings
        val (scaledOutW, scaledOutH) = scaleToMaxDimension(outW, outH, 2048)
        targetOutWidth = scaledOutW
        targetOutHeight = scaledOutH
        needsShaderReprocess = true
        onRequestRender()
    }

    fun updateViewportControls(split: Float, zoomLevel: Float, panOffsetX: Float, panOffsetY: Float) {
        splitPos = split.coerceIn(0f, 1f)
        zoom = zoomLevel.coerceIn(1f, 4f)
        panX = panOffsetX
        panY = panOffsetY
        onRequestRender()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        val player = mediaPlayer ?: return
        if (isPlayerPrepared) {
            val vol = if (muted) 0f else 1f
            runCatching { player.setVolume(vol, vol) }
        }
    }

    fun pausePlayback() {
        val player = mediaPlayer ?: return
        if (isPlayerPrepared && player.isPlaying) {
            runCatching { player.pause() }
            onVideoProgressUpdate(player.currentPosition.toLong(), player.duration.toLong().coerceAtLeast(1L), false)
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (!isPlayerPrepared) return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.start()
        }
        onVideoProgressUpdate(player.currentPosition.toLong(), player.duration.toLong().coerceAtLeast(1L), player.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        if (!isPlayerPrepared) return
        player.seekTo(positionMs.toInt().coerceAtLeast(0))
        needsShaderReprocess = true
        onRequestRender()
    }

    fun pollVideoProgress() {
        val player = mediaPlayer ?: return
        if (isPlayerPrepared) {
            onVideoProgressUpdate(
                player.currentPosition.toLong().coerceAtLeast(0L),
                player.duration.toLong().coerceAtLeast(1L),
                player.isPlaying,
            )
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        pipeline.init()
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        photoTextureId = tex[0]
        oesTextureId = tex[1]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (sourcePath.isNotEmpty()) {
            if (mediaType == MediaType.PHOTO) pendingPhotoReload = true else pendingVideoReload = true
            needsShaderReprocess = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        needsShaderReprocess = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (sourcePath.isBlank()) {
            GLES30.glClearColor(0.031f, 0.031f, 0.039f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        if (mediaType == MediaType.PHOTO) {
            if (pendingPhotoReload) {
                pendingPhotoReload = false
                releaseMediaPlayer()
                uploadPhotoBitmap(sourcePath)
                needsShaderReprocess = true
            }
            if (needsShaderReprocess && photoTextureId != 0) {
                needsShaderReprocess = false
                val (previewOutW, previewOutH) = scaleToMaxDimension(targetOutWidth, targetOutHeight, 2048)
                pipeline.process2DTexture(
                    inputTexId = photoTextureId,
                    srcWidth = mediaWidth,
                    srcHeight = mediaHeight,
                    dstWidth = previewOutW,
                    dstHeight = previewOutH,
                    settings = settings,
                    flipInputY = true,
                    isPhoto = true,
                    turboMode = 1,
                )
            }
        } else {
            if (pendingVideoReload) {
                pendingVideoReload = false
                setupVideoPlayer(sourcePath)
            }
            if (oesFrameAvailable.compareAndSet(true, false)) {
                runCatching {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                    hasReceivedVideoFrame = true
                    needsShaderReprocess = true
                }
            }
            if (hasReceivedVideoFrame && needsShaderReprocess) {
                needsShaderReprocess = false
                val (previewSrcW, previewSrcH) = scaleToMaxDimension(mediaWidth, mediaHeight, 1280)
                val (previewOutW, previewOutH) = scaleToMaxDimension(targetOutWidth, targetOutHeight, 1920)
                pipeline.processOesFrame(
                    oesTexId = oesTextureId,
                    stMatrix = stMatrix,
                    srcWidth = previewSrcW,
                    srcHeight = previewSrcH,
                    dstWidth = previewOutW,
                    dstHeight = previewOutH,
                    settings = settings,
                    turboMode = 1,
                )
            }
        }

        if (pipeline.enhancedTextureId != 0) {
            pipeline.renderSplitViewport(
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                splitPos = splitPos,
                zoom = zoom,
                panX = panX,
                panY = panY,
            )
        } else {
            GLES30.glClearColor(0.031f, 0.031f, 0.039f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
    }

    private fun uploadPhotoBitmap(path: String) {
        val bmp: Bitmap = MediaStorageHelper.loadExifOrientedBitmap(path, maxDimension = 2048) ?: return
        mediaWidth = bmp.width
        mediaHeight = bmp.height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTextureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bmp.recycle()
    }

    private fun setupVideoPlayer(path: String) {
        releaseMediaPlayer()
        hasReceivedVideoFrame = false
        val st = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener {
                oesFrameAvailable.set(true)
                onRequestRender()
            }
        }
        surfaceTexture = st
        val surf = Surface(st)
        videoSurface = surf

        val mp = MediaPlayer()
        mediaPlayer = mp
        runCatching {
            if (MediaStorageHelper.isContentUri(path)) {
                val uri = Uri.parse(path)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    pfd.use { mp.setDataSource(it.fileDescriptor) }
                } else {
                    mp.setDataSource(context, uri)
                }
            } else {
                mp.setDataSource(path)
            }
            mp.setSurface(surf)
            mp.isLooping = true
            val vol = if (isMuted) 0f else 1f
            mp.setVolume(vol, vol)
            mp.setOnPreparedListener { player ->
                isPlayerPrepared = true
                val preparedVol = if (isMuted) 0f else 1f
                player.setVolume(preparedVol, preparedVol)
                player.seekTo(0)
                player.start()
                onVideoProgressUpdate(0L, player.duration.toLong().coerceAtLeast(1L), true)
            }
            mp.prepareAsync()
        }
    }

    fun release() {
        releaseMediaPlayer()
        val tex = intArrayOf(photoTextureId, oesTextureId).filter { it != 0 }.toIntArray()
        if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
        photoTextureId = 0
        oesTextureId = 0
        pipeline.release()
    }

    private fun releaseMediaPlayer() {
        isPlayerPrepared = false
        hasReceivedVideoFrame = false
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { videoSurface?.release() }
        videoSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
    }
}
