package dev.jev.wechatmood.analysis

data class ChatDecision(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) {
    // Initial conservative UI thresholds; they are not a claim of calibrated relationship accuracy.
    val clear: Boolean get() = confidence >= 0.35 && (probabilities[choice] ?: 0.0) >= 0.55
}

data class ChatProfile(val scene: ChatDecision, val emotion: ChatDecision,
    val warmth: ChatDecision, val progress: ChatDecision) {
    val canSpecialize: Boolean get() = scene.clear && scene.choice != "other"
}
