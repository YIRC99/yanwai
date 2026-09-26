package dev.jev.wechatmood.core

import dev.jev.wechatmood.reply.ReplySettings

enum class IntentRoute(val id: String, val label: String) {
    JEV("jev", "JEV 决策模型"), LLM("llm", "通用大模型 LLM");
    companion object { fun resolve(id: String?) = entries.firstOrNull { it.id == id } ?: JEV }
}

/** Independent credentials and permission from the manual reply assistant. */
class IntentSettings(val route: IntentRoute = IntentRoute.JEV, val llm: ReplySettings = ReplySettings.empty()) {
    fun sameAs(other: IntentSettings) = route == other.route && llm.endpoint == other.llm.endpoint &&
        llm.apiKey == other.llm.apiKey && llm.model == other.llm.model

    companion object {
        const val KEY_ROUTE = "intent_route"
        const val KEY_ENDPOINT = "intent_endpoint"
        const val KEY_API_KEY = "intent_api_key"
        const val KEY_MODEL = "intent_model"
        fun load(read: (String) -> String?): IntentSettings = IntentSettings(IntentRoute.resolve(read(KEY_ROUTE)),
            runCatching { ReplySettings.fromInput(read(KEY_ENDPOINT).orEmpty(), read(KEY_API_KEY).orEmpty(), read(KEY_MODEL).orEmpty()) }
                .getOrDefault(ReplySettings.empty()))
    }
}
