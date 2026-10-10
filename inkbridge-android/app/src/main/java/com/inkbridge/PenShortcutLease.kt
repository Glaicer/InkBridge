package com.inkbridge

data class SavedPenShortcutSetting(val value: String?)

interface PenShortcutStore {
    fun read(): String?
    fun write(value: String?): Boolean
    fun saved(): SavedPenShortcutSetting?
    fun save(value: SavedPenShortcutSetting?): Boolean
}

class PenShortcutLease(private val store: PenShortcutStore) {
    private var active = false

    fun activate(): Boolean {
        if (active) return true
        if (!restore()) return false
        val original = store.read()
        if (original != "1") {
            if (!store.save(SavedPenShortcutSetting(original))) return false
            if (!store.write("1")) {
                restore()
                return false
            }
        }
        active = true
        return true
    }

    fun restore(): Boolean {
        active = false
        val saved = store.saved() ?: return true
        if (store.read() == "1" && !store.write(saved.value)) return false
        return store.save(null)
    }
}
