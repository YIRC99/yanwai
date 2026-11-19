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
    val requestedMessages: Int = ReplyHistoryLimit.DEFAULT, val historyAnchor: ReplyHistoryAnchor? = null,
    val background: ContactBackground = ContactBackground(), val focusMessageId: Long = 0,
    val targetMedia: String? = null, val targetImageDataUri: String? = null, val focusText: String? = null,
    val facts: List<RelationFact> = emptyList()) {
    val fingerprint: String get() {
        val text = messages.joinToString("\u0000") { "${it.id}:${it.speaker.length}:${it.speaker}:${it.time}:${it.text.length}:${it.text}:${it.voice?.key.orEmpty()}:${it.voiceState}" }
        return MessageDigest.getInstance("SHA-256").digest("$talker:$latestLoadedId:${background.revision}:$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val MAX_MESSAGES = 100
        /** 单次请求的聊天正文上限：太长会撞小模型的上下文，也会拖慢响应。 */
        const val MAX_CHARACTERS = 12000
        fun collect(talker: String, records: List<MessageMetadata>, maxMessages: Int = MAX_MESSAGES): ReplyContext {
            require(maxMessages in 1..MAX_MESSAGES)
            val same = records.filter { it.talker == talker }.distinctBy { if (it.messageId > 0) "id:${it.messageId}" else "${it.createdAt}:${it.isSend}:${it.content}" }
            // 引用消息即使解析不出正文也要保留：丢掉它会让模型对着上下文里别的消息作答。
            // 超过 1000 字的单条消息只是分析功能的限制，回复上下文由下面的总预算裁剪，
            // 这里按内容是否可用筛选，不能因为正文超长就把整条消息丢掉。
            val eligible = same.filter { it.isSend in 0..1 && when (it.type) {
                1 -> it.content.isNotBlank()
                34 -> it.voiceSource() != null
                49, 822083633 -> true
                else -> it.plainText() != null
            } }
            val text = eligible.takeLast(maxMessages)
            var budget = MAX_CHARACTERS
            var trimmed = eligible.size > maxMessages
            val selected = text.asReversed().mapNotNull { record ->
                if (budget <= 0) { trimmed = true; return@mapNotNull null }
                // 引用消息的 content 是 XML，取解析后的文本，别把标签塞给模型。
                val body = when {
                    record.type == 34 -> VoiceText.WAITING
                    record.type == 49 || record.type == 822083633 -> {
                        // 引用消息要把「被引用的原文」和「这次新写的话」一起给模型，
                        // 只留其中一边很容易答非所问；解析失败也要留占位，不能整条丢掉。
                        val own = record.plainText().orEmpty()
                        val quoted = record.quotedMessage()
                        val quotedText = quoted?.text?.takeIf { it.isNotBlank() }?.take(200)
                        // 合并转发：正文是 XML，回复时模型看不到，先把要点摊平给它。
                        val forwarded = if (own.isBlank() && quotedText == null && ForwardDigest.isForward(record.content))
                            ForwardDigest.parse(record.content) else null
                        when {
                            forwarded != null -> "【合并转发：${forwarded.title.take(40)}】\n" +
                                forwarded.items.take(40).joinToString("\n") { item ->
                                    (if (item.sender.isBlank()) "" else item.sender + ": ") + item.text.take(150)
                                }.take(4000)
                            quotedText != null -> "【引用${quoted.displayName?.take(32).orEmpty()}：$quotedText】$own"
                            own.isNotBlank() -> own
                            else -> "【引用消息，内容未能解析】"
                        }
                    }
                    record.isSend == 0 && talker.endsWith("@chatroom") && record.content.contains(":\n") ->
                        record.content.substringAfter(":\n")
                    else -> record.content
                }
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
