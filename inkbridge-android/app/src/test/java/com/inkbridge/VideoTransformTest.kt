package com.inkbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTransformTest {
    private fun transform() = VideoTransform().apply { resize(2560, 1600, 1920, 1080) }

    @Test fun fitsVideoWithoutStretching() {
        val transform = transform()
        assertEquals(VideoViewport(2560, 1440), transform.viewport)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun zoomKeepsImagePointUnderFingerFocus() {
        val transform = transform()
        transform.transform(2f, 1000f, 600f, 1000f, 600f)
        assertEquals(2f, transform.scale, 0f)
        assertEquals(280f, transform.offsetX, 0.001f)
        assertEquals(200f, transform.offsetY, 0.001f)
        assertEquals(1000f, 1280f + (1000f - 1280f) * transform.scale + transform.offsetX, 0.001f)
        assertEquals(600f, 800f + (600f - 800f) * transform.scale + transform.offsetY, 0.001f)
    }

    @Test fun simultaneousZoomAndPanKeepTheSameSourceCoordinates() {
        val transform = transform()
        val sourceX = (800f - 1280f - transform.offsetX) / transform.scale
        val sourceY = (600f - 800f - transform.offsetY) / transform.scale
        transform.transform(2f, 800f, 600f, 1100f, 700f)
        assertEquals(sourceX, (1100f - 1280f - transform.offsetX) / transform.scale, 0.001f)
        assertEquals(sourceY, (700f - 800f - transform.offsetY) / transform.scale, 0.001f)
    }

    @Test fun twoFingerPanDoesNotChangeScale() {
        val transform = transform()
        transform.transform(3f, 1280f, 800f, 1280f, 800f)
        transform.transform(1f, 1280f, 800f, 1400f, 880f)
        assertEquals(3f, transform.scale, 0f)
        assertEquals(120f, transform.offsetX, 0f)
        assertEquals(80f, transform.offsetY, 0f)
    }

    @Test fun scaleLimitsUseClampedRatioAndRestoreWholeDesktop() {
        val transform = transform()
        transform.transform(20f, 1200f, 700f, 1200f, 700f)
        assertEquals(8f, transform.scale, 0f)
        assertEquals(560f, transform.offsetX, 0f)
        assertEquals(700f, transform.offsetY, 0f)
        transform.transform(0.001f, 1200f, 700f, 1200f, 700f)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun panCannotMoveBeyondImageEdges() {
        val transform = transform()
        transform.transform(2f, 1280f, 800f, 10000f, 10000f)
        assertEquals(1280f, transform.offsetX, 0f)
        assertEquals(640f, transform.offsetY, 0f)
        transform.transform(1f, 1280f, 800f, -10000f, -10000f)
        assertEquals(-1280f, transform.offsetX, 0f)
        assertEquals(-640f, transform.offsetY, 0f)
    }

    @Test fun letterboxedAxisStaysCenteredUntilVideoFillsIt() {
        val transform = transform()
        transform.transform(1.05f, 1280f, 800f, 1280f, 1000f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun unchangedGeometryPreservesZoom() {
        val transform = transform()
        transform.transform(2f, 1000f, 600f, 1000f, 600f)
        transform.resize(2560, 1600, 1920, 1080)
        assertEquals(2f, transform.scale, 0f)
        assertEquals(280f, transform.offsetX, 0f)
        assertEquals(200f, transform.offsetY, 0f)
    }

    @Test fun newVideoResolutionResetsZoomEvenWithTheSameAspectRatio() {
        val transform = transform()
        transform.transform(2f, 1000f, 600f, 1000f, 600f)
        transform.resize(2560, 1600, 1280, 720)
        assertEquals(VideoViewport(2560, 1440), transform.viewport)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun viewResizeResetsZoom() {
        val transform = transform()
        transform.transform(2f, 1280f, 800f, 1400f, 880f)
        transform.resize(1600, 2560, 1920, 1080)
        assertEquals(VideoViewport(1600, 900), transform.viewport)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun stopSharingRestoresFullInputArea() {
        val transform = transform()
        transform.transform(2f, 1280f, 800f, 1400f, 880f)
        transform.resize(2560, 1600, 0, 0)
        assertEquals(VideoViewport(2560, 1600), transform.viewport)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }
}
