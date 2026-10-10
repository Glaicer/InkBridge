package com.inkbridge

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PenButtonInputTest {
    private fun event(action: Int, pressure: Float = 0.75f, buttons: Int = 0,
                      tool: Int = MotionEvent.TOOL_TYPE_STYLUS): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = 400f
            y = 300f
            this.pressure = pressure
            setAxisValue(MotionEvent.AXIS_TILT, 0.4f)
            setAxisValue(MotionEvent.AXIS_ORIENTATION, 0.3f)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun withListener(test: (TouchListener, View, ByteArrayOutputStream) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            view.layout(0, 0, 1000, 800)
            val output = ByteArrayOutputStream()
            test(TouchListener(output), view, output)
        }
    }

    private fun send(listener: TouchListener, view: View, event: MotionEvent) {
        listener.onGenericMotion(view, event)
        event.recycle()
    }

    private fun packet(output: ByteArrayOutputStream, index: Int = 0) =
        ByteBuffer.wrap(output.toByteArray(), index * 22, 22).slice().order(ByteOrder.LITTLE_ENDIAN)

    @Test fun buttonTransitionsReplaySensorsAndCoordinatesWithoutMovement() = withListener { listener, view, output ->
        send(listener, view, event(MotionEvent.ACTION_DOWN))
        val original = output.toByteArray()
        output.reset()
        listener.setEraserPressed(true)
        listener.setEraserPressed(true)
        listener.setEraserPressed(false)
        assertEquals(44, output.size())
        assertEquals(MotionEvent.ACTION_MOVE or 32, packet(output).get(1).toInt())
        assertEquals(MotionEvent.ACTION_MOVE, packet(output, 1).get(1).toInt())
        for (index in 0..1) {
            assertEquals(MotionEvent.TOOL_TYPE_STYLUS, packet(output, index).get(0).toInt())
            assertArrayEquals(original.copyOfRange(2, 22), output.toByteArray().copyOfRange(index * 22 + 2, index * 22 + 22))
        }
    }

    @Test fun holdingBeforeFirstPenSampleDoesNotInventCoordinates() = withListener { listener, view, output ->
        listener.setEraserPressed(true)
        assertEquals(0, output.size())
        send(listener, view, event(MotionEvent.ACTION_DOWN))
        assertEquals(MotionEvent.ACTION_DOWN or 32, packet(output).get(1).toInt())
        assertEquals(3072, packet(output).getInt(10))
    }

    @Test fun transitionAfterTipUpDoesNotCreateAnotherContact() = withListener { listener, view, output ->
        send(listener, view, event(MotionEvent.ACTION_DOWN))
        send(listener, view, event(MotionEvent.ACTION_UP, pressure = 0f))
        output.reset()
        listener.setEraserPressed(true)
        listener.setEraserPressed(false)
        assertEquals(MotionEvent.ACTION_HOVER_MOVE or 32, packet(output).get(1).toInt())
        assertEquals(MotionEvent.ACTION_HOVER_MOVE, packet(output, 1).get(1).toInt())
        assertEquals(0, packet(output).getInt(10))
        assertEquals(0, packet(output, 1).getInt(10))
    }

    @Test fun releasingXiaomiButtonPreservesStandardStylusButton() = withListener { listener, view, output ->
        listener.setEraserPressed(true)
        send(listener, view, event(MotionEvent.ACTION_MOVE, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY))
        output.reset()
        listener.setEraserPressed(false)
        assertEquals(MotionEvent.ACTION_MOVE or 32, packet(output).get(1).toInt())
    }

    @Test fun proximityExitPreventsStaleSampleReplay() = withListener { listener, view, output ->
        send(listener, view, event(MotionEvent.ACTION_HOVER_MOVE, pressure = 0f))
        send(listener, view, event(MotionEvent.ACTION_HOVER_EXIT, pressure = 0f))
        output.reset()
        listener.setEraserPressed(true)
        listener.setEraserPressed(false)
        assertEquals(0, output.size())
    }

    @Test fun heldPenButtonDoesNotTurnFingerInputIntoEraser() = withListener { listener, view, output ->
        listener.setEraserPressed(true)
        send(listener, view, event(MotionEvent.ACTION_DOWN, tool = MotionEvent.TOOL_TYPE_FINGER))
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, packet(output).get(0).toInt())
        assertEquals(MotionEvent.ACTION_DOWN, packet(output).get(1).toInt())
    }
}
