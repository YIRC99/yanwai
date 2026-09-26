package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Synthetic, frozen before live evaluation. Multiple labels explicitly allow textual ambiguity. */
internal object JevQualityCases {
    fun resource(name: String): JSONObject = JSONObject(requireNotNull(javaClass.getResourceAsStream("/jev/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() })
    fun strings(array: JSONArray): Set<String> = (0 until array.length()).map { array.getString(it) }.toSet()
    val cases: List<JSONObject> get() = resource("quality-cases.json").getJSONArray("cases").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    val holdout: List<JSONObject> get() = resource("holdout-cases.json").getJSONArray("cases").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun metrics(case: JSONObject, emotion: JSONObject, intent: JSONObject, first: JSONObject, full: Boolean): JSONObject {
        val legacy = resource("legacy-1.6.0-questions.json")
        val emotions = strings(case.getJSONArray("emotions"))
        val intents = strings(case.getJSONArray("intents"))
        val topic = case.optJSONArray("topic")
        val specific = case.optBoolean("ambiguous") && emotion.getString("choice") != "unknown" &&
            emotion.getDouble("confidence") >= 0.35 && emotion.getJSONObject("probabilities").getDouble(emotion.getString("choice")) >= 0.55
        return JSONObject().put("common_emotion_case", emotions.any { legacy.getJSONObject("emotion").getJSONObject("criteria").has(it) })
            .put("common_intent_case", intents.any { legacy.getJSONObject("speech_act").getJSONObject("criteria").has(it) })
            .put("topic_match", if (full && topic != null) first.getJSONObject("topic_relation").getString("choice") in strings(topic) else JSONObject.NULL)
            .put("ambiguous_case", case.optBoolean("ambiguous")).put("ambiguous_specific_emotion", specific)
    }
    fun input(case: JSONObject): AnalysisInput {
        val unknownTime = case.optBoolean("unknown_time")
        fun time(value: String) = if (unknownTime || value.isBlank()) 0L else Instant.parse(value).toEpochMilli()
        val history = case.getJSONArray("history")
        return AnalysisInput(case.getString("text"), "synthetic-${case.getString("id")}",
            (0 until history.length()).map { i -> history.getJSONObject(i).let {
                ContextMessage(it.getString("speaker"), it.getString("message"), time(it.optString("sent_at")), i + 1L)
            } }, history.length() + 1L, createdAt = time(case.getString("sent_at")),
            coverage = ContextCoverage("synthetic", omittedMedia = case.optInt("omitted_media")), zoneId = "Asia/Shanghai")
    }
    fun legacy(input: AnalysisInput, model: String, withTime: Boolean): JSONObject {
        val limited = input.copy(context = input.context.takeLast(10))
        val state = if (withTime) AnalysisState.build(limited).apply { remove("time_note") } else JSONObject()
            .put("message", input.text).put("speaker", input.speaker)
            .put("context", JSONArray(limited.context.map { JSONObject().put("speaker", it.speaker).put("message", it.text) }))
        return JSONObject().put("model", model).put("state", state)
            .put("questions", resource("legacy-1.6.0-questions.json"))
    }
}
