package dev.jev.wechatmood.analysis

data class ChatDecision(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) {
    // Initial conservative UI thresholds; they are not a claim of calibrated relationship accuracy.
    val clear: Boolean get() = confidence >= 0.35 && (probabilities[choice] ?: 0.0) >= 0.55
}

data class ChatProfile(
    val scene: ChatDecision,
    val emotion: ChatDecision,
    val progress: ChatDecision,
    val facts: Map<String, ChatDecision> = emptyMap(),
) {
    val canSpecialize: Boolean get() = scene.clear && scene.choice != "other"
    fun has(key: String, vararg values: String): Boolean = facts[key]?.let {
        it.clear && it.choice in values
    } == true
    val newTopic: Boolean get() = has("new_topic", "yes")
    val personalConflict: Boolean get() = has("target", "listener") && !newTopic
    val hasAgreement: Boolean get() = has("commitment", "pending", "accepted") && !newTopic
    val acceptsResponse: Boolean get() = !newTopic && progress.clear && progress.choice == "accepted" &&
        has("speech_act", "confirm") && has("commitment", "none", "pending", "accepted")
}
