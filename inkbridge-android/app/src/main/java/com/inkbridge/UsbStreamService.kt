package com.inkbridge

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

object UsbStreamService {

    private const val TAG = "InkBridgeUsbService"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private var readerThread: Thread? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    @Volatile private var generation = 0
    var onDisconnected: ((String) -> Unit)? = null
    val isConnected: Boolean get() = isStreamOpen
    
    // Using the zero-allocation circular buffer
    private val ringBuffer = PacketRingBuffer(16)

    @Volatile private var isStreamOpen = false
    private var currentListener: TouchListener? = null

    private const val HEARTBEAT_PACKET_SIZE = 22
    val videoPlayer = UsbVideoPlayer { action ->
        if (isStreamOpen) {
            val control = UsbVideoProtocol.control(action)
            ringBuffer.write(control, 0, control.size)
        }
    }

    fun updateView(view: View) {
        if (currentListener != null) {
            view.setOnTouchListener(currentListener)
            view.setOnGenericMotionListener(currentListener)
            view.requestFocus()
        }
    }

    fun setEraserPressed(pressed: Boolean) {
        currentListener?.setEraserPressed(pressed)
    }

    fun undo() {
        if (!isStreamOpen || videoPlayer.state.width == 0) return
        val control = UsbVideoProtocol.control(UsbVideoProtocol.UNDO)
        ringBuffer.write(control, 0, control.size)
    }

    /**
     * BLOCKING call to open the stream.
     * Call this from a background thread (Dispatchers.IO).
     */
    @Synchronized
    fun connect(usbManager: UsbManager, accessory: UsbAccessory, context: Context): Boolean {
        if (isStreamOpen) {
            closeStream()
        }

        return try {
            fileDescriptor = usbManager.openAccessory(accessory)

            if (fileDescriptor == null) {
                Log.e(TAG, "OS refused to open accessory. PC app likely not running.")
                return false
            }

            val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
            val rawOutputStream = FileOutputStream(fd)
            val rawInputStream = FileInputStream(fd)
            outputStream = rawOutputStream
            inputStream = rawInputStream
            val session = generation

            isStreamOpen = true
            ringBuffer.clear()
            videoPlayer.start()

            workerThread = Thread {
                writeLoop(rawOutputStream, session)
            }.apply {
                name = "InkBridge-UsbWriter"
                start()
            }
            readerThread = Thread {
                readLoop(rawInputStream, session)
            }.apply {
                name = "InkBridge-UsbVideoReader"
                start()
            }

            val queueWrapper = object : OutputStream() {
                override fun write(b: Int) { /* Unused */ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    ringBuffer.write(b, off, len)
                }
                override fun write(b: ByteArray) {
                    ringBuffer.write(b, 0, b.size)
                }
            }

            // Read preference once at connection time — not per-event
            val stylusOnly = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("stylus_only", false)
            currentListener = TouchListener(queueWrapper, stylusOnly)

            Log.d(TAG, "USB Stream Opened Successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error in connect()", e)
            closeStream()
            false
        }
    }

    private fun writeLoop(stream: FileOutputStream, session: Int) {
        val heartbeat = ByteArray(HEARTBEAT_PACKET_SIZE) { 127.toByte() }

        while (isStreamOpen && session == generation) {
            try {
                // Waits up to 500ms for data, drains it instantly if available
                val hasData = ringBuffer.waitForDataAndDrain(stream, 500)
                
                if (!hasData) {
                    stream.write(heartbeat)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write Loop Error: ${e.message}")
                disconnected(session, "USB write failed: ${e.message}")
                break
            }
        }

    }

    private fun readLoop(stream: FileInputStream, session: Int) {
        try {
            val reader = UsbVideoProtocol.Reader(stream)
            while (isStreamOpen && session == generation) {
                val packet = reader.read() ?: break
                if (session == generation) videoPlayer.offer(packet)
            }
            disconnected(session, "USB disconnected.")
        } catch (error: Exception) {
            if (isStreamOpen && session == generation) {
                Log.e(TAG, "USB video read failed", error)
                disconnected(session, "USB video read failed: ${error.message}")
            }
        }
    }

    private fun disconnected(session: Int, message: String) {
        if (session != generation || !isStreamOpen) return
        isStreamOpen = false
        videoPlayer.videoError(message)
        onDisconnected?.invoke(message)
    }

    @Synchronized
    fun closeStream() {
        Log.d(TAG, "Closing Stream...")
        isStreamOpen = false
        generation++
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { fileDescriptor?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        fileDescriptor = null
        try {
            workerThread?.interrupt()
            readerThread?.interrupt()
            if (workerThread !== Thread.currentThread()) workerThread?.join(1000)
            if (readerThread !== Thread.currentThread()) readerThread?.join(1000)
        } catch (e: Exception) {}

        workerThread = null
        readerThread = null
        videoPlayer.stop()
        currentListener = null

        ringBuffer.clear()
        Log.d(TAG, "Stream Closed.")
    }
}