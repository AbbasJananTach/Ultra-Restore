package com.example.engine.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.example.domain.model.RestorationSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GlShaderPipeline {

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )

    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(quadVertices)
        .apply { position(0) }

    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val flipYMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        Matrix.translateM(this, 0, 0f, 1f, 0f)
        Matrix.scaleM(this, 0, 1f, -1f, 1f)
    }

    private var oesProgram = 0
    private var pass1Program = 0
    private var upscaleProgram = 0
    private var pass2Program = 0
    private var pass3Program = 0
    private var turboFusedProgram = 0
    private var splitProgram = 0
    private var passthroughProgram = 0
    private var blendProgram = 0

    private var fbo0 = 0
    private var tex0 = 0
    private var fbo1 = 0
    private var tex1 = 0
    private var fboUpscale = 0
    private var texUpscale = 0
    private var fbo2 = 0
    private var tex2 = 0
    private var fbo3 = 0
    private var tex3 = 0

    private var fboSrcW = 0
    private var fboSrcH = 0
    private var fboDstW = 0
    private var fboDstH = 0

    private var initialized = false

    val originalTextureId: Int get() = tex0
    val enhancedTextureId: Int get() = tex3

    fun init() {
        if (initialized) return
        oesProgram = buildProgram(ShaderSources.VERTEX_QUAD_ES100, ShaderSources.PASS0_OES_TO_RGBA_ES100)
        pass1Program = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.PASS1_BILATERAL_DENOISE_FRAG)
        upscaleProgram = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.PASS1_5_BICUBIC_UPSCALE_FRAG)
        pass2Program = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.PASS2_EDGE_DEBLUR_CAS_FRAG)
        pass3Program = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.PASS3_CLARITY_TONE_FRAG)
        turboFusedProgram = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.PASS_TURBO_FUSED_FRAG)
        splitProgram = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.SPLIT_VIEWPORT_FRAG)
        passthroughProgram = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.PASSTHROUGH_FRAG)
        blendProgram = buildProgram(ShaderSources.VERTEX_QUAD_ES300, ShaderSources.FRAME_BLEND_FRAG)
        initialized = true
    }

    fun ensureFbos(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int) {
        val safeSrcW = srcWidth.coerceAtLeast(16)
        val safeSrcH = srcHeight.coerceAtLeast(16)
        val safeDstW = dstWidth.coerceAtLeast(16)
        val safeDstH = dstHeight.coerceAtLeast(16)

        if (fboSrcW == safeSrcW && fboSrcH == safeSrcH && fboDstW == safeDstW && fboDstH == safeDstH && fbo0 != 0) {
            return
        }

        releaseFbos()
        fboSrcW = safeSrcW
        fboSrcH = safeSrcH
        fboDstW = safeDstW
        fboDstH = safeDstH

        val pair0 = createFboWithTexture(safeSrcW, safeSrcH)
        fbo0 = pair0.first; tex0 = pair0.second

        val pair1 = createFboWithTexture(safeSrcW, safeSrcH)
        fbo1 = pair1.first; tex1 = pair1.second

        val pairUp = createFboWithTexture(safeDstW, safeDstH)
        fboUpscale = pairUp.first; texUpscale = pairUp.second

        val pair2 = createFboWithTexture(safeDstW, safeDstH)
        fbo2 = pair2.first; tex2 = pair2.second

        val pair3 = createFboWithTexture(safeDstW, safeDstH)
        fbo3 = pair3.first; tex3 = pair3.second
    }

    fun processOesFrame(
        oesTexId: Int,
        stMatrix: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        settings: RestorationSettings,
        turboMode: Int = 1,
    ) {
        ensureFbos(srcWidth, srcHeight, dstWidth, dstHeight)

        // Pass 0: OES external texture -> fbo0 (RGBA8)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo0)
        GLES30.glViewport(0, 0, fboSrcW, fboSrcH)
        GLES30.glUseProgram(oesProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram, "uOesTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(oesProgram, "uTexMatrix"), 1, false, stMatrix, 0)
        drawQuad(oesProgram)

        runRestorationPasses(tex0, settings, isPhoto = false, turboMode = turboMode)
    }

    fun process2DTexture(
        inputTexId: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        settings: RestorationSettings,
        flipInputY: Boolean = true,
        isPhoto: Boolean = true,
        turboMode: Int = 1,
    ) {
        ensureFbos(srcWidth, srcHeight, dstWidth, dstHeight)

        // Pass 0: Upload normalized RGBA into fbo0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo0)
        GLES30.glViewport(0, 0, fboSrcW, fboSrcH)
        GLES30.glUseProgram(passthroughProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(passthroughProgram, "uInputTex"), 0)
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(passthroughProgram, "uTexMatrix"),
            1,
            false,
            if (flipInputY) flipYMatrix else identityMatrix,
            0,
        )
        drawQuad(passthroughProgram)

        runRestorationPasses(tex0, settings, isPhoto = isPhoto, turboMode = turboMode)
    }

    private fun runRestorationPasses(
        sourceTexId: Int,
        settings: RestorationSettings,
        isPhoto: Boolean,
        turboMode: Int,
    ) {
        // High-Speed Fused Single-Pass Path for 3x / 4x Turbo Video Export
        if (!isPhoto && turboMode >= 3) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo3)
            GLES30.glViewport(0, 0, fboDstW, fboDstH)
            GLES30.glUseProgram(turboFusedProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(turboFusedProgram, "uInputTex"), 0)
            GLES30.glUniform2f(
                GLES30.glGetUniformLocation(turboFusedProgram, "uSrcTexelSize"),
                1f / fboSrcW.toFloat(),
                1f / fboSrcH.toFloat(),
            )
            GLES30.glUniform2f(
                GLES30.glGetUniformLocation(turboFusedProgram, "uDstTexelSize"),
                1f / fboDstW.toFloat(),
                1f / fboDstH.toFloat(),
            )
            GLES30.glUniform1f(GLES30.glGetUniformLocation(turboFusedProgram, "uDenoise"), (settings.denoise / 100f).coerceIn(0f, 1f))
            GLES30.glUniform1f(GLES30.glGetUniformLocation(turboFusedProgram, "uDeblur"), (settings.deblur / 100f).coerceIn(0f, 1f))
            GLES30.glUniform1f(GLES30.glGetUniformLocation(turboFusedProgram, "uSharpness"), (settings.sharpness / 100f).coerceIn(0f, 1f))
            GLES30.glUniform1f(GLES30.glGetUniformLocation(turboFusedProgram, "uClarity"), (settings.clarity / 100f).coerceIn(0f, 1f))
            GLES30.glUniform1f(GLES30.glGetUniformLocation(turboFusedProgram, "uShadowRecovery"), (settings.shadowRecovery / 100f).coerceIn(-1f, 1f))
            GLES30.glUniform1f(GLES30.glGetUniformLocation(turboFusedProgram, "uVibrance"), (settings.vibrance / 100f).coerceIn(-1f, 1f))
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(turboFusedProgram, "uTexMatrix"), 1, false, identityMatrix, 0)
            drawQuad(turboFusedProgram)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }

        // Precision Multi-Pass Pipeline (Pass 1 -> Pass 1.5 Upscale -> Pass 2 High-Res Deblur+CAS -> Pass 3 Tone)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo1)
        GLES30.glViewport(0, 0, fboSrcW, fboSrcH)
        GLES30.glUseProgram(pass1Program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pass1Program, "uInputTex"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(pass1Program, "uTexelSize"),
            1f / fboSrcW.toFloat(),
            1f / fboSrcH.toFloat(),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass1Program, "uDenoise"), (settings.denoise / 100f).coerceIn(0f, 1f))
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pass1Program, "uTurboMode"), turboMode.coerceIn(1, 4))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass1Program, "uIsPhoto"), if (isPhoto) 1f else 0f)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pass1Program, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(pass1Program)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboUpscale)
        GLES30.glViewport(0, 0, fboDstW, fboDstH)
        GLES30.glUseProgram(upscaleProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(upscaleProgram, "uInputTex"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(upscaleProgram, "uSrcTexelSize"),
            1f / fboSrcW.toFloat(),
            1f / fboSrcH.toFloat(),
        )
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(upscaleProgram, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(upscaleProgram)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo2)
        GLES30.glViewport(0, 0, fboDstW, fboDstH)
        GLES30.glUseProgram(pass2Program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texUpscale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pass2Program, "uInputTex"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(pass2Program, "uDstTexelSize"),
            1f / fboDstW.toFloat(),
            1f / fboDstH.toFloat(),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass2Program, "uDeblur"), (settings.deblur / 100f).coerceIn(0f, 1f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass2Program, "uSharpness"), (settings.sharpness / 100f).coerceIn(0f, 1f))
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pass2Program, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(pass2Program)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo3)
        GLES30.glViewport(0, 0, fboDstW, fboDstH)
        GLES30.glUseProgram(pass3Program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pass3Program, "uInputTex"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(pass3Program, "uTexelSize"),
            1f / fboDstW.toFloat(),
            1f / fboDstH.toFloat(),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass3Program, "uClarity"), (settings.clarity / 100f).coerceIn(0f, 1f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass3Program, "uShadowRecovery"), (settings.shadowRecovery / 100f).coerceIn(-1f, 1f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pass3Program, "uVibrance"), (settings.vibrance / 100f).coerceIn(-1f, 1f))
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pass3Program, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(pass3Program)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun renderSplitViewport(
        viewportWidth: Int,
        viewportHeight: Int,
        mediaWidth: Int,
        mediaHeight: Int,
        splitPos: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0.031f, 0.031f, 0.039f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val viewAspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1).toFloat()
        val mediaAspect = mediaWidth.toFloat() / mediaHeight.coerceAtLeast(1).toFloat()
        val scaleX = if (viewAspect > mediaAspect) viewAspect / mediaAspect else 1f
        val scaleY = if (viewAspect < mediaAspect) mediaAspect / viewAspect else 1f

        GLES30.glUseProgram(splitProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(splitProgram, "uOriginalTex"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex3)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(splitProgram, "uEnhancedTex"), 1)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(splitProgram, "uSplitPos"), splitPos.coerceIn(0f, 1f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(splitProgram, "uZoom"), zoom.coerceIn(1f, 4f))
        GLES30.glUniform2f(GLES30.glGetUniformLocation(splitProgram, "uPan"), panX, panY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(splitProgram, "uViewportAspectScale"), scaleX, scaleY)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(splitProgram, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(splitProgram)
    }

    fun renderToCurrentSurface(
        textureId: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        flipY: Boolean = false,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glUseProgram(passthroughProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(passthroughProgram, "uInputTex"), 0)
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(passthroughProgram, "uTexMatrix"),
            1,
            false,
            if (flipY) flipYMatrix else identityMatrix,
            0,
        )
        drawQuad(passthroughProgram)
    }

    fun renderBlendedToCurrentSurface(
        texA: Int,
        texB: Int,
        blendFactor: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glUseProgram(blendProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texA)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blendProgram, "uFrameA"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blendProgram, "uFrameB"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(blendProgram, "uBlendFactor"), blendFactor.coerceIn(0f, 1f))
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(blendProgram, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(blendProgram)
    }

    fun copyEnhancedToTexture(targetTexId: Int, targetFboId: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFboId)
        GLES30.glViewport(0, 0, fboDstW, fboDstH)
        GLES30.glUseProgram(passthroughProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex3)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(passthroughProgram, "uInputTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(passthroughProgram, "uTexMatrix"), 1, false, identityMatrix, 0)
        drawQuad(passthroughProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun drawQuad(program: Int) {
        val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES30.glGetAttribLocation(program, "aTexCoord")

        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(texLoc)
    }

    fun release() {
        releaseFbos()
        listOf(
            oesProgram,
            pass1Program,
            upscaleProgram,
            pass2Program,
            pass3Program,
            turboFusedProgram,
            splitProgram,
            passthroughProgram,
            blendProgram,
        )
            .filter { it != 0 }
            .forEach { GLES30.glDeleteProgram(it) }
        oesProgram = 0; pass1Program = 0; upscaleProgram = 0; pass2Program = 0; pass3Program = 0
        turboFusedProgram = 0; splitProgram = 0; passthroughProgram = 0; blendProgram = 0
        initialized = false
    }

    private fun releaseFbos() {
        val fbos = intArrayOf(fbo0, fbo1, fboUpscale, fbo2, fbo3).filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) GLES30.glDeleteFramebuffers(fbos.size, fbos, 0)
        val texs = intArrayOf(tex0, tex1, texUpscale, tex2, tex3).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) GLES30.glDeleteTextures(texs.size, texs, 0)
        fbo0 = 0; tex0 = 0; fbo1 = 0; tex1 = 0; fboUpscale = 0; texUpscale = 0
        fbo2 = 0; tex2 = 0; fbo3 = 0; tex3 = 0
        fboSrcW = 0; fboSrcH = 0; fboDstW = 0; fboDstH = 0
    }

    companion object {
        fun createFboWithTexture(width: Int, height: Int): Pair<Int, Int> {
            val fboArr = IntArray(1)
            val texArr = IntArray(1)
            GLES30.glGenFramebuffers(1, fboArr, 0)
            GLES30.glGenTextures(1, texArr, 0)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texArr[0])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboArr[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                texArr[0],
                0,
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            return fboArr[0] to texArr[0]
        }

        fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
            val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc.trimIndent())
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc.trimIndent())
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw RuntimeException("GLSL Link Error: $log")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return program
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw RuntimeException("GLSL Compile Error: $log")
            }
            return shader
        }
    }
}
