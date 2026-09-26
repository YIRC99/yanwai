package dev.jev.wechatmood.core

data class ContextMessage(val speaker: String, val text: String, val createdAt: Long = 0, val messageId: Long = 0)

data class ContextCoverage(
    val source: String = "provided",
    val scanned: Int = 0,
    val omittedMedia: Int = 0,
    val unavailable: Int = 0,
    val omittedText: Int = 0,
    val invalidTime: Int = 0,
    val truncated: Boolean = false,
)

data class AnalysisInput(
    val text: String,
    val talker: String,
    val context: List<ContextMessage> = emptyList(),
    val messageId: Long = 0,
    val speaker: String = "对方",
    val createdAt: Long = 0,
    val coverage: ContextCoverage = ContextCoverage(),
    val zoneId: String = java.util.TimeZone.getDefault().id,
) {
    val key: String get() = MoodStore.keyOf(text, talker, context, messageId, speaker, createdAt, coverage, zoneId)
}

object MessagePolicy {
    const val MAX_CHARACTERS = 1000
    const val MAX_CONTEXT_MESSAGES = 24
    const val MAX_CONTEXT_SCAN = 80
    const val MAX_CONTEXT_CHARACTERS = 12000

    fun textOrNull(text: String): String? {
        if (text.codePointCount(0, text.length) > MAX_CHARACTERS) return null
        return text.trim().takeIf { it.isNotEmpty() }
    }
}
