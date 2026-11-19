package dev.jev.wechatmood.hook

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import dev.jev.wechatmood.core.Mood
import dev.jev.wechatmood.core.EmotionIndicator
import dev.jev.wechatmood.core.CardDisplaySettings

object AnalysisCardText {
    fun format(mood: Mood, density: Float, availableWidth: Int, paint: android.graphics.Paint,
        display: CardDisplaySettings = CardDisplaySettings()): CharSequence {
        val emotions = EmotionIndicator.from(mood.emotions)
        val lines = AnalysisCardContent.lines(mood, display)
        val text = SpannableStringBuilder()
        if (lines.isEmpty()) return text
        lines.forEachIndexed { index, line ->
            if (index > 0) text.append("\n")
            val lineStart = text.length
            if (line.emotion && emotions.isNotEmpty()) {
                emotions.forEachIndexed { i, emotion ->
                    if (i > 0) text.append(" ")
                    val start = text.length
                    text.append("${emotion.label} ${emotion.percent}")
                    val span = EmotionRingSpan(emotion, density)
                    // Preserve readable text for very narrow rows or large accessibility fonts.
                    if (span.getSize(paint, text, start, text.length, null) <= availableWidth)
                        text.setSpan(span, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else text.append(line.text)
            if (line.header) text.setSpan(StyleSpan(Typeface.BOLD), lineStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.label?.let { label ->
                text.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineStart + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(0xFFB2E3D5.toInt()), lineStart, lineStart + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }

}
