package dev.jev.wechatmood.core

/** Process-only choices for individual incoming text messages. */
class ManualAnalysis {
    private val selected = java.util.concurrent.ConcurrentHashMap<String, AnalysisInput>()

    fun select(input: AnalysisInput): Boolean {
        val identity = identity(input) ?: return false
        // Keep the original context/cache key even if more history is loaded later.
        selected.putIfAbsent(identity, input.copy(context = input.context.toList()))
        return true
    }

    fun selectedInput(input: AnalysisInput): AnalysisInput? = identity(input)?.let(selected::get)

    fun clearConversation(talker: String) {
        selected.entries.removeAll { it.value.talker == talker }
    }

    fun allows(input: AnalysisInput, chatEnabled: Boolean): Boolean =
        chatEnabled || selectedInput(input) != null

    companion object {
        fun identity(input: AnalysisInput): String? {
            if (input.messageId <= 0 || input.talker.isBlank() || MessagePolicy.textOrNull(input.text) == null) return null
            return MoodStore.keyOf(input.text, input.talker, messageId = input.messageId, speaker = input.speaker,
                zoneId = input.voice?.key ?: java.util.TimeZone.getDefault().id, quoted = input.quoted)
        }
    }
}
