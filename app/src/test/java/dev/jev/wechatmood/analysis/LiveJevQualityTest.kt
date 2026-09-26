package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.ApiSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Explicit paid evaluation of synthetic cases; excluded from normal/offline builds. */
class LiveJevQualityTest {
    @Test fun compareFrozenSyntheticCases() {
        val key = System.getenv("JEV_EVAL_KEY").orEmpty()
        check(key.isNotBlank()) { "Missing JEV_EVAL_KEY; no live evaluation performed" }
        val settings = ApiSettings.fromInput(System.getenv("JEV_EVAL_ENDPOINT").orEmpty(), key,
            model = System.getenv("JEV_EVAL_MODEL").orEmpty())
        val client = JevHttpClient()
        val results = JSONArray()
        val holdout = System.getenv("JEV_EVAL_SUITE") == "holdout"
        val cases = if (holdout) JevQualityCases.holdout else JevQualityCases.cases
        val output = File(if (holdout) "build/reports/jev-quality-holdout.json" else "build/reports/jev-quality.json")
        output.parentFile?.mkdirs()
        var forbidden = 0
        for (case in cases) {
            val input = JevQualityCases.input(case)
            for (mode in listOf("legacy", "time_only", "full")) {
                val exchanges = JSONArray()
                val start = System.nanoTime()
                fun exchange(payload: JSONObject): String {
                    val response = client.exchange(payload, settings)
                    check(!response.contains(key)) { "Response contained credential; report aborted" }
                    val json = JSONObject(response)
                    val requested = payload.getJSONObject("questions")
                    val answers = json.getJSONObject("answers")
                    exchanges.put(JSONObject().put("request", payload).put("response", json))
                    for (question in requested.keys()) {
                        val options = requested.getJSONObject(question).getJSONObject("criteria")
                        val answer = answers.getJSONObject(question)
                        val p = answer.getJSONObject("probabilities")
                        assertEquals("choice", answer.getString("type"))
                        assertTrue(options.has(answer.getString("choice")))
                        assertEquals(options.length(), p.length())
                        val values = options.keys().asSequence().map { p.getDouble(it) }.toList()
                        assertTrue(values.all { it.isFinite() && it in 0.0..1.0 })
                        assertTrue(abs(values.sum() - 1) <= 0.02)
                        assertTrue(answer.getDouble("confidence").let { it.isFinite() && it in 0.0..1.0 })
                        assertTrue("$question chose a non-maximum option", p.getDouble(answer.getString("choice")) + 0.000001 >= values.max())
                    }
                    return response
                }
                var protocolFailure: Throwable? = null
                val mood = try {
                    if (mode == "full") ChatAnalysis.analyze(input, settings.model, ::exchange)
                    else { exchange(JevQualityCases.legacy(input, settings.model, mode == "time_only")); null }
                } catch (error: AssertionError) { protocolFailure = error; null }
                catch (error: org.json.JSONException) { protocolFailure = error; null }
                catch (error: IllegalArgumentException) { protocolFailure = error; null }
                if (protocolFailure != null) {
                    val legacy = JevQualityCases.resource("legacy-1.6.0-questions.json")
                    results.put(JSONObject().put("id", case.getString("id")).put("mode", mode)
                        .put("protocol_valid", false).put("error", protocolFailure.javaClass.simpleName)
                        .put("emotion_match", false).put("intent_match", false).put("forbidden_action", false)
                        .put("common_emotion_case", JevQualityCases.strings(case.getJSONArray("emotions")).any { legacy.getJSONObject("emotion").getJSONObject("criteria").has(it) })
                        .put("common_intent_case", JevQualityCases.strings(case.getJSONArray("intents")).any { legacy.getJSONObject("speech_act").getJSONObject("criteria").has(it) })
                        .put("topic_match", JSONObject.NULL).put("ambiguous_specific_emotion", false)
                        .put("ambiguous_case", case.optBoolean("ambiguous")).put("action", "none")
                        .put("elapsed_ms", (System.nanoTime() - start) / 1_000_000).put("calls", exchanges.length()).put("exchanges", exchanges))
                    output.writeText(JSONObject().put("synthetic_only", true).put("results", results).toString(2))
                    println("QUALITY ${case.getString("id")} $mode PROTOCOL_FAILURE ${protocolFailure.javaClass.simpleName}")
                    continue
                }
                val firstAnswers = exchanges.getJSONObject(0).getJSONObject("response").getJSONObject("answers")
                val lastAnswers = exchanges.getJSONObject(exchanges.length() - 1).getJSONObject("response").getJSONObject("answers")
                val emotion = lastAnswers.optJSONObject("emotion_review") ?: firstAnswers.getJSONObject("emotion")
                val intent = lastAnswers.optJSONObject("speech_act_review") ?: firstAnswers.getJSONObject("speech_act")
                val action = if (mood?.detail?.contains("建议：") == true) lastAnswers.getJSONObject("action").getString("choice") else "none"
                val forbiddenActions = case.optJSONArray("forbidden_actions")?.let(JevQualityCases::strings).orEmpty()
                val unsafe = mode == "full" && action in forbiddenActions
                if (unsafe) forbidden++
                val result = JSONObject().put("id", case.getString("id")).put("mode", mode).put("protocol_valid", true)
                    .put("emotion", emotion.getString("choice")).put("intent", intent.getString("choice"))
                    .put("emotion_match", emotion.getString("choice") in JevQualityCases.strings(case.getJSONArray("emotions")))
                    .put("intent_match", intent.getString("choice") in JevQualityCases.strings(case.getJSONArray("intents")))
                    .put("action", action).put("forbidden_action", unsafe)
                    .put("emotion_clear", emotion.getDouble("confidence") >= 0.35 && emotion.getJSONObject("probabilities").getDouble(emotion.getString("choice")) >= 0.55)
                    .put("elapsed_ms", (System.nanoTime() - start) / 1_000_000).put("calls", exchanges.length())
                    .put("card", mood?.detail ?: JSONObject.NULL).put("exchanges", exchanges)
                val metrics = JevQualityCases.metrics(case, emotion, intent, firstAnswers, mode == "full")
                metrics.keys().forEach { result.put(it, metrics.get(it)) }
                results.put(result)
                output.writeText(JSONObject().put("synthetic_only", true).put("results", results).toString(2))
                println("QUALITY ${case.getString("id")} $mode emotion=${result.getString("emotion")} match=${result.getBoolean("emotion_match")} intent=${result.getString("intent")} match=${result.getBoolean("intent_match")} unsafe=$unsafe calls=${exchanges.length()}")
            }
        }
        for (mode in listOf("legacy", "time_only", "full")) {
            val rows = (0 until results.length()).map { results.getJSONObject(it) }.filter { it.getString("mode") == mode }
            println("QUALITY SUMMARY $mode n=${rows.size} valid=${rows.count { it.getBoolean("protocol_valid") }} emotionMatches=${rows.count { it.getBoolean("emotion_match") }} intentMatches=${rows.count { it.getBoolean("intent_match") }} forbidden=${rows.count { it.getBoolean("forbidden_action") }}")
            val commonEmotion = rows.filter { it.getBoolean("common_emotion_case") }
            val commonIntent = rows.filter { it.getBoolean("common_intent_case") }
            println("QUALITY COMMON $mode emotion=${commonEmotion.count { it.getBoolean("emotion_match") }}/${commonEmotion.size} intent=${commonIntent.count { it.getBoolean("intent_match") }}/${commonIntent.size}")
            if (mode == "full") println("QUALITY EXTRA topic=${rows.count { it.optBoolean("topic_match") }}/${rows.count { !it.isNull("topic_match") }} ambiguousSpecific=${rows.count { it.getBoolean("ambiguous_specific_emotion") }}/${rows.count { it.getBoolean("ambiguous_case") }} advice=${rows.count { it.getString("action") != "none" }}/${rows.size}")
        }
        assertEquals("A displayed action violated a frozen boundary case; inspect build/reports/jev-quality.json", 0, forbidden)
        assertTrue("Production full pipeline returned malformed output; inspect quality report",
            (0 until results.length()).map { results.getJSONObject(it) }.filter { it.getString("mode") == "full" }.all { it.getBoolean("protocol_valid") })
    }
}
