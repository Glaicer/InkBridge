package com.inkbridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbVideoProtocolTest {
    private fun packet(payload: ByteArray, flags: Int = 1, width: Int = 1280, height: Int = 720): ByteArray {
        return ByteBuffer.allocate(32 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x31564249).putInt(payload.size).putInt(width).putInt(height)
            .putInt(flags).putInt(0).putLong(12345).put(payload).array()
    }

    @Test fun readsDesktopGoldenFixture() {
        val hex = "494256310300000000050000d002000001000000000000003930000000000000616263"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val reader = UsbVideoProtocol.Reader(ByteArrayInputStream(bytes))
        val frame = reader.read()!!
        assertEquals(1280, frame.width)
        assertEquals(720, frame.height)
        assertEquals(12345L, frame.presentationTimeUs)
        assertTrue(frame.isKeyFrame)
        assertArrayEquals("abc".toByteArray(), frame.payload)
        assertNull(reader.read())
    }

    @Test fun usbReadsAlwaysRequestAtLeastOneFullAccessoryBuffer() {
        val payload = ByteArray(65537) { (it % 251).toByte() }
        val data = packet(payload) + packet("next".toByteArray())
        val input = object : InputStream() {
            var offset = 0
            override fun read(): Int = error("Unbuffered USB byte read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue("USB accessory reads must not truncate packets", len >= 16384)
                if (offset == data.size) return -1
                val count = minOf(16384, len, data.size - offset)
                data.copyInto(b, off, offset, offset + count)
                offset += count
                return count
            }
        }
        val reader = UsbVideoProtocol.Reader(input)
        assertArrayEquals(payload, reader.read()!!.payload)
        assertEquals("next", reader.read()!!.payload.toString(Charsets.UTF_8))
        assertNull(reader.read())
    }

    @Test fun readsAccessoryDescriptorsWithoutAvailableSupport() {
        val payload = ByteArray(65537) { 42 }
        val input = object : ByteArrayInputStream(packet(payload)) {
            override fun available(): Int = throw IOException("Invalid argument")
        }
        assertArrayEquals(payload, UsbVideoProtocol.Reader(input).read()!!.payload)
    }

    @Test fun acceptsFragmentedHeadersAndFrames() {
        val data = packet(ByteArray(20000) { 42 }) + packet("Stopped".toByteArray(), 2, 0, 0)
        val input = object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
        }
        val reader = UsbVideoProtocol.Reader(input)
        assertEquals(20000, reader.read()!!.payload.size)
        val stop = reader.read()!!
        assertTrue(stop.isStopped)
        assertEquals("Stopped", stop.payload.toString(Charsets.UTF_8))
    }

    @Test(expected = IOException::class) fun rejectsOversizedPayloadBeforeAllocation() {
        val header = packet("a".toByteArray())
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, UsbVideoProtocol.MAX_PAYLOAD + 1)
        UsbVideoProtocol.Reader(ByteArrayInputStream(header)).read()
    }

    @Test(expected = IOException::class) fun rejectsUnsupportedMagic() {
        val data = packet("a".toByteArray())
        data[0] = 0
        UsbVideoProtocol.Reader(ByteArrayInputStream(data)).read()
    }

    @Test(expected = EOFException::class) fun rejectsTruncatedFrame() {
        val data = packet("abc".toByteArray())
        UsbVideoProtocol.Reader(ByteArrayInputStream(data.copyOf(data.size - 1))).read()
    }

    @Test fun separatesCodecConfigurationFromAccessUnit() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e)
        val pps = byteArrayOf(0, 0, 1, 0x68, 1, 2)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 3, 4)
        val config = UsbVideoProtocol.codecConfig(sps + pps + idr)!!
        assertArrayEquals(sps, config.first)
        assertArrayEquals(pps, config.second)
        assertNull(UsbVideoProtocol.codecConfig(idr))
    }

    @Test fun capabilityPacketsUseExisting22ByteFraming() {
        val hello = ByteBuffer.wrap(UsbVideoProtocol.control(1)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(22, hello.capacity())
        assertEquals(127, hello.get().toInt())
        assertEquals(1, hello.get().toInt())
        assertEquals(1, hello.int)
        assertEquals(2, UsbVideoProtocol.control(2)[1].toInt())
    }

    @Test fun fitsXiaomiPad5WithoutStretching() {
        assertEquals(VideoViewport(2560, 1440), VideoViewport.fit(2560, 1600, 1920, 1080))
        assertEquals(VideoViewport(1600, 900), VideoViewport.fit(1600, 2560, 1920, 1080))
        assertEquals(VideoViewport(1440, 2560), VideoViewport.fit(1600, 2560, 1080, 1920))
        assertEquals(VideoViewport(2560, 1600), VideoViewport.fit(2560, 1600, 0, 0))
    }
}
