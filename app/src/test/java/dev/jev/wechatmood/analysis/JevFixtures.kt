package dev.jev.wechatmood.analysis

import org.json.JSONObject

/** Synthetic transport replies only. These do not measure live model quality. */
internal object JevFixtures {
    fun reply(payload: JSONObject, selected: Map<String, String> = emptyMap()): String {
        val answers = JSONObject()
        val questions = payload.getJSONObject("questions")
        for (key in questions.keys()) {
            val criteria = questions.getJSONObject(key).getJSONObject("criteria")
            val choice = selected[key] ?: when (key) {
                "scene" -> "promise"
                "emotion" -> "hurt"
                "progress" -> "act"
                "focus" -> "none"
                "action" -> "none"
                "target" -> "listener"
                "commitment" -> "pending"
                "speech_act", "advice_need", "own_fault", "new_topic" -> "unknown"
                else -> "signal"
            }
            require(criteria.has(choice)) { "Unknown test option $key/$choice" }
            val probabilities = JSONObject()
            criteria.keys().forEach { probabilities.put(it, if (it == choice) 1.0 else 0.0) }
            answers.put(key, JSONObject().put("type", "choice").put("choice", choice)
                .put("confidence", 1.0).put("probabilities", probabilities))
        }
        return JSONObject().put("answers", answers).toString()
    }

    fun profile(scene: String = "promise", progress: String = "act"): ChatProfile =
        JevProtocol.parseProfile(reply(JevProtocol.payload("所以呢？", "test"), mapOf(
            "scene" to scene, "progress" to progress,
            "commitment" to if (progress == "accepted") "accepted" else "pending")))
}
