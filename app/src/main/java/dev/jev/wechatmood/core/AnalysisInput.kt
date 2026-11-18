package dev.jev.wechatmood.core

import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.voice.VoiceState

data class ContextMessage(val speaker: String, val text: String, val createdAt: Long = 0, val messageId: Long = 0,
    val voice: VoiceSource? = null, val voiceState: VoiceState = if (voice == null) VoiceState.NONE else VoiceState.WAITING,
    val quoted: QuotedMessage? = null)

data class ContextCoverage(
    val source: String = "provided",
    val scanned: Int = 0,
    val omittedMedia: Int = 0,
    val unavailable: Int = 0,
    val omittedText: Int = 0,
    val invalidTime: Int = 0,
    val truncated: Boolean = false,
    val unavailableVoice: Int = 0,
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
    val voice: VoiceSource? = null,
    val voiceState: VoiceState = if (voice == null) VoiceState.NONE else VoiceState.WAITING,
    val quoted: QuotedMessage? = null,
    val accountScope: String = "",
    val background: dev.jev.wechatmood.reply.ContactBackground = dev.jev.wechatmood.reply.ContactBackground(),
    val backgroundReady: Boolean = true,
    val settingsFingerprint: String = "",
) {
    /**
     * 界面每帧都会读一次。旧实现是计算属性，每次访问都要重建整串再做一次 SHA-256，
     * 而 records() 里同一条消息每 tick 会被读好几次。data class 的 copy() 会生成新实例，
     * 所以用 lazy 不会拿到别的上下文的 key。
     */
    val key: String by lazy {
        MoodStore.keyOf(text, talker, context, messageId, speaker, createdAt, coverage,
            zoneId + (voice?.let { "|voice:${it.key}:$voiceState" } ?: "") + "|background:${background.revision}:$backgroundReady|settings:$settingsFingerprint", quoted, accountScope)
    }
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
