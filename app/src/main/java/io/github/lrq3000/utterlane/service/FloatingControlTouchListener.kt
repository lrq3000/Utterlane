package io.github.lrq3000.utterlane.service

import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One gesture owns its classification until all pointers leave, including zero-span pinches. */
internal class FloatingControlTouchListener(
    private val touchSlop: Int,
    private val position: () -> Pair<Int, Int>,
    private val diameterDp: () -> Float,
    private val isRtl: () -> Boolean,
    private val onMove: (Int, Int) -> Unit,
    private val onResize: (Int) -> Unit,
    private val onFinish: (resized: Boolean) -> Unit
) : View.OnTouchListener {
    private var active = false
    private var dragged = false
    private var multiple = false
    private var resized = false
    private var downX = 0f
    private var downY = 0f
    private var start = 0 to 0
    private var horizontalDirection = 1
    private var pinchFirst = -1
    private var pinchSecond = -1
    private var pinchSpan = 0f
    private var pinchSize = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            active = true
            dragged = false
            multiple = false
            resized = false
            pinchFirst = -1
            pinchSecond = -1
            downX = event.rawX
            downY = event.rawY
            start = position()
            horizontalDirection = if (isRtl()) -1 else 1
            return true
        }
        if (!active) return true // CANCEL followed by a stray UP is never a click.

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiple = true
                if (pinchFirst == -1) beginPinch(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    multiple = true
                    if (pinchFirst == -1) beginPinch(event)
                    val span = span(event)
                    if (pinchSpan > 0f && span > 0f) {
                        onResize((pinchSize * span / pinchSpan).roundToInt())
                        resized = true
                    } else if (span > 0f) beginPinch(event)
                } else if (!multiple) move(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                multiple = true
                val lifted = event.getPointerId(event.actionIndex)
                if (lifted == pinchFirst || lifted == pinchSecond) beginPinch(event, event.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                if (!multiple) move(event)
                val tap = !multiple && !dragged
                cancel()
                if (tap) view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    /** Configuration changes can invalidate the raw-coordinate baseline mid-gesture. */
    fun cancel(persist: Boolean = true) {
        if (!active) return
        active = false
        if (persist && (dragged || resized)) onFinish(resized)
    }

    private fun move(event: MotionEvent) {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        // Android can batch an excursion and return in one MOVE. Inspect its history
        // too, so returning within slop never revives a tap already used for dragging.
        for (i in 0 until event.historySize) {
            val hx = event.getHistoricalX(i) + event.rawX - event.x - downX
            val hy = event.getHistoricalY(i) + event.rawY - event.y - downY
            if (hypot(hx, hy) > touchSlop) dragged = true
        }
        if (hypot(dx, dy) > touchSlop) dragged = true
        if (dragged) onMove(start.first + horizontalDirection * dx.roundToInt(), start.second + dy.roundToInt())
    }

    private fun beginPinch(event: MotionEvent, excludedIndex: Int = -1) {
        // IDs survive pointer-index reordering and a third finger replacing either
        // member of the pair. Rebase on replacement to avoid a sudden size jump.
        pinchFirst = -1
        pinchSecond = -1
        for (index in 0 until event.pointerCount) {
            if (index == excludedIndex) continue
            if (pinchFirst == -1) pinchFirst = event.getPointerId(index)
            else { pinchSecond = event.getPointerId(index); break }
        }
        if (pinchSecond == -1) pinchFirst = -1
        pinchSpan = span(event)
        pinchSize = diameterDp()
    }

    private fun span(event: MotionEvent): Float {
        val first = event.findPointerIndex(pinchFirst)
        val second = event.findPointerIndex(pinchSecond)
        if (first < 0 || second < 0) return 0f
        return hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
    }
}
