package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.CardDisplaySettings
import dev.jev.wechatmood.core.EmotionIndicator
import dev.jev.wechatmood.core.Mood

/** Pure projection; Mood and its cached detail stay complete. Labels have semantic roles, not row numbers. */
object AnalysisCardContent {
    data class Line(val text: String, val header: Boolean = false, val emotion: Boolean = false,
        val label: String? = null, val readingDecoration: Boolean = false)
    fun lines(mood: Mood, display: CardDisplaySettings): List<Line> {
        val sections = listOf("意图解析：" to display.intent, "可能在意：" to display.concern, "情绪倾向：" to display.tone)
        val original = mood.detail.lines().map(String::trim).filter(String::isNotEmpty)
        val analysisLines = original.takeWhile { !it.startsWith("语音转写：") }
        val hasReading = analysisLines.any { line -> line == "智能分析" || sections.any { line.startsWith(it.first) } }
        val noVisibleEmotions = mood.emotions.isNotEmpty() && EmotionIndicator.from(mood.emotions).isEmpty()
        var transcript = false
        val projected = original.mapNotNull { line ->
            if (line.startsWith("语音转写：")) transcript = true
            // The appended transcript is user evidence, not part of the analysis schema.
            if (transcript) return@mapNotNull Line(line)
            val section = sections.firstOrNull { line.startsWith(it.first) }
            if (section?.second == false || line.startsWith("情绪：") && noVisibleEmotions) return@mapNotNull null
            Line(line, line.matches(Regex("yanwai \\S+")), line.startsWith("情绪："),
                section?.first ?: listOf("智能分析", "意图：", "事件：", "建议：").firstOrNull(line::startsWith),
                hasReading && (line == "智能分析" || line == "仅供参考"))
        }
        val hasSections = projected.any { line -> sections.any { it.first == line.label } }
        val lines = projected.filterNot { !hasSections && it.readingDecoration }
        return if (lines.none { !it.header }) emptyList() else lines
    }
}
