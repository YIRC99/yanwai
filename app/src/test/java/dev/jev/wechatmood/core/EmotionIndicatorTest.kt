package dev.jev.wechatmood.core

import dev.jev.wechatmood.analysis.JevProtocol
import org.junit.Assert.*
import org.junit.Test

class EmotionIndicatorTest {
    @Test fun `nonzero emotions retain stable colors and zero probabilities take no space`() {
        val values = JevProtocol.emotions.values.associateWith { 0.1 }
        val indicators = EmotionIndicator.from(values)
        assertEquals(values.keys.toSet(), indicators.map { it.label }.toSet())
        assertEquals(indicators.size, indicators.map { it.color }.toSet().size)
        assertEquals(indicators, EmotionIndicator.from(values.entries.reversed().associate { it.toPair() }))
        val single = EmotionIndicator.from(values.mapValues { if (it.key == "开心") 1.0 else 0.0 })
        assertEquals(listOf("开心"), single.map { it.label })
        assertEquals("100%", single.single().percent)
        assertTrue(EmotionIndicator.from(values.mapValues { 0.0 }).isEmpty())
    }

    @Test fun `rounding does not turn near zero and near certainty into absolutes`() {
        val indicators = EmotionIndicator.from(mapOf("开心" to 0.002, "平静" to 0.998))
        assertEquals(listOf("<1%", ">99%"), indicators.map { it.percent })
        assertEquals(listOf(0.002, 0.998), indicators.map { it.probability })
    }

    @Test fun `unexpected values cannot create invalid arcs and unknown labels remain readable`() {
        val indicators = EmotionIndicator.from(mapOf("开心" to Double.NaN, "平静" to -0.1,
            "生气" to 2.0, "新情绪" to 0.4))
        assertFalse(indicators.any { it.label == "开心" })
        assertFalse(indicators.any { it.label == "平静" })
        assertEquals(1.0, indicators.single { it.label == "生气" }.probability, 0.0)
        assertEquals("40%", indicators.single { it.label == "新情绪" }.percent)
    }
}
