package at.techgaisi.farbwerk.render

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import at.techgaisi.farbwerk.model.ArtDocument

/** Budgets include compressed pixels, decoded textures, composite tiles, stroke work and history. */
object EngineMemory {
    private const val MIB = 1024L * 1024
    var budgetBytes = 256L * MIB
        private set
    val gpuStrokeBudget get() = (budgetBytes / 16).coerceAtMost(32L * MIB)
    val javaPixelBudget get() = (Runtime.getRuntime().maxMemory() / 2).coerceAtMost(384L * MIB)

    fun configure(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val total = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem } ?: (2L shl 30)
        budgetBytes = budgetFor(total)
        PixelTileCache.maxBytes = (budgetBytes / 8).coerceAtMost(48L * MIB)
        TiledCompositor.maxBytes = (budgetBytes / 4).coerceAtMost(128L * MIB)
    }

    internal fun budgetFor(deviceBytes: Long) = (deviceBytes / 8).coerceIn(128L * MIB, 512L * MIB)
    internal fun undoAllowance(budget: Long, pixels: Long, caches: Long, work: Long) =
        minOf(128L * MIB, budget / 4, (budget - pixels - caches - work).coerceAtLeast(0))

    fun undoLimit(doc: ArtDocument?, work: Long): Long = minOf(undoAllowance(budgetBytes,
        doc?.layers?.sumOf { it.pixelBytes + (it.mask?.allocationByteCount ?: 0) } ?: 0,
        PixelTileCache.residentBytes + TiledCompositor.residentBytes, work),
        (javaPixelBudget - (doc?.layers?.sumOf { it.encodedPixelBytes } ?: 0)).coerceAtLeast(0))

    fun available(doc: ArtDocument?, work: Long, undo: Long): Long = minOf(budgetBytes - work - undo -
        (doc?.layers?.sumOf { it.pixelBytes + (it.mask?.allocationByteCount ?: 0) } ?: 0) -
        PixelTileCache.residentBytes - TiledCompositor.residentBytes,
        javaPixelBudget - (doc?.layers?.sumOf { it.encodedPixelBytes } ?: 0) - undo)

    fun clearCaches() { PixelTileCache.clear(); TiledCompositor.clear() }

    fun statistics(doc: ArtDocument?, work: Long, undo: Long): String {
        val pixels = doc?.layers?.sumOf { it.pixelBytes } ?: 0
        return "pixels=${pixels / MIB}MiB tiles=${PixelTileCache.residentBytes / MIB}MiB " +
            "composite=${TiledCompositor.residentBytes / MIB}MiB work=${work / MIB}MiB undo=${undo / MIB}MiB " +
            "native=${Debug.getNativeHeapAllocatedSize() / MIB}MiB " +
            "java=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / MIB}MiB budget=${budgetBytes / MIB}MiB"
    }
}
