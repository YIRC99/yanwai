package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import java.security.MessageDigest

data class ReplyMessage(val id: Long, val speaker: String, val time: Long, val text: String)
enum class ReplyContextSource { LOADED_PAGE, LOCAL_HISTORY }
data class ReplyContext(val talker: String, val messages: List<ReplyMessage>, val omittedMedia: Int = 0,
    val trimmed: Boolean = false, val latestLoadedId: Long = messages.lastOrNull()?.id ?: 0,
    val source: ReplyContextSource = ReplyContextSource.LOADED_PAGE, val historyFailure: String? = null) {
    val fingerprint: String get() {
        val text = messages.joinToString("\u0000") { "${it.id}:${it.speaker.length}:${it.speaker}:${it.time}:${it.text.length}:${it.text}" }
        return MessageDigest.getInstance("SHA-256").digest("$talker:$latestLoadedId:$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val MAX_MESSAGES = 100
        const val MAX_CHARACTERS = 48000
        fun collect(talker: String, records: List<MessageMetadata>): ReplyContext {
            val same = records.filter { it.talker == talker }.distinctBy { if (it.messageId > 0) "id:${it.messageId}" else "${it.createdAt}:${it.isSend}:${it.content}" }
            val text = same.filter { it.type == 1 && it.isSend in 0..1 && it.content.isNotBlank() }.takeLast(MAX_MESSAGES)
            var budget = MAX_CHARACTERS
            var trimmed = same.count { it.type == 1 } > MAX_MESSAGES
            val selected = text.asReversed().mapNotNull { record ->
                if (budget <= 0) { trimmed = true; return@mapNotNull null }
                val body = if (record.isSend == 0 && talker.endsWith("@chatroom") && record.content.contains(":\n"))
                    record.content.substringAfter(":\n") else record.content
                val end = minOf(body.length, budget).let {
                    if (it < body.length && it > 0 && body[it - 1].isHighSurrogate() && body[it].isLowSurrogate()) it - 1 else it
                }
                val cut = body.take(end)
                if (cut.length != body.length) trimmed = true
                budget -= cut.length
                ReplyMessage(record.messageId, record.speaker(), record.createdAt, cut)
            }.asReversed()
            return ReplyContext(talker, selected, same.count { it.type != 1 }, trimmed, same.lastOrNull()?.messageId ?: 0)
        }
    }
}
