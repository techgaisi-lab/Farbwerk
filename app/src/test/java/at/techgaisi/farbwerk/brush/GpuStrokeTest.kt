package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import at.techgaisi.farbwerk.render.EngineMemory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class GpuStrokeTest {
    @Test fun cpuCommitReplayPreservesTexturedDabOrderAndPixels() {
        val brush = Brush("test", "test", "test", builtIn = false).apply {
            this[BP.SHAPE_KIND] = ShapeKind.SPONGE.ordinal.toFloat()
            this[BP.GRAIN_KIND] = GrainKind.WATERCOLOR.ordinal.toFloat()
            this[BP.GRAIN_DEPTH] = 0.4f
        }
        val direct = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val committed = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val painter = DabPainter().apply { setup(brush, 2f) }
        val gpu = GpuStroke(600, 400)
        for (i in 0 until 80) {
            val x = 100f + i * 4.3f; val y = 150f + (i % 11) * 4.7f
            val angle = i * 0.17f
            assertTrue(gpu.append(x, y, 63f, 0.8f, angle, 0.65f, Color.RED, 0))
            painter.draw(Canvas(direct), x, y, 63f, 0.8f, angle, 0.65f, Color.RED, 0)
        }
        painter.flush()
        gpu.replayInto(Canvas(committed), painter)
        fun bytes(b: Bitmap) = ByteArray(b.rowBytes * b.height).also { b.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        assertArrayEquals(bytes(direct), bytes(committed))
        gpu.release(); painter.release()
    }

    @Test fun gpuPreviewRefusesAdditionalTilesBeforeBudgetIsExceeded() {
        val gpu = GpuStroke(8192, 8192)
        var refused = false
        loop@ for (y in 128 until 8192 step 256) for (x in 128 until 8192 step 256) {
            if (!gpu.append(x.toFloat(), y.toFloat(), 30f, 1f, 0f, 1f, Color.BLUE, 0)) { refused = true; break@loop }
        }
        assertTrue(refused)
        assertTrue(gpu.bytes <= EngineMemory.gpuStrokeBudget)
        gpu.release(); assertEquals(0L, gpu.bytes)
    }
}
