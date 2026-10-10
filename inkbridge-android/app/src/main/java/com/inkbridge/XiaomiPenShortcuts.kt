package com.inkbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

class XiaomiPenShortcuts(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val lease = PenShortcutLease(object : PenShortcutStore {
        override fun read(): String? = Settings.Secure.getString(context.contentResolver, "gb_boosting")

        override fun write(value: String?): Boolean = try {
            Settings.Secure.putString(context.contentResolver, "gb_boosting", value)
        } catch (error: SecurityException) {
            Log.w("InkBridgePen", "Cannot update Xiaomi pen shortcuts", error)
            false
        }

        override fun saved(): SavedPenShortcutSetting? = if (prefs.getBoolean("xiaomi_pen_pending", false)) {
            SavedPenShortcutSetting(prefs.getString("xiaomi_pen_original", null))
        } else null

        @SuppressLint("ApplySharedPref", "UseKtx")
        override fun save(value: SavedPenShortcutSetting?): Boolean = if (value == null) {
            prefs.edit().remove("xiaomi_pen_original").remove("xiaomi_pen_pending").commit()
        } else {
            prefs.edit().putString("xiaomi_pen_original", value.value).putBoolean("xiaomi_pen_pending", true).commit()
        }
    })
    private val appContext = context.applicationContext

    init {
        setActive(false)
    }

    fun setActive(active: Boolean) {
        val enabled = active && supported && prefs.getBoolean(ENABLED_PREF, false) &&
            appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        val success = if (enabled) lease.activate() else lease.restore()
        if (!success) Log.w("InkBridgePen", "Xiaomi shortcut setting could not be restored or applied")
    }

    companion object {
        const val ENABLED_PREF = "xiaomi_pen_shortcuts"
        val supported: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }
}
