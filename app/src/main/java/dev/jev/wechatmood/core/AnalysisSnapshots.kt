package dev.jev.wechatmood.core

/** Keep the evidence selected for a message stable while the loaded history window moves. */
class AnalysisSnapshots(private val capacity: Int = 512) {
    private val inputs = LinkedHashMap<String, AnalysisInput>(16, 0.75f, true)
    init { require(capacity > 0) }

    @Synchronized fun resolve(input: AnalysisInput): AnalysisInput {
        if (input.messageId <= 0 || input.talker.isBlank()) return input
        // Keep target content, sender, time, and voice identity; only the history window may drift.
        val identity = input.copy(context = emptyList(), coverage = ContextCoverage()).key
        return inputs[identity] ?: input.copy(context = input.context.toList()).also {
            inputs[identity] = it
            while (inputs.size > capacity) inputs.remove(inputs.keys.first())
        }
    }
    @Synchronized fun clear() = inputs.clear()
    @Synchronized fun clearConversation(talker: String) {
        inputs.entries.removeAll { it.value.talker == talker }
    }
}
