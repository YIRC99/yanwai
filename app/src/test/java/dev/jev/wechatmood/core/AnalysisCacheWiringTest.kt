package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Host entry points need Android/Xposed; protect their wiring alongside executable cache tests. */
class AnalysisCacheWiringTest {
    private fun source(path: String) = File("src/main/java/dev/jev/wechatmood/$path").readText()

    @Test fun `turning a chat off must retain evidence used to find completed results`() {
        val prefs = source("core/ModulePrefs.kt")
        val switch = prefs.substringAfter("fun setChatEnabled(").substringBefore("fun report(")
        assertFalse("Closing the switch discards the cache lookup snapshot", switch.contains("analysisSnapshots.clearConversation"))
        assertTrue("Closing the switch must still revoke manual display choices", switch.contains("manualAnalysis.clearConversation"))
    }

    @Test fun `analysis checks persistent results before sending model requests`() {
        val analyzer = source("analysis/SignalAnalyzer.kt")
        val lookup = analyzer.indexOf("AnalysisResultCache.find(")
        assertTrue("No persistent lookup in the actual analysis path", lookup >= 0)
        assertTrue(lookup < analyzer.indexOf("AnalysisRouter.analyze(", lookup))
        assertTrue(analyzer.contains("AnalysisResultCache.save("))
    }

    @Test fun `only the module provider opens cache files and authorization precedes dispatch`() {
        val client = source("core/AnalysisResultCache.kt")
        assertFalse(client.contains("SQLiteDatabase"))
        assertFalse(client.contains("java.io.File"))
        assertTrue(client.contains("SettingsTransport.call"))
        val disk = source("core/AnalysisCacheProvider.kt")
        assertTrue(disk.contains("context.packageName == BuildConfig.APPLICATION_ID"))
        assertTrue(disk.contains("context.noBackupFilesDir"))
        val provider = source("core/SettingsProvider.kt")
        assertTrue(provider.indexOf("if (!own && !wechat)") < provider.indexOf("AnalysisCacheProvider.call"))
        val service = source("core/SettingsConnectionService.kt")
        assertTrue(service.contains("SettingsProvider.dispatch(this@SettingsConnectionService, Binder.getCallingUid()"))
        assertFalse(service.contains("clearCallingIdentity"))
    }

    @Test fun `disk cache cannot bypass account verification and cancellation checks`() {
        val analyzer = source("analysis/SignalAnalyzer.kt")
        assertTrue(analyzer.indexOf("matchesAnalysisAccount(input)") < analyzer.indexOf("AnalysisResultCache.find("))
        assertTrue(analyzer.substringAfter("AnalysisResultCache.find(").substringBefore("return@withContext cached")
            .contains("if (!active())"))
        val history = source("hook/ReplyDatabaseHistory.kt")
        assertTrue(history.contains("if (moduleQuery.get() == true) return"))
    }
}
