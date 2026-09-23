package dev.jev.wechatmood.hook

data class MessageMetadata(val type: Int, val isSend: Int, val content: String, val talker: String) {
    fun incomingText(): String? {
        if (type != 1 || isSend != 0 || talker.isBlank()) return null
        val text = if (talker.endsWith("@chatroom") && content.contains(":\n"))
            content.substringAfter(":\n") else content
        return text.trim().takeIf { it.isNotEmpty() }
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
                    value("field_talker") as? String ?: "")
            }.getOrNull()
        }
    }
}
