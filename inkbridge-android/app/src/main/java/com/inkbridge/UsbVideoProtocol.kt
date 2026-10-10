package com.inkbridge

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class UsbVideoPacket(
    val width: Int,
    val height: Int,
    val flags: Int,
    val sequence: Int,
    val presentationTimeUs: Long,
    val payload: ByteArray
) {
    val isKeyFrame: Boolean get() = flags and UsbVideoProtocol.KEY_FRAME != 0
    val isStopped: Boolean get() = flags == UsbVideoProtocol.STOPPED
}

object UsbVideoProtocol {
    const val KEY_FRAME = 1
    const val STOPPED = 2
    const val MAX_PAYLOAD = 4 * 1024 * 1024
    const val UNDO = 3

    fun control(action: Int): ByteArray = ByteBuffer.allocate(22)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(127.toByte())
        .put(action.toByte())
        .putInt(if (action == 1) 1 else 0)
        .putInt(0).putInt(0).putInt(0).putInt(0)
        .array()

    class Reader(input: InputStream) {
        private val stream = DataInputStream(BufferedInputStream(object : FilterInputStream(input) {
            override fun available(): Int = 0
        }, 16384))

        fun read(): UsbVideoPacket? {
            val header = ByteArray(32)
            val first = stream.read()
            if (first == -1) return null
            header[0] = first.toByte()
            stream.readFully(header, 1, 31)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != 0x31564249) throw IOException("Unsupported USB video protocol")
            val length = buffer.int
            val width = buffer.int
            val height = buffer.int
            val flags = buffer.int
            val sequence = buffer.int
            val pts = buffer.long
            if (length !in 0..MAX_PAYLOAD || pts < 0) throw IOException("Invalid USB video header")
            if (flags == STOPPED) {
                if (width != 0 || height != 0 || length > 4096) throw IOException("Invalid stop packet")
            } else if (flags !in 0..KEY_FRAME || width !in 2..4096 || height !in 2..4096 || length == 0) {
                throw IOException("Invalid video frame")
            }
            val payload = ByteArray(length)
            try {
                stream.readFully(payload)
            } catch (error: EOFException) {
                throw EOFException("USB disconnected during a video frame")
            }
            return UsbVideoPacket(width, height, flags, sequence, pts, payload)
        }
    }

    fun codecConfig(frame: ByteArray): Pair<ByteArray, ByteArray>? {
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index + 3 < frame.size) {
            val prefix = if (frame[index] == 0.toByte() && frame[index + 1] == 0.toByte()) {
                if (frame[index + 2] == 1.toByte()) 3
                else if (frame[index + 2] == 0.toByte() && frame[index + 3] == 1.toByte()) 4
                else 0
            } else 0
            if (prefix != 0) {
                starts.add(index to prefix)
                index += prefix
            } else index++
        }
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (i in starts.indices) {
            val (start, prefix) = starts[i]
            if (start + prefix >= frame.size) continue
            val end = if (i + 1 < starts.size) starts[i + 1].first else frame.size
            val type = frame[start + prefix].toInt() and 31
            if (type == 7) sps = frame.copyOfRange(start, end)
            if (type == 8) pps = frame.copyOfRange(start, end)
        }
        return if (sps != null && pps != null) sps to pps else null
    }
}
