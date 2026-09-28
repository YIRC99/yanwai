package dev.jev.wechatmood.hook

import dev.jev.wechatmood.analysis.JevProtocol
import dev.jev.wechatmood.core.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisCardContentTest {
    private val full = Mood("智能分析", 0.0, 0, "", "${JevProtocol.header}\n情绪：平静 80%\n智能分析\n意图解析：说明安排\n可能在意：时间\n情绪倾向：平和\n仅供参考",
        emotions = mapOf("平静" to 0.8))

    @Test fun `all switch combinations filter presentation only and reopening restores cached fields`() {
        val payload = CachedAnalysisResult(full, "evidence").encode()
        val restored = CachedAnalysisResult.decode(payload).mood
        for (intent in listOf(false, true)) for (concern in listOf(false, true)) for (tone in listOf(false, true)) {
            val lines = AnalysisCardContent.lines(restored, CardDisplaySettings(intent, concern, tone))
            val text = lines.joinToString("\n") { it.text }
            assertEquals(intent, text.contains("意图解析："))
            assertEquals(concern, text.contains("可能在意："))
            assertEquals(tone, text.contains("情绪倾向："))
            assertEquals(intent || concern || tone, lines.any { it.text == "智能分析" })
            assertEquals(intent || concern || tone, text.contains("仅供参考"))
            assertEquals(listOf("情绪：平静 80%"), lines.filter { it.emotion }.map { it.text })
            assertFalse(text.contains("\n\n"))
        }
        assertEquals(full.detail, AnalysisCardContent.lines(restored, CardDisplaySettings()).joinToString("\n") { it.text })
        assertEquals(payload, CachedAnalysisResult(restored, "evidence").encode())
        assertEquals(full.detail, restored.detail)
    }

    @Test fun `hidden sections leave no empty card and emotion recognition does not use line numbers`() {
        val off = CardDisplaySettings(false, false, false)
        val onlySections = full.copy(detail = full.detail.replace("情绪：平静 80%\n", ""), emotions = emptyMap())
        assertTrue(AnalysisCardContent.lines(onlySections, off).isEmpty())
        val reordered = full.copy(detail = "意图解析：说明安排\n情绪：平静 80%\n情绪倾向：平和")
        val lines = AnalysisCardContent.lines(reordered, off)
        assertEquals(1, lines.size)
        assertTrue(lines.single().emotion)
        assertFalse(lines.single().header)
    }

    @Test fun `JEV lines loading failure retry and coverage are preserved`() {
        val off = CardDisplaySettings(false, false, false)
        for (body in listOf("意图：询问\n事件：确认时间\n建议：问清楚", "智能分析中…",
            "智能分析失败，已保留情绪。点击卡片重试。", "分析失败：连接失败\n点击此卡重试", "部分语音缺失")) {
            val mood = full.copy(detail = "${JevProtocol.header}\n$body", intentFailed = true, emotions = emptyMap())
            assertEquals(mood.detail, AnalysisCardContent.lines(mood, off).joinToString("\n") { it.text })
        }
    }

    @Test fun `transcript lines resembling analysis labels remain original evidence`() {
        val transcript = "语音转写：请记下这些标题\n意图解析：这句是原话\n可能在意：同样是原话\n情绪倾向：也保留\n情绪：开心 100%\n（仅根据转写文字分析）"
        val mood = full.copy(detail = full.detail + "\n\n" + transcript)
        val lines = AnalysisCardContent.lines(mood, CardDisplaySettings(false, false, false))
        assertTrue(lines.joinToString("\n") { it.text }.endsWith(transcript))
        assertEquals(1, lines.count { it.emotion })
        assertTrue(lines.dropWhile { !it.text.startsWith("语音转写：") }.all { it.label == null && !it.emotion && !it.header })
    }
}
