package dev.jev.wechatmood.core

import java.security.MessageDigest

/** Only explicit opt-ins are stored. Reads use a process-local snapshot, without IPC. */
class ConversationSwitches(initial: Set<String>, private val persist: (Set<String>) -> Boolean) {
    @Volatile private var enabledKeys = initial.toSet()

    fun isEnabled(talker: String?): Boolean = storageKey(talker)?.let { it in enabledKeys } == true

    @Synchronized fun setEnabled(talker: String?, enabled: Boolean): Boolean {
        val key = storageKey(talker) ?: return false
        val next = if (enabled) enabledKeys + key else enabledKeys - key
        if (next == enabledKeys) return true
        if (!persist(next)) return false
        enabledKeys = next
        return true
    }

    private fun storageKey(talker: String?): String? {
        if (talker.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest(talker.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
