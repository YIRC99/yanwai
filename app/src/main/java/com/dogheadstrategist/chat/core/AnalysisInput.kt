package com.dogheadstrategist.chat.core

import com.dogheadstrategist.chat.voice.VoiceSource
import com.dogheadstrategist.chat.voice.VoiceState

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
    val background: com.dogheadstrategist.chat.reply.ContactBackground = com.dogheadstrategist.chat.reply.ContactBackground(),
    val backgroundReady: Boolean = true,
    val settingsFingerprint: String = "",
) {
    val key: String get() = analysisKeyOf(text, talker, context, messageId, speaker, createdAt, coverage,
        zoneId + (voice?.let { "|voice:${it.key}:$voiceState" } ?: "") + "|background:${background.revision}:$backgroundReady|settings:$settingsFingerprint", quoted, accountScope)
}

/**
 * Length-prefix every field so different contexts or message identities never share a key.
 * Kept here (rather than in a result store) so message identity does not depend on any cache.
 */
private fun analysisKeyOf(text: String, talker: String?, context: List<ContextMessage> = emptyList(),
    messageId: Long = 0, speaker: String = "对方", createdAt: Long = 0,
    coverage: ContextCoverage = ContextCoverage(), zoneId: String = java.util.TimeZone.getDefault().id,
    quoted: QuotedMessage? = null, accountScope: String = ""): String {
    val source = buildString {
        fun field(value: String) { append(value.length).append(':').append(value) }
        fun quote(value: QuotedMessage?) {
            field(if (value == null) "no-quote" else "quoted-v1")
            if (value != null) {
                field(value.text.orEmpty()); field(value.displayName.orEmpty()); field(value.type.toString())
                field(value.serverId.orEmpty()); field(value.unavailableReason.orEmpty())
            }
        }
        field(talker.orEmpty())
        field(accountScope)
        field(messageId.toString())
        field(speaker)
        field(text)
        field("temporal-intent-v1")
        field(createdAt.toString())
        field(zoneId)
        field(coverage.toString())
        quote(quoted)
        context.forEach {
            field(it.speaker); field(it.text); field(it.createdAt.toString()); field(it.messageId.toString())
            it.voice?.let { source -> field(source.key); field(it.voiceState.name) }
            quote(it.quoted)
        }
    }
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
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
