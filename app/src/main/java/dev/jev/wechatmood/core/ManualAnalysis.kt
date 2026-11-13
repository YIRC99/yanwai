package dev.jev.wechatmood.core

/** Process-only choices for individual incoming text messages. */
class ManualAnalysis {
    private val selected = java.util.concurrent.ConcurrentHashMap<String, AnalysisInput>()
    /** 用户主动收起的卡片；只存在进程内存，重启微信或重新长按会恢复。 */
    private val hidden = java.util.concurrent.ConcurrentHashMap<String, AnalysisInput>()

    /** 收起状态只认「哪条消息」，不跟着 accountScope / 背景的短暂变化漂移。 */
    private fun hideKey(input: AnalysisInput): String? =
        if (input.messageId <= 0 || input.talker.isBlank()) null
        else input.talker + "\u0000" + input.messageId + "\u0000" + input.speaker

    fun hide(input: AnalysisInput): Boolean {
        val key = hideKey(input) ?: return false
        hidden[key] = input
        return true
    }

    fun reveal(input: AnalysisInput) {
        hideKey(input)?.let(hidden::remove)
    }

    fun isHidden(input: AnalysisInput): Boolean = hideKey(input)?.let(hidden::containsKey) ?: false

    fun select(input: AnalysisInput): Boolean {
        val identity = identity(input) ?: return false
        // Keep the original context/cache key even if more history is loaded later.
        selected.putIfAbsent(identity, input.copy(context = input.context.toList()))
        return true
    }

    fun selectedInput(input: AnalysisInput): AnalysisInput? = identity(input)?.let(selected::get)

    /** A user can select a message while its background account lookup is still pending. */
    fun resolveAccount(input: AnalysisInput, pendingScope: String) {
        val key = identity(input.copy(accountScope = pendingScope)) ?: return
        val pending = selected.remove(key) ?: return
        select(pending.copy(accountScope = input.accountScope))
    }

    fun clearConversation(talker: String) {
        selected.entries.removeAll { it.value.talker == talker }
        hidden.entries.removeAll { it.value.talker == talker }
    }

    fun allows(input: AnalysisInput, chatEnabled: Boolean): Boolean =
        chatEnabled || selectedInput(input) != null

    companion object {
        fun identity(input: AnalysisInput): String? {
            if (input.messageId <= 0 || input.talker.isBlank() || MessagePolicy.textOrNull(input.text) == null) return null
            return MoodStore.keyOf(input.text, input.talker, messageId = input.messageId, speaker = input.speaker,
                zoneId = input.voice?.key ?: java.util.TimeZone.getDefault().id, quoted = input.quoted,
                accountScope = input.accountScope)
        }
    }
}
