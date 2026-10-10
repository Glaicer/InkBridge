package com.inkbridge

import org.junit.Assert.*
import org.junit.Test

class PenShortcutLeaseTest {
    private class Store(var value: String? = "0") : PenShortcutStore {
        var backup: SavedPenShortcutSetting? = null
        var allowWrite = true
        var allowSave = true
        val operations = mutableListOf<String>()
        override fun read() = value
        override fun saved() = backup
        override fun write(value: String?): Boolean {
            operations.add("write:$value")
            if (!allowWrite) return false
            this.value = value
            return true
        }
        override fun save(value: SavedPenShortcutSetting?): Boolean {
            operations.add("save:$value")
            if (!allowSave) return false
            backup = value
            return true
        }
    }

    @Test fun savesOriginalBeforeEnablingAndRestoresOnSessionEnd() {
        val store = Store()
        val lease = PenShortcutLease(store)
        assertTrue(lease.activate())
        assertEquals("1", store.value)
        assertEquals(SavedPenShortcutSetting("0"), store.backup)
        assertTrue(store.operations.first().startsWith("save:"))
        assertTrue(lease.restore())
        assertEquals("0", store.value)
        assertNull(store.backup)
    }

    @Test fun repeatedActivationDoesNotReplaceTheOriginalValue() {
        val store = Store()
        val lease = PenShortcutLease(store)
        repeat(10) { assertTrue(lease.activate()) }
        assertEquals(2, store.operations.size)
        assertTrue(lease.restore())
        assertEquals("0", store.value)
    }

    @Test fun externallyEnabledGameModeIsNeverDisabledByInkBridge() {
        val store = Store("1")
        val lease = PenShortcutLease(store)
        assertTrue(lease.activate())
        assertTrue(lease.restore())
        assertEquals("1", store.value)
        assertTrue(store.operations.isEmpty())
    }

    @Test fun originallyAbsentSettingIsRemovedOnRestore() {
        val store = Store(null)
        val lease = PenShortcutLease(store)
        assertTrue(lease.activate())
        assertEquals(SavedPenShortcutSetting(null), store.backup)
        assertTrue(lease.restore())
        assertNull(store.value)
        assertNull(store.backup)
    }

    @Test fun newInstanceRestoresPendingValueAfterACrash() {
        val store = Store()
        assertTrue(PenShortcutLease(store).activate())
        assertTrue(PenShortcutLease(store).restore())
        assertEquals("0", store.value)
        assertNull(store.backup)
    }

    @Test fun failedBackupDoesNotChangeSystemSetting() {
        val store = Store().apply { allowSave = false }
        assertFalse(PenShortcutLease(store).activate())
        assertEquals("0", store.value)
        assertNull(store.backup)
        assertEquals(1, store.operations.size)
    }

    @Test fun missingWritePermissionDoesNotLeaveAnOwnedSession() {
        val store = Store().apply { allowWrite = false }
        assertFalse(PenShortcutLease(store).activate())
        assertEquals("0", store.value)
        assertNull(store.backup)
    }

    @Test fun failedRestoreRetainsBackupForRetry() {
        val store = Store()
        val lease = PenShortcutLease(store)
        assertTrue(lease.activate())
        store.allowWrite = false
        assertFalse(lease.restore())
        assertEquals("1", store.value)
        assertEquals(SavedPenShortcutSetting("0"), store.backup)
        store.allowWrite = true
        assertTrue(lease.restore())
        assertEquals("0", store.value)
        assertNull(store.backup)
    }

    @Test fun unrelatedExternalChangeIsNotOverwritten() {
        val store = Store()
        val lease = PenShortcutLease(store)
        assertTrue(lease.activate())
        store.value = "2"
        assertTrue(lease.restore())
        assertEquals("2", store.value)
        assertNull(store.backup)
    }
}
