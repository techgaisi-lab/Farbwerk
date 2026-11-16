package at.techgaisi.farbwerk.render

import org.junit.Assert.*
import org.junit.Test

class EngineMemoryTest {
    private val mib = 1024L * 1024
    @Test fun budgetScalesWithinExplicitDeviceBounds() {
        assertEquals(128 * mib, EngineMemory.budgetFor(512 * mib))
        assertEquals(256 * mib, EngineMemory.budgetFor(2048 * mib))
        assertEquals(512 * mib, EngineMemory.budgetFor(12 * 1024 * mib))
    }
    @Test fun historyCannotSpendMemoryAlreadyUsedByPixelsCachesAndStroke() {
        assertEquals(128 * mib, EngineMemory.undoAllowance(512 * mib, 100 * mib, 100 * mib, 100 * mib))
        assertEquals(12 * mib, EngineMemory.undoAllowance(512 * mib, 300 * mib, 100 * mib, 100 * mib))
        assertEquals(0L, EngineMemory.undoAllowance(512 * mib, 400 * mib, 100 * mib, 100 * mib))
    }
}
