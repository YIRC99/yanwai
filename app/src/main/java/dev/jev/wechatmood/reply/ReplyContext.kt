package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import java.security.MessageDigest
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.voice.VoiceState
import dev.jev.wechatmood.voice.VoiceText

data class ReplyMessage(val id: Long, val speaker: String, val time: Long, val text: String,
    val voice: VoiceSource? = null, val voiceState: VoiceState = if (voice == null) VoiceState.NONE else VoiceState.WAITING)
/** Identity check only. Media payloads stay out of the reply context and model input. */
data class ReplyHistoryAnchor(val id: Long, val type: Int, val sent: Int, val time: Long, val digest: String) {
    fun matches(record: MessageMetadata): Boolean = this == from(record)
    companion object {
        fun from(record: MessageMetadata) = ReplyHistoryAnchor(record.messageId, record.type, record.isSend, record.createdAt,
            MessageDigest.getInstance("SHA-256").digest(record.content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) })
    }
}
enum class ReplyContextSource { LOADED_PAGE, LOCAL_HISTORY }
data class ReplyContext(val talker: String, val messages: List<ReplyMessage>, val omittedMedia: Int = 0,
    val trimmed: Boolean = false, val latestLoadedId: Long = messages.lastOrNull()?.id ?: 0,
    val source: ReplyContextSource = ReplyContextSource.LOADED_PAGE, val historyFailure: String? = null,
    val requestedMessages: Int = MAX_MESSAGES, val historyAnchor: ReplyHistoryAnchor? = null) {
    val fingerprint: String get() {
        val text = messages.joinToString("\u0000") { "${it.id}:${it.speaker.length}:${it.speaker}:${it.time}:${it.text.length}:${it.text}:${it.voice?.key.orEmpty()}:${it.voiceState}" }
        return MessageDigest.getInstance("SHA-256").digest("$talker:$latestLoadedId:$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val MAX_MESSAGES = 100
        const val MAX_CHARACTERS = 48000
        fun collect(talker: String, records: List<MessageMetadata>, maxMessages: Int = MAX_MESSAGES): ReplyContext {
            require(maxMessages in 1..MAX_MESSAGES)
            val same = records.filter { it.talker == talker }.distinctBy { if (it.messageId > 0) "id:${it.messageId}" else "${it.createdAt}:${it.isSend}:${it.content}" }
            val eligible = same.filter { it.isSend in 0..1 && (it.type == 1 && it.content.isNotBlank() || it.voiceSource() != null) }
            val text = eligible.takeLast(maxMessages)
            var budget = MAX_CHARACTERS
            var trimmed = eligible.size > maxMessages
            val selected = text.asReversed().mapNotNull { record ->
                if (budget <= 0) { trimmed = true; return@mapNotNull null }
                val body = if (record.type == 34) VoiceText.WAITING
                    else if (record.isSend == 0 && talker.endsWith("@chatroom") && record.content.contains(":\n"))
                    record.content.substringAfter(":\n") else record.content
                val end = minOf(body.length, budget).let {
                    if (it < body.length && it > 0 && body[it - 1].isHighSurrogate() && body[it].isLowSurrogate()) it - 1 else it
                }
                val cut = body.take(end)
                if (cut.length != body.length) trimmed = true
                budget -= cut.length
                ReplyMessage(record.messageId, record.speaker(), record.createdAt, cut, record.voiceSource())
            }.asReversed()
            return ReplyContext(talker, selected, same.count { it.type !in setOf(1, 34) }, trimmed, same.lastOrNull()?.messageId ?: 0,
                requestedMessages = maxMessages,
                historyAnchor = same.lastOrNull { it.messageId > 0 && it.createdAt > 0 && it.isSend in 0..1 }
                    ?.let(ReplyHistoryAnchor::from))
        }
    }
}
