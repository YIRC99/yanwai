package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.MessagePolicy

data class MessageMetadata(val type: Int, val isSend: Int, val content: String, val talker: String,
    val messageId: Long = 0) {
    fun incomingText(): String? = if (isSend == 0) plainText() else null

    fun plainText(): String? {
        if (type != 1 || isSend !in 0..1 || talker.isBlank()) return null
        val text = if (isSend == 0 && talker.endsWith("@chatroom") && content.contains(":\n"))
            content.substringAfter(":\n") else content
        return MessagePolicy.textOrNull(text)
    }

    fun speaker(): String = when {
        isSend == 1 -> "我"
        talker.endsWith("@chatroom") && content.contains(":\n") -> content.substringBefore(":\n").ifBlank { "对方" }
        else -> "对方"
    }
    companion object {
        fun read(item: Any?): MessageMetadata? {
            if (item == null) return null
            return runCatching {
                fun value(name: String): Any? {
                    var clazz: Class<*>? = item.javaClass
                    while (clazz != null && clazz != Any::class.java) {
                        val field = runCatching { clazz!!.getDeclaredField(name) }.getOrNull()
                        if (field != null) { field.isAccessible = true; return field.get(item) }
                        clazz = clazz.superclass
                    }
                    return null
                }
                MessageMetadata(value("field_type") as? Int ?: return null,
                    value("field_isSend") as? Int ?: return null,
                    value("field_content") as? String ?: "",
                    value("field_talker") as? String ?: "",
                    (value("field_msgId") as? Number)?.toLong() ?: 0)
            }.getOrNull()
        }
    }
}
