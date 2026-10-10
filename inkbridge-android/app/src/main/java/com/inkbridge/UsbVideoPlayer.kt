package com.inkbridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

data class UsbMirrorState(
    val width: Int = 0,
    val height: Int = 0,
    val message: String = "USB connected. Start screen sharing on the PC."
)

class UsbVideoPlayer(private val sendControl: (Int) -> Unit) {
    private val lock = Object()
    private val frames = ArrayDeque<UsbVideoPacket>()
    private var running = false
    private var surface: Surface? = null
    private var revision = 0
    private var waitingForKeyFrame = true
    private var worker: Thread? = null
    @Volatile var state = UsbMirrorState()
        private set
    @Volatile var onStateChanged: ((UsbMirrorState) -> Unit)? = null

    @Synchronized
    fun start() {
        stop()
        synchronized(lock) {
            running = true
            waitingForKeyFrame = true
        }
        report(UsbMirrorState())
        worker = Thread(::decodeLoop, "InkBridge-VideoDecoder").apply { start() }
    }

    @Synchronized
    fun stop() {
        synchronized(lock) {
            running = false
            revision++
            frames.clear()
            lock.notifyAll()
        }
        worker?.interrupt()
        worker?.join()
        worker = null
        report(UsbMirrorState(message = "USB disconnected."))
    }

    fun setSurface(value: Surface) {
        val ready = synchronized(lock) {
            surface = value
            revision++
            waitingForKeyFrame = true
            frames.clear()
            lock.notifyAll()
            running
        }
        if (ready) sendControl(1)
    }

    fun clearSurface(value: Surface?) {
        synchronized(lock) {
            if (surface !== value) return
            surface = null
            revision++
            frames.clear()
            waitingForKeyFrame = true
            lock.notifyAll()
        }
    }

    fun offer(packet: UsbVideoPacket) {
        if (packet.isStopped) {
            synchronized(lock) {
                frames.clear()
                waitingForKeyFrame = true
                revision++
                lock.notifyAll()
            }
            report(UsbMirrorState(message = packet.payload.toString(Charsets.UTF_8)))
            return
        }
        var requestKeyFrame = false
        synchronized(lock) {
            if (!running || surface == null) return
            if (frames.size >= 3) {
                frames.clear()
                waitingForKeyFrame = true
                requestKeyFrame = !packet.isKeyFrame
            }
            if (packet.isKeyFrame) waitingForKeyFrame = false
            if (!waitingForKeyFrame) frames.addLast(packet)
            lock.notifyAll()
        }
        if (requestKeyFrame) sendControl(2)
    }

    fun videoError(message: String) {
        offer(UsbVideoPacket(0, 0, UsbVideoProtocol.STOPPED, 0, 0, message.toByteArray()))
    }

    private fun report(value: UsbMirrorState, expectedRevision: Int? = null) {
        synchronized(lock) {
            if (expectedRevision != null && expectedRevision != revision) return
            if (state == value) return
            state = value
        }
        onStateChanged?.invoke(value)
    }

    private fun release(codec: MediaCodec?) {
        if (codec == null) return
        try { codec.stop() } catch (_: IllegalStateException) {}
        codec.release()
    }

    private fun resynchronize() {
        synchronized(lock) {
            frames.clear()
            waitingForKeyFrame = true
        }
        sendControl(2)
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var codecRevision = -1
        var failedRevision = -1
        var width = 0
        var height = 0
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val packet: UsbVideoPacket?
                val target: Surface?
                val currentRevision: Int
                synchronized(lock) {
                    if (!running) break
                    if (frames.isEmpty()) lock.wait(8)
                    if (!running) break
                    target = surface
                    currentRevision = revision
                    packet = frames.pollFirst()
                }
                if (codecRevision != currentRevision) {
                    release(codec)
                    codec = null
                    codecRevision = currentRevision
                }
                if (target == null || !target.isValid || failedRevision == currentRevision) continue
                try {
                    if (packet != null) {
                        if (codec == null || packet.width != width || packet.height != height) {
                            release(codec)
                            codec = null
                            if (!packet.isKeyFrame) {
                                resynchronize()
                                continue
                            }
                            val config = UsbVideoProtocol.codecConfig(packet.payload)
                            if (config == null) {
                                resynchronize()
                                continue
                            }
                            width = packet.width
                            height = packet.height
                            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.first))
                            format.setByteBuffer("csd-1", ByteBuffer.wrap(config.second))
                            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, UsbVideoProtocol.MAX_PAYLOAD)
                            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                            codec = decoder
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                decoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                            }
                            decoder.configure(format, target, null, 0)
                            decoder.start()
                            report(UsbMirrorState(width, height, "Receiving USB video…"), currentRevision)
                        }
                        val decoder = codec ?: continue
                        drain(decoder, info, width, height, currentRevision)
                        val inputIndex = decoder.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex) ?: error("Missing decoder input buffer")
                            input.clear()
                            input.put(packet.payload)
                            decoder.queueInputBuffer(inputIndex, 0, packet.payload.size, packet.presentationTimeUs, 0)
                        } else resynchronize()
                    }
                    codec?.let { drain(it, info, width, height, currentRevision) }
                } catch (error: Exception) {
                    Log.e("InkBridgeVideo", "Video decoder failed", error)
                    release(codec)
                    codec = null
                    failedRevision = currentRevision
                    report(UsbMirrorState(message = "Video decoder error: ${error.message}\nPen input remains available. Stop sharing and retry."), currentRevision)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            release(codec)
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, width: Int, height: Int, currentRevision: Int) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) break
            val render = info.size > 0
            codec.releaseOutputBuffer(index, render)
            if (render) report(UsbMirrorState(width, height, ""), currentRevision)
        }
    }
}
