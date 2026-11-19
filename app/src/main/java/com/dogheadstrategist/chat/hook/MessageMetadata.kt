package com.dogheadstrategist.chat.hook

import com.dogheadstrategist.chat.core.MessagePolicy
import com.dogheadstrategist.chat.voice.VoiceSource
import com.dogheadstrategist.chat.voice.VoiceText

data class MessageMetadata(val type: Int, val isSend: Int, val content: String, val talker: String,
    val messageId: Long = 0, val createdAt: Long = 0, val imagePath: String = "", val serverId: Long = 0) {
    private val quoteReply by lazy {
        if (type in setOf(49, 822083633) && isSend in 0..1 && talker.isNotBlank()) QuoteMessage.parse(body()) else null
    }
    fun quotedMessage() = quoteReply?.quoted
    fun isAnalysisContent() = type in setOf(1, 34) || quoteReply != null

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
        }?.let { raw ->
            // 前缀通常是发送者 wxid，换成昵称模型才分得清谁在说话
            runCatching { ReplyDatabaseHistory.nickOfUsername(raw) }.getOrNull() ?: raw
        }
    }
    private fun body() = if (groupSender() != null && type != 34) content.substringAfter(":\n") else content

    fun speaker(): String = when {
        isSend == 1 -> "我"
        talker.endsWith("@chatroom") -> groupSender() ?: "对方（群成员未知）"
        else -> "对方"
    }
    companion object {
        /**
         * 微信数据库的 createTime 是秒级时间戳，而界面展示和前后比较用的是毫秒。
         * 不换算的话「当前毫秒 - 消息秒数」会得到一个天文数字，
         * 判定式的比较永远不成立——「回复后撤销建议」失效正是栽在这里。
         *
         * 1e11 之上按毫秒原样返回：既兼容历史数据，也避免重复相乘。
         */
        fun toMillis(value: Long): Long =
            if (value in 1..100_000_000_000L) value * 1000 else value

        private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Map<String, java.lang.reflect.Field?>>()

        private fun fields(type: Class<*>): Map<String, java.lang.reflect.Field?> = fieldCache.getOrPut(type) {
            listOf("field_type", "field_isSend", "field_content", "field_talker", "field_msgId", "field_createTime", "field_imgPath", "field_msgSvrId").associateWith { name ->
                generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
                    .firstNotNullOfOrNull { clazz ->
                        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                    }
            }
        }

        fun read(item: Any?): MessageMetadata? {
            if (item == null) return null
            return runCatching {
                val accessors = fields(item.javaClass)
                fun value(name: String): Any? = accessors[name]?.get(item)
                MessageMetadata(value("field_type") as? Int ?: return null,
                    value("field_isSend") as? Int ?: return null,
                    value("field_content") as? String ?: "",
                    value("field_talker") as? String ?: "",
                    (value("field_msgId") as? Number)?.toLong() ?: 0,
                    toMillis((value("field_createTime") as? Number)?.toLong() ?: 0),
                    value("field_imgPath") as? String ?: "", (value("field_msgSvrId") as? Number)?.toLong() ?: 0)
            }.getOrNull()
        }
    }
}
