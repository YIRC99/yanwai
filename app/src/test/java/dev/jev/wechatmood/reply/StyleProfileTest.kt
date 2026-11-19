package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleProfileTest {
    private fun mine(text: String, id: Long = 0) = ReplyMessage(id, "我", 0, text)
    private fun theirs(text: String, id: Long = 0) = ReplyMessage(id, "对方", 0, text)

    @Test fun `too few messages produce no profile`() {
        assertNull(StyleAnalyzer.analyze((1..7).map { mine("好") }))
    }

    @Test fun `only my own messages are counted`() {
        val messages = (1..10).map { mine("行") } + (1..30).map { theirs("这是一条很长很长的对方消息，用来说明统计口径") }
        val profile = StyleAnalyzer.analyze(messages)
        assertNotNull("需要产出画像", profile)
        assertEquals(10, profile!!.messageCount)
    }

    @Test fun `short replies without periods are described as short`() {
        val texts = listOf("嗯嗯", "好的", "行", "可以", "晚安", "哈哈哈", "收到", "好呀", "嗯哼", "那我先睡了")
        val profile = StyleAnalyzer.analyze(texts.map { mine(it) })!!
        assertTrue(profile.medianLength <= 6)
        assertEquals(0.0, profile.periodRate, 0.001)
        val summary = profile.summary()
        assertTrue(summary.contains("个字"))
        assertTrue(summary.contains("句号"))
        assertTrue(profile.particles.isEmpty() || profile.particles.all { it in texts.joinToString("") })
    }

    @Test fun `particles need repetition before they count`() {
        val base = listOf("好的", "行", "可以", "收到", "晚安", "在", "忙", "等下")
        val profile = StyleAnalyzer.analyze((base + "嗯嗯嗯").map { mine(it) })!!
        assertTrue(profile.particles.contains("嗯"))
        val noParticles = StyleAnalyzer.analyze(base.map { mine(it) })!!
        assertTrue(noParticles.particles.isEmpty())
    }

    @Test fun `question and exclamation rates follow the text`() {
        val texts = listOf("真的吗？", "去哪", "几点？", "好吧！", "行", "可以", "好的", "嗯")
        val profile = StyleAnalyzer.analyze(texts.map { mine(it) })!!
        assertEquals(0.25, profile.questionRate, 0.001)
        assertEquals(0.125, profile.exclaimRate, 0.001)
    }

    @Test fun `quoted placeholders are not treated as my style`() {
        val texts = (1..8).map { mine("行") } + mine("【引用张三：晚上吃什么】随便")
        assertEquals(8, StyleAnalyzer.analyze(texts)!!.messageCount)
    }

    @Test fun `samples stay short and avoid links`() {
        val texts = listOf("行", "好", "可以", "嗯", "晚安", "收到", "好的", "没问题", "https://example.com/very/long/path")
        val profile = StyleAnalyzer.analyze(texts.map { mine(it) })!!
        assertTrue(profile.samples.none { it.contains("http") })
        assertTrue(profile.samples.all { it.length <= 24 })
    }
}
