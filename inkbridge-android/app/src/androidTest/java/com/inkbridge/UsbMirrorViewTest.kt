package com.inkbridge

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsbMirrorViewTest {
    @Test fun videoSurfaceHasSizeOnFirstLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = UsbMirrorView(instrumentation.targetContext)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 2560, 1600)
            val inputArea = view.getChildAt(0) as ViewGroup
            val surface = inputArea.getChildAt(0)
            assertEquals(2560, inputArea.measuredWidth)
            assertEquals(1600, inputArea.measuredHeight)
            assertTrue("Surface must be laid out before declaring the USB video reader ready", surface.width > 0 && surface.height > 0)
        }
    }
}
