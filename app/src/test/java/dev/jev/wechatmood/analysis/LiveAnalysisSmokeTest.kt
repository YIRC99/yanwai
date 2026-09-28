package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties
import java.io.File

/** Explicit one-sample check; credentials never printed and no real contact data. */
class LiveAnalysisSmokeTest {
    @Test fun syntheticJevRoute() = runBlocking {
        val props = Properties().apply { File("../jev.local.properties").inputStream().use(::load) }
        val api = ApiSettings.fromInput(props.getProperty("endpoint", ""), props.getProperty("apiKey", ""),
            model = props.getProperty("model", ""))
        check(api.isConfigured) { "No local JEV test credentials" }
        var calls = 0
        val started = System.nanoTime()
        val mood = AnalysisRouter.analyze(AnalysisInput("今天完成了练习，好开心！", "synthetic-only"),
            RuntimeSettings(1, false, api, "synthetic-only"),
            { calls++; JevHttpClient().exchangeSuspending(it, api) }, { _, _ -> error("Unexpected LLM call") })
        assertTrue(mood.emotions.isNotEmpty()); assertFalse(mood.intentFailed)
        println("LIVE JEV synthetic sample PASS requests=$calls elapsedMs=${(System.nanoTime() - started) / 1000000}; no personal data")
    }
}
