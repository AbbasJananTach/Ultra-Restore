package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AiModelMode
import com.example.domain.model.MediaType
import com.example.domain.model.RestorationSettings
import com.example.engine.ai.AiEnhancerEngine
import com.example.engine.ai.MultiStageAiPipeline
import com.example.engine.ai.StageModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("UltraRestore", appName)
    }

    @Test
    fun `verify tflite model assets and 5-stage sequential AI pipeline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify all 5 stage .tflite models + 3 mode .tflite models exist in assets and have TFL3 FlatBuffer identifier
        for (stage in StageModelId.entries) {
            val bytes = context.assets.open(stage.assetFileName).use { it.readBytes() }
            assertTrue("Stage model ${stage.assetFileName} should not be empty", bytes.size > 100)
            val identifier = String(bytes, 4, 4, Charsets.US_ASCII)
            assertEquals("TFL3", identifier)
        }
        for (mode in AiModelMode.entries) {
            val bytes = context.assets.open(mode.assetFileName).use { it.readBytes() }
            assertTrue("Model ${mode.assetFileName} should not be empty", bytes.size > 100)
            val identifier = String(bytes, 4, 4, Charsets.US_ASCII)
            assertEquals("TFL3", identifier)
        }

        // 2. Verify Auto Enhance pixel analysis & 5-stage sequential photo processing
        val srcBitmap = Bitmap.createBitmap(96, 80, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.rgb(90, 110, 140))

        MultiStageAiPipeline(context).use { pipeline ->
            val analysis = pipeline.analyzeBitmapForAutoEnhance(
                bitmap = srcBitmap,
                originalWidth = 96,
                originalHeight = 80,
                mediaType = MediaType.PHOTO,
                currentAiMode = AiModelMode.ESRGAN_4X,
            )
            assertTrue(analysis.recommendedSettings.deblock in 0f..100f)
            assertTrue(analysis.recommendedSettings.denoise in 0f..100f)
            assertTrue(analysis.recommendedSettings.deblur in 0f..100f)
            assertTrue(analysis.recommendedSettings.sharpness in 0f..100f)
            assertTrue(analysis.recommendedSettings.clarity in 0f..100f)

            val stagesObserved = mutableSetOf<Int>()
            val outBitmap = pipeline.processPhotoFiveStages(
                srcBitmap = srcBitmap,
                targetWidth = 192,
                targetHeight = 160,
                settings = RestorationSettings(
                    deblock = 55f,
                    deblur = 60f,
                    sharpness = 65f,
                    denoise = 45f,
                    clarity = 40f,
                    shadowRecovery = 15f,
                    vibrance = 20f,
                    aiModelMode = AiModelMode.ESRGAN_4X,
                ),
                onStageProgress = { sp ->
                    stagesObserved.add(sp.currentStage)
                },
            )
            assertEquals(192, outBitmap.width)
            assertEquals(160, outBitmap.height)
            assertEquals(setOf(1, 2, 3, 4, 5), stagesObserved)
            outBitmap.recycle()

            // 3. Verify AiEnhancerEngine compatibility
            AiEnhancerEngine(context).use { engine ->
                val out = engine.enhancePhotoTiled(
                    srcBitmap = srcBitmap,
                    targetWidth = 192,
                    targetHeight = 160,
                    settings = analysis.recommendedSettings,
                )
                assertEquals(192, out.width)
                assertEquals(160, out.height)
                out.recycle()
            }
        }
        srcBitmap.recycle()
    }
}
