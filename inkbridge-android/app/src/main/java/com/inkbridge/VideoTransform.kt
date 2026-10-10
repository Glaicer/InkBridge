package com.inkbridge

import kotlin.math.max

class VideoTransform {
    private var view = VideoViewport(0, 0)
    private var video = VideoViewport(0, 0)
    var viewport = VideoViewport(0, 0)
        private set
    var scale = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    fun resize(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int) {
        val newView = VideoViewport(viewWidth, viewHeight)
        val newVideo = VideoViewport(videoWidth, videoHeight)
        if (view == newView && video == newVideo) return
        view = newView
        video = newVideo
        viewport = VideoViewport.fit(viewWidth, viewHeight, videoWidth, videoHeight)
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    fun transform(factor: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val newScale = (scale * factor).coerceIn(1f, 8f)
        val ratio = newScale / scale
        val limitX = max(0f, (viewport.width * newScale - view.width) / 2f)
        val limitY = max(0f, (viewport.height * newScale - view.height) / 2f)
        offsetX = (toX - view.width / 2f - (fromX - view.width / 2f - offsetX) * ratio)
            .coerceIn(-limitX, limitX)
        offsetY = (toY - view.height / 2f - (fromY - view.height / 2f - offsetY) * ratio)
            .coerceIn(-limitY, limitY)
        scale = newScale
    }
}
