package com.inkbridge

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class UsbMirrorView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    private val player = UsbStreamService.videoPlayer
    private val inputArea = FrameLayout(context)
    private val video = SurfaceView(context)
    private val status = TextView(context)
    private var attachedSurface: Surface? = null
    private val stateListener: (UsbMirrorState) -> Unit = { state ->
        post { if (isAttachedToWindow) updateState(state) }
    }

    init {
        setBackgroundColor(Color.BLACK)
        inputArea.addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inputArea, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        status.setTextColor(Color.WHITE)
        status.setBackgroundColor(Color.BLACK)
        status.gravity = Gravity.CENTER
        status.textSize = 18f
        status.isClickable = false
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keepScreenOn = true
        isFocusable = true
        isFocusableInTouchMode = true
        UsbStreamService.updateView(inputArea)
        video.holder.addCallback(this)
        updateState(player.state)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player.onStateChanged = stateListener
        updateState(player.state)
    }

    override fun onDetachedFromWindow() {
        if (player.onStateChanged === stateListener) player.onStateChanged = null
        player.clearSurface(attachedSurface)
        attachedSurface = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateState(player.state)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachedSurface = holder.surface
        player.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player.clearSurface(attachedSurface)
        attachedSurface = null
    }

    private fun updateState(state: UsbMirrorState) {
        if (width > 0 && height > 0) {
            val viewport = VideoViewport.fit(width, height, state.width, state.height)
            val parameters = inputArea.layoutParams as LayoutParams
            if (parameters.width != viewport.width || parameters.height != viewport.height) {
                inputArea.layoutParams = LayoutParams(viewport.width, viewport.height, Gravity.CENTER)
            }
        }
        status.text = state.message
        status.visibility = if (state.message.isEmpty()) View.GONE else View.VISIBLE
    }
}
