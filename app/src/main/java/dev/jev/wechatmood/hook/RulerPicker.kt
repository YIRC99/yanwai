package dev.jev.wechatmood.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 横向刻度选择器：左右滑动换档，中间那条线是当前值。
 *
 * 用它替掉系统的 DatePicker / TimePicker：那套控件在深色面板里配色对不上，
 * 日历加表盘还有半屏多高，塞进底部面板后下半截直接被屏幕切掉，手指够不到。
 * 这里只画刻度和文字，颜色全走 [ReplyTheme]，高度固定，滑动和外层滚动互不抢。
 */
internal class RulerPicker(
    context: Context,
    private val theme: ReplyTheme,
    private val min: Int,
    private val max: Int,
    private val step: Int,
    value: Int,
    private val spacing: Int,
    private val label: (Int) -> String,
    private val onChanged: (Int) -> Unit,
) : View(context) {
    private val count = ((max - min) / step) + 1
    private var position = ((value - min) / step).toFloat().coerceIn(0f, (count - 1).toFloat())
    private var lastX = 0f

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.border
        strokeWidth = theme.dp(1).coerceAtLeast(1).toFloat()
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accent
        strokeWidth = theme.dp(2).coerceAtLeast(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = theme.dp(13).toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        contentDescription = "左右滑动选择"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), theme.dp(82))
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val centerX = width / 2f
        val tickTop = height * 0.50f
        val tickBottom = height * 0.74f
        val textBaseline = height * 0.36f
        val selected = position.roundToInt()
        val first = floor(position).toInt() - 8
        val last = ceil(position).toInt() + 8
        for (index in first..last) {
            if (index < 0 || index >= count) continue
            val x = centerX + (index - position) * spacing
            if (x < -spacing || x > width + spacing) continue
            val major = index == selected
            canvas.drawLine(x, if (major) tickTop - theme.dp(6) else tickTop, x, tickBottom,
                if (major) pointerPaint else tickPaint)
            // 只画中心附近几个文字，别让刻度挤成一片。
            if (abs(index - position) <= 2.4f) {
                textPaint.color = if (major) theme.accent else theme.muted
                canvas.drawText(label(min + index * step), x, textBaseline, textPaint)
            }
        }
        canvas.drawLine(centerX, tickTop - theme.dp(10), centerX, tickBottom + theme.dp(6), pointerPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (event.x - lastX) / spacing
                lastX = event.x
                moveBy(delta)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                snap()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveBy(delta: Float) {
        val next = (position - delta).coerceIn(0f, (count - 1).toFloat())
        if (next == position) return
        val before = position.roundToInt()
        position = next
        val after = position.roundToInt()
        if (after != before) onChanged(min + after * step)
        invalidate()
    }

    private fun snap() {
        val target = position.roundToInt()
        if (target.toFloat() != position) {
            position = target.toFloat()
            invalidate()
        }
        onChanged(min + target * step)
    }
}
