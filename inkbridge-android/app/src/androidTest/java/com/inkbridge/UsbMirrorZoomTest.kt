package com.inkbridge

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class UsbMirrorZoomTest {
    private data class Pointer(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        fun properties() = MotionEvent.PointerProperties().apply {
            id = this@Pointer.id
            toolType = tool
        }
        fun coordinates(pressure: Float = 0.5f) = MotionEvent.PointerCoords().apply {
            x = this@Pointer.x
            y = this@Pointer.y
            this.pressure = pressure
            setAxisValue(MotionEvent.AXIS_TILT, 0.4f)
            setAxisValue(MotionEvent.AXIS_ORIENTATION, 0.3f)
        }
    }

    private fun event(action: Int, vararg pointers: Pointer, buttonState: Int = 0): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now - 1000, now, action, pointers.size,
            pointers.map { it.properties() }.toTypedArray(), pointers.map { it.coordinates() }.toTypedArray(),
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(view: UsbMirrorView, action: Int, vararg pointers: Pointer) {
        val event = event(action, *pointers)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun layout(view: UsbMirrorView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 2560, 1600)
    }

    private fun withView(stylusOnly: Boolean = false, test: (UsbMirrorView, ViewGroup, ByteArrayOutputStream) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = UsbMirrorView(InstrumentationRegistry.getInstrumentation().targetContext)
            layout(view)
            view.updateState(UsbMirrorState(1920, 1080, ""))
            layout(view)
            val area = view.getChildAt(0) as ViewGroup
            val output = ByteArrayOutputStream()
            val listener = TouchListener(output, stylusOnly)
            area.setOnTouchListener(listener)
            area.setOnGenericMotionListener(listener)
            test(view, area, output)
        }
    }

    private fun pinchAndPan(view: UsbMirrorView) {
        send(view, MotionEvent.ACTION_DOWN, Pointer(0, 800f, 800f))
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), Pointer(0, 800f, 800f), Pointer(1, 1200f, 800f))
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 600f, 800f), Pointer(1, 1400f, 800f))
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 200f, 800f), Pointer(1, 1800f, 800f))
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 400f, 800f), Pointer(1, 2000f, 800f))
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), Pointer(0, 400f, 800f), Pointer(1, 2000f, 800f))
        send(view, MotionEvent.ACTION_UP, Pointer(0, 400f, 800f))
    }

    private fun stylus(area: ViewGroup, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) = Pointer(2,
        area.left + area.width / 2f + (x - area.width / 2f) * area.scaleX + area.translationX,
        area.top + area.height / 2f + (y - area.height / 2f) * area.scaleY + area.translationY, tool)

    private fun packet(output: ByteArrayOutputStream, index: Int = 0) =
        ByteBuffer.wrap(output.toByteArray(), index * 22, 22).slice().order(ByteOrder.LITTLE_ENDIAN)

    private fun assertPosition(packet: ByteBuffer, x: Float, y: Float, area: ViewGroup) {
        assertEquals((x / area.width * 32767).toInt().toDouble(), packet.getInt(2).toDouble(), 1.0)
        assertEquals((y / area.height * 32767).toInt().toDouble(), packet.getInt(6).toDouble(), 1.0)
    }

    @Test fun pinchAndPanNeverSendFingerPacketsInEitherInputMode() {
        for (stylusOnly in listOf(false, true)) withView(stylusOnly) { view, area, output ->
            pinchAndPan(view)
            assertEquals(2f, area.scaleX, 0.001f)
            assertEquals(2f, area.scaleY, 0.001f)
            assertEquals(480f, area.translationX, 0.001f)
            assertEquals(0, output.size())
        }
    }

    @Test fun transformedStylusAndHistoryKeepDesktopCoordinatesPressureAndTilt() = withView { view, area, output ->
        pinchAndPan(view)
        send(view, MotionEvent.ACTION_DOWN, stylus(area, 1500f, 500f))
        output.reset()
        val move = event(MotionEvent.ACTION_MOVE, stylus(area, 1500f, 500f))
        move.addBatch(SystemClock.uptimeMillis() + 10,
            arrayOf(stylus(area, 1600f, 500f).coordinates(1f)), 0)
        view.dispatchTouchEvent(move)
        move.recycle()
        assertEquals(44, output.size())
        assertPosition(packet(output, 0), 1500f, 500f, area)
        assertPosition(packet(output, 1), 1600f, 500f, area)
        assertEquals(2048, packet(output, 0).getInt(10))
        assertEquals(4096, packet(output, 1).getInt(10))
        assertEquals((sin(0.4f) * sin(0.3f) * 90f).toInt(), packet(output, 1).getInt(14))
        assertEquals((sin(0.4f) * cos(0.3f) * 90f).toInt(), packet(output, 1).getInt(18))
        send(view, MotionEvent.ACTION_UP, stylus(area, 1600f, 500f))
        assertEquals(MotionEvent.ACTION_UP, packet(output, 2).get(1).toInt())
    }

    @Test fun hoverAndEraserUseTheSameInverseTransform() = withView { view, area, output ->
        pinchAndPan(view)
        for (tool in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
            val hover = event(MotionEvent.ACTION_HOVER_MOVE, stylus(area, 1500f, 500f, tool))
            hover.source = InputDevice.SOURCE_STYLUS
            view.dispatchGenericMotionEvent(hover)
            hover.recycle()
            val packet = packet(output, output.size() / 22 - 1)
            assertEquals(tool, packet.get(0).toInt())
            assertPosition(packet, 1500f, 500f, area)
        }
        assertEquals(44, output.size())
    }

    @Test fun fingersCannotMoveViewportDuringOrImmediatelyAfterMixedPenStroke() = withView { view, area, output ->
        val finger = Pointer(0, 1000f, 800f)
        val pen = stylus(area, 1500f, 500f)
        send(view, MotionEvent.ACTION_DOWN, finger)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger, pen)
        assertEquals(MotionEvent.ACTION_DOWN, packet(output).get(1).toInt())
        send(view, MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), finger, pen, Pointer(1, 1400f, 800f))
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 200f, 800f), pen, Pointer(1, 2200f, 800f))
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger, pen, Pointer(1, 1400f, 800f))
        assertEquals(MotionEvent.ACTION_UP, packet(output, 3).get(1).toInt())
        val packetCount = output.size() / 22
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 200f, 800f), Pointer(1, 2200f, 800f))
        assertEquals(packetCount * 22, output.size())
        assertEquals(1f, area.scaleX, 0f)
        assertEquals(0f, area.translationX, 0f)
        for (index in 0 until packetCount) {
            assertEquals(MotionEvent.TOOL_TYPE_STYLUS, packet(output, index).get(0).toInt())
            assertPosition(packet(output, index), 1500f, 500f, area)
        }
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger, Pointer(1, 1400f, 800f))
        send(view, MotionEvent.ACTION_UP, finger)
        pinchAndPan(view)
        assertEquals(2f, area.scaleX, 0.001f)
    }

    @Test fun mixedPointerHistoryPreservesPenSensorsAndButtonState() = withView { view, area, output ->
        pinchAndPan(view)
        val finger = Pointer(0, 1000f, 800f)
        val pen = stylus(area, 1500f, 500f)
        send(view, MotionEvent.ACTION_DOWN, pen)
        send(view, MotionEvent.ACTION_POINTER_DOWN, finger, pen)
        output.reset()
        val move = event(MotionEvent.ACTION_MOVE, finger, pen, buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)
        move.addBatch(SystemClock.uptimeMillis() + 10,
            arrayOf(finger.coordinates(), stylus(area, 1600f, 500f).coordinates(0.75f)), 0)
        view.dispatchTouchEvent(move)
        move.recycle()
        assertEquals(44, output.size())
        for (index in 0..1) {
            assertEquals(MotionEvent.TOOL_TYPE_STYLUS, packet(output, index).get(0).toInt())
            assertEquals(MotionEvent.ACTION_MOVE or 32, packet(output, index).get(1).toInt())
            assertEquals((sin(0.4f) * sin(0.3f) * 90f).toInt(), packet(output, index).getInt(14))
            assertEquals((sin(0.4f) * cos(0.3f) * 90f).toInt(), packet(output, index).getInt(18))
        }
        assertPosition(packet(output, 0), 1500f, 500f, area)
        assertPosition(packet(output, 1), 1600f, 500f, area)
        assertEquals(2048, packet(output, 0).getInt(10))
        assertEquals(3072, packet(output, 1).getInt(10))
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger, pen)
        send(view, MotionEvent.ACTION_UP, finger)
    }

    @Test fun stoppingVideoDuringGestureConsumesRemainingFingersWithoutDrawingOrZooming() = withView { view, area, output ->
        val finger = Pointer(0, 800f, 800f)
        send(view, MotionEvent.ACTION_DOWN, finger)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger, Pointer(1, 1200f, 800f))
        view.updateState(UsbMirrorState())
        layout(view)
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 600f, 800f), Pointer(1, 1400f, 800f))
        send(view, MotionEvent.ACTION_MOVE, Pointer(0, 200f, 800f), Pointer(1, 1800f, 800f))
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger, Pointer(1, 1200f, 800f))
        send(view, MotionEvent.ACTION_UP, finger)
        assertEquals(1f, area.scaleX, 0f)
        assertEquals(0, output.size())
        send(view, MotionEvent.ACTION_DOWN, finger)
        send(view, MotionEvent.ACTION_UP, finger)
        assertEquals(44, output.size())
    }

    @Test fun letterboxDoesNotGeneratePenPackets() = withView { view, _, output ->
        val pen = Pointer(2, 1000f, 20f, MotionEvent.TOOL_TYPE_STYLUS)
        send(view, MotionEvent.ACTION_DOWN, pen)
        send(view, MotionEvent.ACTION_UP, pen)
        assertEquals(0, output.size())
    }

    @Test fun stateUpdatesKeepZoomButStopSharingRestoresOriginalFingerInput() = withView { view, area, output ->
        pinchAndPan(view)
        view.updateState(UsbMirrorState(1920, 1080, "Receiving USB video…"))
        assertEquals(2f, area.scaleX, 0f)
        view.updateState(UsbMirrorState())
        layout(view)
        assertEquals(1f, area.scaleX, 0f)
        assertEquals(0f, area.translationX, 0f)
        assertEquals(1600, area.height)
        send(view, MotionEvent.ACTION_DOWN, Pointer(0, 1000f, 800f))
        send(view, MotionEvent.ACTION_UP, Pointer(0, 1000f, 800f))
        assertEquals(44, output.size())
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, packet(output).get(0).toInt())
    }
}
