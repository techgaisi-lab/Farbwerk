package at.techgaisi.farbwerk.io

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasQualityTest {
    @Test fun standardKeepsRequestedPixelsOnLargeTablets() {
        val old = CanvasQuality.cachedScreenLong
        try {
            CanvasQuality.cachedScreenLong = 3200
            for ((w, h) in listOf(512 to 512, 1920 to 1080, 3000 to 2000)) {
                assertEquals(1, CanvasQuality.detailFor(w, h))
                assertEquals(1, CanvasQuality.detailFor(w, h, highQuality = false))
            }
        } finally { CanvasQuality.cachedScreenLong = old }
    }

    @Test fun explicitHighQualityStillRespectsPixelAndSideLimits() {
        val old = CanvasQuality.cachedScreenLong
        try {
            CanvasQuality.cachedScreenLong = 3200
            assertEquals(4, CanvasQuality.detailFor(512, 512, true))
            assertEquals(3, CanvasQuality.detailFor(1920, 1080, true))
            assertEquals(2, CanvasQuality.detailFor(2048, 2048, true))
            assertEquals(1, CanvasQuality.detailFor(4096, 4096, true))
            assertEquals(1, CanvasQuality.detailFor(8192, 1000, true))
            assertEquals(1, CanvasQuality.detailFor(0, 1000, true))
        } finally { CanvasQuality.cachedScreenLong = old }
    }
}
