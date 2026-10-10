package com.inkbridge

import org.junit.Assert.*
import org.junit.Test

class PenButtonsTest {
    private val eraser = mutableListOf<Boolean>()
    private var undos = 0
    private val buttons = PenButtons({ eraser.add(it) }, { undos++ }).apply { enabled = true }

    private fun key(code: Int, action: Int, repeat: Int = 0, device: Int = 10, isPen: Boolean = true) =
        buttons.handle(code, action, repeat, device, isPen)

    @Test fun primaryButtonHoldsEraserUntilReleasedWithoutRepeating() {
        assertTrue(key(93, 0))
        repeat(40) { assertTrue(key(93, 0, it + 1)) }
        assertEquals(listOf(true), eraser)
        assertTrue(key(93, 1))
        assertEquals(listOf(true, false), eraser)
    }

    @Test fun secondaryButtonUndoesOnlyOncePerPress() {
        assertTrue(key(92, 0))
        repeat(40) { assertTrue(key(92, 0, it + 1)) }
        assertTrue(key(92, 0))
        assertEquals(1, undos)
        assertTrue(key(92, 1))
        assertTrue(key(92, 0))
        assertEquals(2, undos)
    }

    @Test fun ordinaryKeyboardPageKeysAreNotConsumed() {
        assertFalse(key(93, 0, isPen = false))
        assertFalse(key(92, 0, isPen = false))
        assertTrue(eraser.isEmpty())
        assertEquals(0, undos)
    }

    @Test fun disablingSessionReleasesEraserAndIgnoresHeldKeyRepeatsOnResume() {
        key(93, 0)
        buttons.enabled = false
        assertEquals(listOf(true, false), eraser)
        assertFalse(key(93, 0))
        buttons.enabled = true
        key(93, 0, 12)
        key(92, 0, 12)
        assertEquals(listOf(true, false), eraser)
        assertEquals(0, undos)
        key(93, 1)
        key(93, 0)
        assertEquals(listOf(true, false, true), eraser)
    }

    @Test fun removingPenReleasesItsButtonsButRemovingKeyboardDoesNot() {
        key(93, 0)
        key(92, 0)
        buttons.releaseDevice(19)
        assertEquals(listOf(true), eraser)
        buttons.releaseDevice(10)
        assertEquals(listOf(true, false), eraser)
        key(92, 0, device = 11)
        assertEquals(2, undos)
    }

    @Test fun releaseFromAnotherDeviceCannotClearHeldEraser() {
        key(93, 0)
        key(93, 1, device = 11)
        assertEquals(listOf(true), eraser)
        key(93, 1)
        assertEquals(listOf(true, false), eraser)
    }

    @Test fun resetIsIdempotentAndAllowsANewUndoPress() {
        key(93, 0)
        key(92, 0)
        buttons.reset()
        buttons.reset()
        assertEquals(listOf(true, false), eraser)
        key(92, 0)
        assertEquals(2, undos)
    }

    @Test fun unrelatedKeysAndActionsAreNotConsumed() {
        assertFalse(key(24, 0))
        assertFalse(key(93, 2))
        assertTrue(eraser.isEmpty())
        assertEquals(0, undos)
    }

    @Test fun identifiesPenWithoutHardcodingItsRuntimeDeviceId() {
        assertTrue(PenButtons.isXiaomiPen("Xiaomi Smart Pen", 0, 0))
        assertTrue(PenButtons.isXiaomiPen(null, 0x1915, 0xeaea))
        assertFalse(PenButtons.isXiaomiPen("Xiaomi Pad Keyboard", 0x3206, 0x3ffc))
        assertFalse(PenButtons.isXiaomiPen(null, 0, 0))
    }
}
