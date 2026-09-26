package dev.jev.wechatmood.hook

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import dev.jev.wechatmood.core.Mood
import dev.jev.wechatmood.core.EmotionIndicator

object AnalysisCardText {
    fun format(mood: Mood, density: Float, availableWidth: Int, paint: android.graphics.Paint): CharSequence {
        val emotions = EmotionIndicator.from(mood.emotions)
        val lines = mood.detail.lines().map(String::trim).filter(String::isNotEmpty)
            .filterIndexed { index, line ->
                !(index == 1 && line.startsWith("情绪：") && mood.emotions.isNotEmpty() && emotions.isEmpty())
            }
        val text = SpannableStringBuilder()
        if (lines.isEmpty()) return text
        lines.forEachIndexed { index, line ->
            if (index > 0) text.append("\n")
            if (index == 1 && line.startsWith("情绪：") && emotions.isNotEmpty()) {
                emotions.forEachIndexed { i, emotion ->
                    if (i > 0) text.append(" ")
                    val start = text.length
                    text.append("${emotion.label} ${emotion.percent}")
                    val span = EmotionRingSpan(emotion, density)
                    // Preserve readable text for very narrow rows or large accessibility fonts.
                    if (span.getSize(paint, text, start, text.length, null) <= availableWidth)
                        text.setSpan(span, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else text.append(line)
        }
        text.setSpan(StyleSpan(Typeface.BOLD), 0, lines.first().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (label in listOf("智能分析", "意图解析：", "可能在意：", "情绪倾向：", "意图：", "事件：", "建议：")) {
            val start = text.indexOf(label)
            if (start >= 0) {
                text.setSpan(StyleSpan(Typeface.BOLD), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(0xFFB2E3D5.toInt()), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }

}
