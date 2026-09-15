/*
 * Copyright (C) 2026 Daniel Georg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.car.win98

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes

/**
 * Windows 98 window chrome: caption, 3-D frame, bottom resize bar.
 *
 * The view is the full chrome surface (title + side frames + bottom bar). The task sits in
 * the hole in the middle at a higher layer, so client-area touches go to the app. Title
 * drags; the frame and bottom bar resize. During a resize the controller raises this
 * surface over the task and calls [setResizePreview], so the rubber-band shim can be drawn
 * without moving the task.
 *
 * In chip mode the chrome collapses to a taskbar button: caption only, tap restores.
 */
class Win98TitleBarView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defaultStyleAttribute: Int = 0,
) : FrameLayout(context, attributeSet, defaultStyleAttribute) {

    interface Listener {
        fun onStripTouch(event: MotionEvent)
        /** [directionX] / [directionY]: -1 left/top edge, +1 right/bottom edge, 0 untouched. */
        fun onResizeTouch(event: MotionEvent, directionX: Int, directionY: Int)
        fun onMinimize()
        fun onMaximizeToggle()
        fun onClose()
        fun onChipTap()
    }

    var listener: Listener? = null

    private val resources = context.resources
    private val titleHeight = resources.getDimensionPixelSize(R.dimen.win98_title_bar_height)
    private val frameWidth = resources.getDimensionPixelSize(R.dimen.win98_frame_width)
    private val resizeBarHeight = resources.getDimensionPixelSize(R.dimen.win98_resize_bar_height)
    private val cornerHitSize = resources.getDimensionPixelSize(R.dimen.win98_resize_corner)
    private val buttonWidth = resources.getDimensionPixelSize(R.dimen.win98_button_width)
    private val buttonHeight = resources.getDimensionPixelSize(R.dimen.win98_button_height)
    private val titlePadding = resources.getDimensionPixelSize(R.dimen.win98_title_padding)

    private val activeStart = resources.getColor(R.color.win98_title_active_start, null)
    private val activeEnd = resources.getColor(R.color.win98_title_active_end, null)
    private val inactiveStart = resources.getColor(R.color.win98_title_inactive_start, null)
    private val inactiveEnd = resources.getColor(R.color.win98_title_inactive_end, null)

    private val captionPaint = Paint()
    private val shimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.win98_shim, null)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val shimFill = Paint().apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }
    // Built once: onDraw runs on every MOVE frame of a drag or resize.
    private val gripShadow = Paint().apply {
        color = resources.getColor(R.color.win98_shadow, null)
        strokeWidth = 1.5f
    }
    private val gripLight = Paint().apply {
        color = resources.getColor(R.color.win98_light, null)
        strokeWidth = 1.5f
    }
    private val clientHole = Rect()

    private var active = true
    private var chipMode = false
    private var fullscreen = false
    private var wrapChrome = true

    // When null, the window is the whole view. During resize the controller sets both.
    private var windowInView: Rect? = null
    private var shimInView: Rect? = null

    private enum class Gesture { NONE, DRAG, RESIZE }
    private var gesture = Gesture.NONE
    private var resizeDirectionX = 0
    private var resizeDirectionY = 0

    private val title = TextView(context).apply {
        setTextColor(resources.getColor(R.color.win98_title_text, null))
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.win98_title_text_size))
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        text = resources.getString(R.string.win98_untitled)
    }

    private val minimizeButton = Win98Button(context, R.drawable.win98_glyph_minimize).apply {
        contentDescription = resources.getString(R.string.win98_minimize)
        setOnClickListener { listener?.onMinimize() }
    }
    private val maximizeButton = Win98Button(context, R.drawable.win98_glyph_maximize).apply {
        contentDescription = resources.getString(R.string.win98_maximize)
        setOnClickListener { listener?.onMaximizeToggle() }
    }
    private val closeButton = Win98Button(context, R.drawable.win98_glyph_close).apply {
        contentDescription = resources.getString(R.string.win98_close)
        setOnClickListener { listener?.onClose() }
    }

    private val buttonGap = resources.getDimensionPixelSize(R.dimen.win98_button_gap)
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = false
        isFocusable = false
        for ((index, button) in listOf(minimizeButton, maximizeButton, closeButton).withIndex()) {
            val params = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            if (index == 2) params.marginStart = buttonGap
            addView(button, params)
        }
    }
    private val buttonsWidth = 3 * buttonWidth + buttonGap

    private val tapDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (chipMode) listener?.onChipTap()
                return true
            }
        })

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = false
        title.isClickable = false
        title.isFocusable = false
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, titleHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = frameWidth + titlePadding
            marginEnd = buttonsWidth + frameWidth + titlePadding
        })
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (titleHeight - buttonHeight) / 2
            marginEnd = frameWidth + titlePadding
        })
        buttons.bringToFront()
    }

    /**
     * Buttons are children and get the event first (ViewGroup dispatch). Everything else,
     * caption drag and frame resize, lands here. Do not put an OnTouchListener on this view: it
     * would run in View.dispatchTouchEvent and can swallow the children.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (chipMode) {
            tapDetector.onTouchEvent(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitArea = hitTest(event.x, event.y)
                if (hitArea == HitArea.TITLE) {
                    gesture = Gesture.DRAG
                    listener?.onStripTouch(event)
                } else {
                    gesture = Gesture.RESIZE
                    resizeDirectionX = hitArea.directionX
                    resizeDirectionY = hitArea.directionY
                    listener?.onResizeTouch(event, resizeDirectionX, resizeDirectionY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.DRAG) listener?.onStripTouch(event)
                else if (gesture == Gesture.RESIZE) {
                    listener?.onResizeTouch(event, resizeDirectionX, resizeDirectionY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.DRAG) listener?.onStripTouch(event)
                else if (gesture == Gesture.RESIZE) {
                    listener?.onResizeTouch(event, resizeDirectionX, resizeDirectionY)
                }
                gesture = Gesture.NONE
            }
        }
        return true
    }

    /** Where a touch landed on the chrome, with the resize direction that part of it drives. */
    private enum class HitArea(val directionX: Int, val directionY: Int) {
        TITLE(0, 0),
        LEFT_EDGE(-1, 0),
        RIGHT_EDGE(1, 0),
        BOTTOM_EDGE(0, 1),
        BOTTOM_LEFT_CORNER(-1, 1),
        BOTTOM_RIGHT_CORNER(1, 1),
    }

    private fun hitTest(x: Float, y: Float): HitArea {
        val window = windowRect()
        if (y < window.top + titleHeight) return HitArea.TITLE
        val onBottom = y >= window.bottom - resizeBarHeight
        val onLeft = x < window.left + cornerHitSize
        val onRight = x > window.right - cornerHitSize
        return when {
            onBottom && onLeft -> HitArea.BOTTOM_LEFT_CORNER
            onBottom && onRight -> HitArea.BOTTOM_RIGHT_CORNER
            onBottom -> HitArea.BOTTOM_EDGE
            x < window.left + frameWidth -> HitArea.LEFT_EDGE
            x > window.right - frameWidth -> HitArea.RIGHT_EDGE
            else -> HitArea.TITLE
        }
    }

    private fun windowRect(): Rect = windowInView ?: Rect(0, 0, width, height)

    fun setTitle(text: CharSequence?) {
        title.text = text ?: resources.getString(R.string.win98_untitled)
    }

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        rebuildGradient()
        invalidate()
    }

    fun setFullscreen(isFullscreen: Boolean) {
        if (fullscreen == isFullscreen) return
        fullscreen = isFullscreen
        maximizeButton.setImageResource(
            if (fullscreen) R.drawable.win98_glyph_restore else R.drawable.win98_glyph_maximize)
        maximizeButton.contentDescription = resources.getString(
            if (fullscreen) R.string.win98_restore else R.string.win98_maximize)
    }

    /** When false the view is a title strip only (fullscreen / chip). */
    fun setWrapChrome(wrap: Boolean) {
        if (wrapChrome == wrap) return
        wrapChrome = wrap
        requestLayout()
        invalidate()
    }

    fun setChipMode(isChip: Boolean) {
        if (chipMode == isChip) return
        chipMode = isChip
        buttons.visibility = if (isChip) GONE else VISIBLE
        title.setTextColor(resources.getColor(
            if (isChip) R.color.win98_glyph else R.color.win98_title_text, null))
        requestLayout()
        invalidate()
    }

    /**
     * Rubber-band mode: [window] is the original chrome in this view's coordinates,
     * [shim] is the proposed new chrome. Pass nulls to leave.
     */
    fun setResizePreview(window: Rect?, shim: Rect?) {
        windowInView = window?.let { Rect(it) }
        shimInView = shim?.let { Rect(it) }
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(newWidth: Int, newHeight: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(newWidth, newHeight, oldWidth, oldHeight)
        rebuildGradient()
    }

    private fun rebuildGradient() {
        val window = windowRect()
        if (window.width() <= 0) return
        val (startColor, endColor) =
            if (active) activeStart to activeEnd else inactiveStart to inactiveEnd
        captionPaint.shader = LinearGradient(
            window.left.toFloat(), 0f, window.right.toFloat(), 0f,
            startColor, endColor, Shader.TileMode.CLAMP)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val window = windowRect()
        val stripTop = window.top
        val stripBottom = window.top + titleHeight
        val inset = (if (wrapChrome && !chipMode) frameWidth else 0) + titlePadding
        val reservedRight = if (chipMode) inset else buttonsWidth + inset
        title.measure(
            MeasureSpec.makeMeasureSpec(
                maxOf(0, window.width() - inset - reservedRight), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(titleHeight, MeasureSpec.EXACTLY),
        )
        title.layout(window.left + inset, stripTop, window.right - reservedRight, stripBottom)
        if (!chipMode) {
            buttons.measure(
                MeasureSpec.makeMeasureSpec(buttonsWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(buttonHeight, MeasureSpec.EXACTLY),
            )
            val buttonsTop = stripTop + (titleHeight - buttonHeight) / 2
            buttons.layout(
                window.right - buttonsWidth - inset,
                buttonsTop,
                window.right - inset,
                buttonsTop + buttonHeight,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val window = windowRect()
        if (chipMode) {
            Bevel.drawRaised(canvas, window, context)
        } else {
            if (wrapChrome) {
                // Leave the client area unpainted: the task normally covers it, but during a
                // resize the chrome is raised above the task and an opaque fill would turn the
                // whole app into a grey slab under the shim.
                clientHole.set(
                    window.left + frameWidth,
                    window.top + titleHeight,
                    window.right - frameWidth,
                    window.bottom - resizeBarHeight,
                )
                canvas.save()
                canvas.clipOutRect(clientHole)
                Bevel.drawRaised(canvas, window, context)
                canvas.restore()
            }
            val bevelInset = if (wrapChrome) 2f else 0f
            canvas.drawRect(
                window.left + bevelInset,
                window.top + bevelInset,
                window.right - bevelInset,
                (window.top + titleHeight).toFloat(),
                captionPaint,
            )
            if (wrapChrome) {
                drawSizeGrip(canvas, window)
            }
        }
        super.onDraw(canvas)
        val shim = shimInView
        if (shim != null && !shim.isEmpty) {
            canvas.drawRect(
                shim.left.toFloat(), shim.top.toFloat(),
                shim.right.toFloat(), shim.bottom.toFloat(), shimFill)
            canvas.drawRect(
                shim.left + 1f, shim.top + 1f,
                shim.right - 1f, shim.bottom - 1f, shimStroke)
        }
    }

    /** The diagonal hatching in the bottom-right corner that marks the resize handle. */
    private fun drawSizeGrip(canvas: Canvas, window: Rect) {
        val gripSize = resizeBarHeight * 0.7f
        val lineStep = gripSize / 4f
        val right = window.right - 4f
        val bottom = window.bottom - 4f
        for (line in 0..2) {
            val offset = line * lineStep
            canvas.drawLine(
                right - gripSize + offset, bottom, right, bottom - gripSize + offset, gripShadow)
            canvas.drawLine(
                right - gripSize + offset + 1f, bottom,
                right, bottom - gripSize + offset + 1f, gripLight)
        }
    }

    /**
     * A caption button. The bevel is a state-list drawable and the glyph a vector drawable, both
     * RRO-overridable. The glyph shifts one pixel while pressed, as Windows 98 buttons do; the
     * background does not, so the shift is applied to the image alone rather than to the view.
     */
    private class Win98Button(context: Context, @DrawableRes glyph: Int) : ImageButton(context) {
        init {
            setBackgroundResource(R.drawable.win98_button_background)
            setImageResource(glyph)
            scaleType = ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            if (isPressed) canvas.translate(1f, 1f)
            super.onDraw(canvas)
            canvas.restore()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
    }

    /** The raised 3-D edge around the frame and the chip. */
    private object Bevel {
        // Colours are static resources; resolve them once rather than per frame.
        private var face: Paint? = null
        private var light: Paint? = null
        private var shadow: Paint? = null
        private var dark: Paint? = null

        private fun ensure(context: Context) {
            if (face != null) return
            val resources = context.resources
            face = paint(resources.getColor(R.color.win98_face, null))
            light = paint(resources.getColor(R.color.win98_light, null))
            shadow = paint(resources.getColor(R.color.win98_shadow, null))
            dark = paint(resources.getColor(R.color.win98_dark_shadow, null))
        }

        fun drawRaised(canvas: Canvas, rect: Rect, context: Context) {
            ensure(context)
            val left = rect.left.toFloat()
            val top = rect.top.toFloat()
            val right = rect.right.toFloat()
            val bottom = rect.bottom.toFloat()
            canvas.drawRect(left, top, right, bottom, face!!)
            canvas.drawRect(left, top, right - 1, top + 1, light!!)
            canvas.drawRect(left, top, left + 1, bottom - 1, light!!)
            canvas.drawRect(left, bottom - 1, right, bottom, dark!!)
            canvas.drawRect(right - 1, top, right, bottom, dark!!)
            canvas.drawRect(left + 1, bottom - 2, right - 1, bottom - 1, shadow!!)
            canvas.drawRect(right - 2, top + 1, right - 1, bottom - 1, shadow!!)
        }

        private fun paint(color: Int) = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
        }
    }
}
