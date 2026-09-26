package dev.jev.wechatmood.hook

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import dev.jev.wechatmood.core.Mood

object AnalysisCardText {
    fun format(mood: Mood): CharSequence {
        // Both routes retain the same text probabilities, without separate chart rows.
        val lines = mood.detail.lines().map(String::trim).filter(String::isNotEmpty)
        val text = SpannableStringBuilder(lines.joinToString("\n"))
        if (lines.isEmpty()) return text
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
