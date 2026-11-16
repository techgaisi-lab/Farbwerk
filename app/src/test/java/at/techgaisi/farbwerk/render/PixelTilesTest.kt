package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import at.techgaisi.farbwerk.io.LayerCodec
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import at.techgaisi.farbwerk.undo.LayerTilesAction
import org.junit.Assert.*
import org.junit.Before
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
class PixelTilesTest {
    @Before fun resetCaches() { EngineMemory.clearCaches() }

    private fun raw(b: Bitmap): ByteArray = ByteArray(b.rowBytes * b.height).also { b.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
    private fun reference(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val colors = IntArray(w * h) { i -> Color.argb((i * 17) and 255, (i * 19) and 255, (i * 23) and 255, (i * 29) and 255) }
        b.setPixels(colors, 0, w, 0, 0, w, h)
    }

    @Test fun fiftyHighResolutionEmptyLayersAllocateNoPixelBitmaps() {
        val layers = List(50) { Layer.empty("Layer $it", 6320, 3950) }
        assertEquals(0L, layers.sumOf { it.pixelBytes })
        assertEquals(6320, layers.last().width)
        assertEquals(0, layers.last().samplePixel(6000, 3900))
        assertEquals(0L, layers.sumOf { it.pixelBytes })
    }

    @Test fun sparseWritesCrossTileEdgesAndSnapshotsStayImmutable() {
        val pixels = PixelTiles(600, 400)
        val r = Rect(250, 250, 270, 270)
        pixels.edit(r) { it.drawColor(Color.RED) }
        assertEquals(4, pixels.allocatedTiles)
        val snapshot = pixels.snapshot()
        pixels.edit(r) { it.drawColor(Color.BLUE) }
        assertEquals(Color.RED, snapshot.sample(260, 260))
        assertEquals(Color.BLUE, pixels.sample(260, 260))
        assertEquals(0, pixels.sample(249, 260))
        assertFalse(pixels.hasDenseBitmap)
        pixels.edit(r) { it.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.SRC) }
        assertEquals(0, pixels.allocatedTiles)
    }

    @Test fun nativePremultipliedPixelsSurviveCompressionAndLegacyEdits() {
        val original = reference(517, 299)
        val pixels = PixelTiles.from(original)
        assertArrayEquals(raw(original), raw(pixels.region(Rect(0, 0, 517, 299))))
        val dense = pixels.bitmap()
        dense.setPixel(300, 280, Color.GREEN)
        pixels.compact()
        assertEquals(Color.GREEN, pixels.sample(300, 280))
        assertFalse(pixels.hasDenseBitmap)
    }

    @Test fun codecReadsSparseSnapshotsWithoutMaterializingLayers() {
        val p = PixelTiles.from(reference(517, 299))
        val expected = raw(p.region(Rect(0, 0, 517, 299)))
        val encoded = LayerCodec.encode(p.snapshot(), null)
        assertArrayEquals(expected, raw(LayerCodec.decode(encoded)))
        val loaded = LayerCodec.decodeTiles(encoded)
        assertFalse(loaded.hasDenseBitmap)
        assertArrayEquals(expected, raw(loaded.region(Rect(0, 0, 517, 299))))
        assertFalse(p.hasDenseBitmap)
        val empty = PixelTiles(1024, 768)
        val encodedEmpty = LayerCodec.encode(empty.snapshot(), null)
        assertTrue(encodedEmpty.size < 1024)
        assertEquals(0, LayerCodec.decode(encodedEmpty).getPixel(100, 100))
    }

    @Test fun lazyRedoPreservesTransparentOriginalPixels() {
        val layer = Layer.empty("test", 600, 400)
        val rect = Rect(250, 250, 270, 270)
        val before = layer.pixelRegion(rect)
        layer.editPixels(rect) { it.drawColor(Color.RED) }
        val action = LayerTilesAction("stroke", layer, listOf(rect), listOf(PixelTiles.from(before)))
        action.undo(); assertEquals(0, layer.samplePixel(260, 260))
        action.redo(); assertEquals(Color.RED, layer.samplePixel(260, 260))
        assertEquals(0, layer.samplePixel(200, 200))
    }

