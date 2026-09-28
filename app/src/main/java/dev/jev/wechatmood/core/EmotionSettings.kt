package dev.jev.wechatmood.core

enum class EmotionSource(val id: String, val label: String) {
    JEV("jev", "JEV 决策模型"), LLM("llm", "LLM 大模型");
    companion object { fun resolve(value: String?) = entries.firstOrNull { it.id == value } ?: JEV }
}

/** Explicitly link existing settings; never change the reply model or the saved JEV intent choice. */
data class EmotionSettings(val source: EmotionSource = EmotionSource.JEV, val reuseReply: Boolean = false) {
    companion object {
        const val KEY_SOURCE = "emotion_source"
        const val KEY_REUSE_REPLY = "emotion_reuse_reply"
        fun load(read: (String) -> String?) = EmotionSettings(EmotionSource.resolve(read(KEY_SOURCE)), read(KEY_REUSE_REPLY) == "true")
    }
}
