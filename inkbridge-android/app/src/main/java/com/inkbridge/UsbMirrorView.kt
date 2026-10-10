package com.inkbridge

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    private val transform = VideoTransform()
    private var mirrored = false
    private var mirrorGestures = false
    private var ignoreFingers = false
    private var gestureActive = false
    private var hasGestureFocus = false
    private var focusX = 0f
    private var focusY = 0f
    private var scaleFactor = 1f
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = detector.scaleFactor
                return true
            }
        }
    ).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            mirrorGestures = mirrored
            ignoreFingers = false
        }
        if (!mirrorGestures) return super.dispatchTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)

        var stylusIndex = -1
        for (index in 0 until event.pointerCount) {
            val tool = event.getToolType(index)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                stylusIndex = index
                break
            }
        }
        if (stylusIndex >= 0) {
            ignoreFingers = true
            if (gestureActive) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                scaleDetector.onTouchEvent(cancel)
                cancel.recycle()
                gestureActive = false
                hasGestureFocus = false
            }
            val stylus = stylusEvent(event, stylusIndex)
            super.dispatchTouchEvent(stylus)
            stylus.recycle()
        } else if (!ignoreFingers) {
            scaleFactor = 1f
            scaleDetector.onTouchEvent(event)
            gestureActive = true
            if (event.pointerCount == 2 && event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                val x = scaleDetector.focusX
                val y = scaleDetector.focusY
                if (hasGestureFocus && event.actionMasked == MotionEvent.ACTION_MOVE) {
                    transform.transform(scaleFactor, focusX, focusY, x, y)
                    applyTransform()
                }
                focusX = x
                focusY = y
                hasGestureFocus = true
            } else {
                hasGestureFocus = false
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureActive = false
            hasGestureFocus = false
            ignoreFingers = false
        }
        return true
    }

    private fun stylusEvent(event: MotionEvent, index: Int): MotionEvent {
        if (event.pointerCount == 1) return MotionEvent.obtain(event)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.actionIndex == index) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == index) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            else -> event.actionMasked
        }
        val properties = MotionEvent.PointerProperties()
        val coordinates = MotionEvent.PointerCoords()
        event.getPointerProperties(index, properties)
        val history = event.historySize
        if (history > 0) event.getHistoricalPointerCoords(index, 0, coordinates)
        else event.getPointerCoords(index, coordinates)
        val stylus = MotionEvent.obtain(event.downTime,
            if (history > 0) event.getHistoricalEventTime(0) else event.eventTime,
            action, 1, arrayOf(properties), arrayOf(coordinates), event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        for (sample in 1..history) {
            if (sample < history) event.getHistoricalPointerCoords(index, sample, coordinates)
            else event.getPointerCoords(index, coordinates)
            stylus.addBatch(if (sample < history) event.getHistoricalEventTime(sample) else event.eventTime,
                arrayOf(coordinates), event.metaState)
        }
        return stylus
    }

    private fun applyTransform() {
        inputArea.scaleX = transform.scale
        inputArea.scaleY = transform.scale
        inputArea.translationX = transform.offsetX
        inputArea.translationY = transform.offsetY
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

    internal fun updateState(state: UsbMirrorState) {
        mirrored = state.width > 0 && state.height > 0
        if (!mirrored) ignoreFingers = true
        if (width > 0 && height > 0) {
            transform.resize(width, height, state.width, state.height)
            val viewport = transform.viewport
            val parameters = inputArea.layoutParams as LayoutParams
            if (parameters.width != viewport.width || parameters.height != viewport.height) {
                inputArea.layoutParams = LayoutParams(viewport.width, viewport.height, Gravity.CENTER)
            }
            applyTransform()
        }
        status.text = state.message
        status.visibility = if (state.message.isEmpty()) View.GONE else View.VISIBLE
    }
}