    @Test fun tiledCompositionMatchesReferenceAndInvalidatesChangedPixels() {
        val doc = ArtDocument.create("test", 517, 299)
        val base = reference(doc.width, doc.height)
        doc.layers[0].bitmap = base
        doc.layers += Layer.empty("paint", doc.width, doc.height).also { l ->
            l.editPixels(Rect(230, 220, 290, 280)) { it.drawColor(Color.RED) }
            l.opacity = 0.45f; l.blend = LayerBlend.MULTIPLY
        }
        doc.layers.forEach { it.compactPixels() }
        fun direct(): Bitmap = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888).also { Compositor.draw(Canvas(it), doc) }
        fun tiled(): Bitmap = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888).also { assertTrue(TiledCompositor.draw(Canvas(it), doc)) }
        assertArrayEquals(raw(direct()), raw(tiled()))
        doc.layers[0].editPixels(Rect(10, 10, 20, 20)) { it.drawColor(Color.GREEN) }
        assertArrayEquals(raw(direct()), raw(tiled()))
    }

    @Test fun clippingToSparseBaseClearsMissingTiles() {
        val doc = ArtDocument.create("clip", 600, 300)
        doc.backgroundVisible = false
        doc.layers[0].editPixels(Rect(10, 10, 20, 20)) { it.drawColor(Color.RED) }
        doc.layers += Layer("clip", Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }).apply { clipping = true }
        doc.layers.forEach { it.compactPixels() }
        val out = Compositor.flatten(doc, false)
        assertEquals(Color.BLUE, out.getPixel(15, 15))
        assertEquals(0, out.getPixel(400, 150))
    }

    @Test fun decodedCacheStaysWithinBudget() {
        val old = PixelTileCache.maxBytes
        try {
            PixelTileCache.maxBytes = 1024 * 1024
            val p = PixelTiles.from(reference(1024, 1024))
            p.region(Rect(0, 0, 1024, 1024))
            assertTrue(PixelTileCache.residentBytes <= PixelTileCache.maxBytes)
        } finally { PixelTileCache.maxBytes = old }
    }

    @Test fun displayPyramidDoesNotReduceStoredDocumentResolution() {
        val doc = ArtDocument.create("zoom", 1024, 1024)
        doc.layers[0].editPixels(Rect(510, 510, 511, 511)) { it.drawColor(Color.RED) }
        val small = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(small); c.scale(0.125f, 0.125f)
        assertTrue(TiledCompositor.draw(c, doc))
        assertTrue(TiledCompositor.residentBytes < 1024L * 1024)
        assertEquals(Color.RED, doc.layers[0].samplePixel(510, 510))
        assertEquals(0, doc.layers[0].samplePixel(511, 510))
        assertEquals(1024, doc.layers[0].width)
        val full = Compositor.flatten(doc, false)
        assertEquals(Color.RED, full.getPixel(510, 510))
    }

    @Test fun unchangedCompositeTilesAreReusedOutsideEditedTileAndFilterNeighbors() {
        val doc = ArtDocument.create("dirty", 1024, 512)
        val out = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        TiledCompositor.draw(Canvas(out), doc)
        val count = TiledCompositor.rasterizedTiles
        TiledCompositor.draw(Canvas(out), doc)
        assertEquals(count, TiledCompositor.rasterizedTiles)
        doc.layers[0].editPixels(Rect(20, 20, 40, 40)) { it.drawColor(Color.RED) }
        TiledCompositor.draw(Canvas(out), doc)
        // Revisions are tracked per source tile; its three filter-border neighbors also refresh.
        assertEquals(count + 4, TiledCompositor.rasterizedTiles)
    }

    @Test fun readonlyLegacyBitmapDoesNotChangePixelVersion() {
        val p = PixelTiles(512, 512)
        p.edit(Rect(10, 10, 20, 20)) { it.drawColor(Color.RED) }
        val version = p.version
        p.bitmap().getPixel(10, 10)
        p.compact()
        assertEquals(version, p.version)
        assertEquals(Color.RED, p.sample(10, 10))
    }

    @Test fun projectAndPngRoundTripsPreserveHighResolutionSparseLayers() {
        val doc = ArtDocument.create("roundtrip", 517, 299)
        doc.detailScale = 2f
        doc.layers[0].bitmap = reference(doc.width, doc.height)
        doc.layers[0].compactPixels()
        repeat(49) { doc.layers += Layer.empty("empty $it", doc.width, doc.height) }
        doc.layers[25].editPixels(Rect(250, 250, 270, 270)) { it.drawColor(Color.GREEN) }
        val expected = raw(Compositor.flatten(doc))
        val dir = java.nio.file.Files.createTempDirectory("farbwerk-project").toFile()
        val internalFile = java.io.File(dir, "internal.farbwerk")
        val pngFile = java.io.File(dir, "portable.farbwerk")
        try {
            val snapshots = doc.layers.map { at.techgaisi.farbwerk.io.ProjectFormat.LayerData(it.snapshotPixels(), it.mask) }
            at.techgaisi.farbwerk.io.ProjectFormat.writeInternal(internalFile,
                at.techgaisi.farbwerk.io.ProjectFormat.toJson(doc), snapshots, null)
            val loaded = at.techgaisi.farbwerk.io.ProjectFormat.read(internalFile)
            assertEquals(50, loaded.layers.size)
            assertEquals(2f, loaded.detailScale)
            assertEquals(517, loaded.width)
            assertArrayEquals(expected, raw(Compositor.flatten(loaded)))
            assertEquals(0L, loaded.layers[49].pixelBytes)
            at.techgaisi.farbwerk.io.ProjectFormat.write(pngFile,
                at.techgaisi.farbwerk.io.ProjectFormat.toJson(doc), snapshots, null)
            assertArrayEquals(expected, raw(Compositor.flatten(at.techgaisi.farbwerk.io.ProjectFormat.read(pngFile))))
        } finally { internalFile.delete(); pngFile.delete(); dir.delete() }
    }

    @Test fun maskProxyWritesKeepTheOriginalMaskBitmapIdentity() {
        val mask = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val proxy = Layer("mask", mask).apply { keepBitmapIdentity = true }
        proxy.editPixels(Rect(10, 10, 20, 20)) { it.drawColor(Color.RED) }
        proxy.compactPixels()
        proxy.editPixels(Rect(30, 30, 40, 40)) { it.drawColor(Color.BLUE) }
        assertSame(mask, proxy.bitmap)
        assertEquals(Color.RED, mask.getPixel(15, 15))
        assertEquals(Color.BLUE, mask.getPixel(35, 35))
    }
}
