package com.inkbridge

import kotlin.math.min
import kotlin.math.roundToInt

data class VideoViewport(val width: Int, val height: Int) {
    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int): VideoViewport {
            if (videoWidth <= 0 || videoHeight <= 0) return VideoViewport(viewWidth, viewHeight)
            val scale = min(viewWidth.toDouble() / videoWidth, viewHeight.toDouble() / videoHeight)
            return VideoViewport((videoWidth * scale).roundToInt(), (videoHeight * scale).roundToInt())
        }
    }
}
