package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.MessagePolicy
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.voice.VoiceText

data class MessageMetadata(val type: Int, val isSend: Int, val content: String, val talker: String,
    val messageId: Long = 0, val createdAt: Long = 0, val imagePath: String = "", val serverId: Long = 0,
    val displayName: String = "") {
    private val quoteReply by lazy {
        if (type in setOf(49, 822083633) && isSend in 0..1 && talker.isNotBlank()) QuoteMessage.parse(body()) else null
    }
    fun quotedMessage() = quoteReply?.quoted
    fun isAnalysisContent() = type in setOf(1, 34) || quoteReply != null
    fun incomingText(): String? = if (isSend == 0) plainText() else null
    fun isReplyTarget(allowMedia: Boolean = false): Boolean = isSend == 0 && talker.isNotBlank() && messageId > 0 &&
        ((type == 1 && content.isNotBlank()) || type == 34 || quoteReply?.text?.isNotBlank() == true ||
            (allowMedia && (type == 3 || type == 47 || type == 49 || type == 822083633)))

    fun voiceSource(): VoiceSource? = if (type == 34 && isSend in 0..1 && talker.isNotBlank() && messageId > 0)
        VoiceSource(talker, messageId, createdAt, isSend, imagePath, serverId) else null

    fun analysisText(): String? = if (voiceSource() != null) VoiceText.WAITING else plainText()

    fun plainText(): String? {
        if (isSend !in 0..1 || talker.isBlank()) return null
        val text = if (type == 1) body() else quoteReply?.text ?: return null
        return MessagePolicy.textOrNull(text)
    }

    private fun groupSender(): String? {
        if (isSend != 0 || !talker.endsWith("@chatroom")) return null
        val separator = if (type == 34) ":" else ":\n"
        if (!content.contains(separator)) return null
        return content.substringBefore(separator).takeIf {
            it.isNotBlank() && it.length <= 128 && it.none { c -> c.isWhitespace() || c == '<' || c == '>' }
        }
    }
    private fun body() = if (groupSender() != null && type != 34) content.substringAfter(":\n") else content

    fun speaker(): String = when {
        isSend == 1 -> "我"
        talker.endsWith("@chatroom") -> displayName.takeIf { it.isNotBlank() } ?: groupSender() ?: "对方（群成员未知）"
        else -> "对方"
    }
    companion object {
        private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Map<String, java.lang.reflect.Field?>>()
        private val dumped = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 一次性把消息对象的字段名写进日志，方便确认昵称到底存在哪个字段。 */
        private fun dumpFields(item: Any) {
            if (!dumped.compareAndSet(false, true)) return
            runCatching {
                val names = generateSequence(item.javaClass) { it.superclass }.takeWhile { it != Any::class.java }
                    .flatMap { clazz -> clazz.declaredFields.map { it.name } }.distinct().toList()
                dev.jev.wechatmood.core.MoodLog.i("MSG_FIELDS count=${names.size} names=${names.take(60)}")
            }
        }

        private fun fields(type: Class<*>): Map<String, java.lang.reflect.Field?> = fieldCache.getOrPut(type) {
            listOf("field_type", "field_isSend", "field_content", "field_talker", "field_msgId", "field_createTime",
                "field_imgPath", "field_msgSvrId",
                // 群成员显示名可能在这些字段里，取到就优先用
                "field_displayName", "field_nickName", "field_senderName",
                "field_chatroomMemberName", "field_memberName", "field_chatroomNickName").associateWith { name ->
                generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
                    .firstNotNullOfOrNull { clazz ->
                        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                    }
            }
        }

        fun read(item: Any?): MessageMetadata? {
            if (item == null) return null
            dumpFields(item)
            return runCatching {
                val accessors = fields(item.javaClass)
                fun value(name: String): Any? = accessors[name]?.get(item)
                MessageMetadata(value("field_type") as? Int ?: return null,
                    value("field_isSend") as? Int ?: return null,
                    value("field_content") as? String ?: "",
                    value("field_talker") as? String ?: "",
                    (value("field_msgId") as? Number)?.toLong() ?: 0,
                    (value("field_createTime") as? Number)?.toLong() ?: 0,
                    value("field_imgPath") as? String ?: "", (value("field_msgSvrId") as? Number)?.toLong() ?: 0,
                    listOf("field_displayName", "field_nickName", "field_senderName", "field_chatroomMemberName",
                        "field_memberName", "field_chatroomNickName")
                        .firstNotNullOfOrNull { name -> (value(name) as? String)?.takeIf { it.isNotBlank() } } ?: "")
            }.getOrNull()
        }
    }
}
