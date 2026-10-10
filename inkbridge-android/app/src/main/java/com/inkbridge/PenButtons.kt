package com.inkbridge

class PenButtons(
    private val setEraser: (Boolean) -> Unit,
    private val undo: () -> Unit
) {
    private var primaryDevice: Int? = null
    private var secondaryDevice: Int? = null
    var enabled = false
        set(value) {
            if (!value) reset()
            field = value
        }

    fun handle(keyCode: Int, action: Int, repeatCount: Int, deviceId: Int, isPen: Boolean): Boolean {
        if (!enabled || !isPen || action !in 0..1) return false
        when (keyCode) {
            93 -> {
                if (action == 0 && repeatCount == 0 && primaryDevice == null) {
                    primaryDevice = deviceId
                    setEraser(true)
                } else if (action == 1 && primaryDevice == deviceId) {
                    primaryDevice = null
                    setEraser(false)
                }
            }
            92 -> {
                if (action == 0 && repeatCount == 0 && secondaryDevice == null) {
                    secondaryDevice = deviceId
                    undo()
                } else if (action == 1 && secondaryDevice == deviceId) {
                    secondaryDevice = null
                }
            }
            else -> return false
        }
        return true
    }

    fun releaseDevice(deviceId: Int) {
        if (primaryDevice == deviceId) {
            primaryDevice = null
            setEraser(false)
        }
        if (secondaryDevice == deviceId) secondaryDevice = null
    }

    fun reset() {
        primaryDevice?.let(::releaseDevice)
        secondaryDevice = null
    }

    companion object {
        fun isXiaomiPen(name: String?, vendorId: Int, productId: Int): Boolean =
            name == "Xiaomi Smart Pen" || (vendorId == 0x1915 && productId == 0xeaea)
    }
}
