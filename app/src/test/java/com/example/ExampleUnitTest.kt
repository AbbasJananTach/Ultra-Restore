package com.example

import com.example.data.local.ProjectEntity
import com.example.data.repository.ProjectRepository
import com.example.domain.model.ExportSettings
import com.example.domain.model.MediaType
import com.example.domain.model.ProjectStatus
import com.example.domain.model.RestorationSettings
import com.example.domain.model.SmartPresets
import com.example.domain.model.StudioProject
import com.example.domain.model.TargetFps
import com.example.domain.model.TargetResolution
import com.example.domain.model.TurboSpeed
import com.example.domain.model.scaleToMaxDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun selectSmartDefaultFps_matchesSourceVideoFrameRate() {
        assertEquals(TargetFps.FPS_24, ProjectRepository.selectSmartDefaultFps(23.976f))
        assertEquals(TargetFps.FPS_30, ProjectRepository.selectSmartDefaultFps(29.97f))
        assertEquals(TargetFps.FPS_50, ProjectRepository.selectSmartDefaultFps(50f))
        assertEquals(TargetFps.FPS_60, ProjectRepository.selectSmartDefaultFps(59.94f))
    }

    @Test
    fun scaleToMaxDimension_preservesExactAspectRatioAndAlignsTo4() {
        val (pW, pH) = scaleToMaxDimension(1080, 1920, 1280)
        assertEquals(720, pW)
        assertEquals(1280, pH)
        assertEquals(0, pW % 4)
        assertEquals(0, pH % 4)

        val (lW, lH) = scaleToMaxDimension(3840, 2160, 1920)
        assertEquals(1920, lW)
        assertEquals(1080, lH)
    }

    @Test
    fun targetResolution_preservesPortraitAndLandscapeAspectRatios() {
        // Landscape 16:9 -> 1920x1080
        val (landW, landH) = TargetResolution.FHD_1080P.computeOutputSize(854, 480)
        assertEquals(1920, landW)
        assertEquals(1080, landH)

        // Portrait 9:16 -> 1080x1920
        val (portW, portH) = TargetResolution.FHD_1080P.computeOutputSize(480, 854)
        assertEquals(1080, portW)
        assertEquals(1920, portH)

        // 4x Super Scale for Photo
        val (scaleW, scaleH) = TargetResolution.SCALE_4X.computeOutputSize(640, 480)
        assertEquals(2560, scaleW)
        assertEquals(1920, scaleH)
    }

    @Test
    fun projectEntity_roundTripsDomainModelAccurately() {
        val original = StudioProject(
            id = "proj-1",
            name = "Vintage Family Portrait",
            mediaType = MediaType.PHOTO,
            sourcePath = "/data/user/0/proj-1/source.jpg",
            thumbnailPath = "/data/user/0/proj-1/thumb.jpg",
            exportedPath = null,
            originalWidth = 600,
            originalHeight = 800,
            originalFps = 0f,
            durationMs = 0L,
            restoration = RestorationSettings(
                deblur = 72f,
                sharpness = 64f,
                denoise = 55f,
                clarity = 60f,
                shadowRecovery = 30f,
                vibrance = 40f,
            ),
            exportSettings = ExportSettings(
                resolution = TargetResolution.SCALE_4X,
                fps = TargetFps.FPS_60,
                motionInterpolation = false,
                bitrateMbps = 24f,
                turboSpeed = TurboSpeed.X4,
                photoFormatPng = true,
            ),
            status = ProjectStatus.DRAFT,
            lastProgress = 0f,
            updatedAt = 1700000000000L,
            createdAt = 1699999000000L,
        )

        val entity = ProjectEntity.fromDomain(original)
        val restored = entity.toDomain()
        assertEquals(original, restored)
    }

    @Test
    fun smartPresets_containsAllFiveEditablePresets() {
        assertEquals(5, SmartPresets.All.size)
        assertTrue(SmartPresets.All.any { it.name == "480p to 1080p Crisp Fix" })
        assertTrue(SmartPresets.All.any { it.name == "Old Vintage Photo Revival" })
    }
}
