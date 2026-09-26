package dev.jev.wechatmood.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import dev.jev.wechatmood.core.Mood
import kotlin.math.roundToInt

object AnalysisCardText {
    fun format(mood: Mood, width: Int): CharSequence {
        if (mood.emotions.isEmpty()) return mood.detail
        val lines = mood.detail.lines()
        val text = SpannableStringBuilder(lines.first() + "\n\n")
        val top = mood.emotions.entries.sortedByDescending { it.value }.take(3)
        top.forEach { (label, probability) ->
            val start = text.length
            text.append("$label ${(probability * 100).roundToInt()}%")
            text.setSpan(ProbabilityBar(label, probability, width), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n")
        }
        val rest = ((1 - top.sumOf { it.value }) * 100).roundToInt().coerceAtLeast(0)
        if (rest > 0) text.append("其他情绪合计 $rest%\n")
        // The first two lines are the stable Jev header and complete probability summary.
        val details = lines.drop(2).joinToString("\n").trim()
        if (details.isNotBlank()) text.append("\n").append(details)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, lines.first().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (label in listOf("意图解析：", "可能在意：", "情绪倾向：", "意图：", "事件：", "建议：")) {
            val start = text.indexOf(label)
            if (start >= 0) {
                text.setSpan(StyleSpan(Typeface.BOLD), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(0xFFB2E3D5.toInt()), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }

    private class ProbabilityBar(val label: String, val probability: Double, val width: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return width.coerceAtLeast(1)
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = Paint(paint)
            val percent = "${(probability * 100).roundToInt()}%"
            canvas.drawText(label, x, y.toFloat(), p)
            canvas.drawText(percent, x + width - p.measureText(percent), y.toFloat(), p)
            val left = x + p.measureText("不明确") + p.textSize
            val right = x + width - p.measureText("100%") - p.textSize
            if (right <= left) return // Large accessibility fonts keep the readable labels and percentages.
            val h = p.textSize * 0.42f
            val rect = RectF(left, y - p.textSize * 0.55f, right, y - p.textSize * 0.55f + h)
            p.color = 0x386F8F89
            canvas.drawRoundRect(rect, h / 2, h / 2, p)
            rect.right = left + (right - left) * probability.toFloat().coerceIn(0f, 1f)
            p.color = 0xFF9BDAC9.toInt()
            canvas.drawRoundRect(rect, h / 2, h / 2, p)
        }
    }
}
