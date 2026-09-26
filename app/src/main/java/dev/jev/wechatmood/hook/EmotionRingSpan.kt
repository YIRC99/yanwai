package dev.jev.wechatmood.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import dev.jev.wechatmood.core.EmotionIndicator
import kotlin.math.ceil
import kotlin.math.max

/** One atomic inline chip, so Android can wrap between emotions, never inside a ring. */
class EmotionRingSpan(private val emotion: EmotionIndicator, private val density: Float) : ReplacementSpan() {
    private fun labelPaint(paint: Paint) = Paint(paint).apply { textSize *= 0.8f; color = emotion.color }
    private fun numberPaint(paint: Paint) = Paint(paint).apply { textSize *= 0.65f; color = 0xFFF0F1F5.toInt() }
    private fun diameter(paint: Paint) = max(24f * density, numberPaint(paint).measureText("100%") + 6f * density)
    private fun height(paint: Paint) = max(diameter(paint), paint.fontMetrics.descent - paint.fontMetrics.ascent) + 4f * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val height = height(paint)
        val center = (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        fm?.apply {
            ascent = kotlin.math.floor(center - height / 2f).toInt()
            descent = ceil(center + height / 2f).toInt()
            top = ascent; bottom = descent; leading = 0
        }
        return ceil(diameter(paint) + 3f * density + labelPaint(paint).measureText(emotion.label)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint) {
        val diameter = diameter(paint)
        val centerY = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val centerX = x + diameter / 2f
        val stroke = 2f * density
        val rect = RectF(x + stroke / 2, centerY - diameter / 2 + stroke / 2,
            x + diameter - stroke / 2, centerY + diameter / 2 - stroke / 2)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x446E7B89
        }
        canvas.drawOval(rect, ring)
        ring.color = emotion.color
        // No animation: this is a model probability, not elapsed analysis progress.
        if (emotion.probability > 0) canvas.drawArc(rect, -90f, (emotion.probability * 360).toFloat(), false, ring)
        val number = numberPaint(paint)
        canvas.drawText(emotion.percent, centerX - number.measureText(emotion.percent) / 2,
            centerY - (number.fontMetrics.ascent + number.fontMetrics.descent) / 2, number)
        val label = labelPaint(paint)
        canvas.drawText(emotion.label, x + diameter + 3f * density,
            centerY - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2, label)
    }
}
