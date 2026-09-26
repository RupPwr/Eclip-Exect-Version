package com.eclip.rup.leb

import com.eclip.rup.leb.utils.MediaValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaValidatorTest {

    @Test
    fun testSupportedResolutions() {
        // Landscape 1080p
        assertFalse(MediaValidator.isResolutionAbove1080p(1920, 1080))
        // Portrait 1080p
        assertFalse(MediaValidator.isResolutionAbove1080p(1080, 1920))
        // 720p
        assertFalse(MediaValidator.isResolutionAbove1080p(1280, 720))
        assertFalse(MediaValidator.isResolutionAbove1080p(720, 1280))
        // 480p
        assertFalse(MediaValidator.isResolutionAbove1080p(854, 480))
        // 360p
        assertFalse(MediaValidator.isResolutionAbove1080p(640, 360))
        // Square 1080x1080
        assertFalse(MediaValidator.isResolutionAbove1080p(1080, 1080))
    }

    @Test
    fun testUnsupportedResolutionsAbove1080p() {
        // 1440p (2K)
        assertTrue(MediaValidator.isResolutionAbove1080p(2560, 1440))
        assertTrue(MediaValidator.isResolutionAbove1080p(1440, 2560))
        // 4K UHD
        assertTrue(MediaValidator.isResolutionAbove1080p(3840, 2160))
        assertTrue(MediaValidator.isResolutionAbove1080p(2160, 3840))
        // 4K DCI
        assertTrue(MediaValidator.isResolutionAbove1080p(4096, 2160))
        // 5K
        assertTrue(MediaValidator.isResolutionAbove1080p(5120, 2880))
        // 8K
        assertTrue(MediaValidator.isResolutionAbove1080p(7680, 4320))
        // Borderline exceeding: 1921x1080
        assertTrue(MediaValidator.isResolutionAbove1080p(1921, 1080))
        // Borderline exceeding: 1920x1081
        assertTrue(MediaValidator.isResolutionAbove1080p(1920, 1081))
    }
}
