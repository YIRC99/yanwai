package dev.jev.wechatmood.core

import kotlin.math.roundToInt

/** Stable colors across routes and messages; labels remain available without color perception. */
data class EmotionIndicator(val label: String, val probability: Double, val color: Int) {
    val percent: String get() = when {
        probability > 0 && probability < 0.005 -> "<1%"
        probability < 1 && probability >= 0.995 -> ">99%"
        else -> "${(probability * 100).roundToInt()}%"
    }

    companion object {
        val colors = linkedMapOf(
            "开心" to 0xFFF0CD68.toInt(), "平静" to 0xFF78CDB4.toInt(),
            "失落" to 0xFF83ADEF.toInt(), "委屈" to 0xFFD99BCB.toInt(),
            "生气" to 0xFFF28B82.toInt(), "缓和" to 0xFFB5CF80.toInt(),
            "焦虑" to 0xFFEFB17D.toInt(), "困惑" to 0xFFB69FE3.toInt(),
            "疲惫" to 0xFFA4B4C9.toInt(), "不明确" to 0xFFBDC3CA.toInt(),
        )

        fun from(emotions: Map<String, Double>): List<EmotionIndicator> =
            (colors.keys + emotions.keys).distinct().mapNotNull { label ->
                val probability = emotions[label]?.takeIf { it.isFinite() } ?: return@mapNotNull null
                EmotionIndicator(label, probability.coerceIn(0.0, 1.0), colors[label] ?: colors.getValue("不明确"))
            }
    }
}
