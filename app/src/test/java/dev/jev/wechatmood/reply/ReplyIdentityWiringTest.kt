package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android/host wiring complements the actual SQLite and queue tests; no device claims. */
class ReplyIdentityWiringTest {
    private fun source(path: String) = File("src/main/java/dev/jev/wechatmood/$path").readText()
    @Test fun `private storage stays behind authorized provider`() {
        val provider = source("core/SettingsProvider.kt")
        assertTrue(provider.indexOf("if (!own && !wechat)") < provider.indexOf("ReplyIdentityProvider.call"))
        val storage = source("core/ReplyIdentityProvider.kt")
        assertTrue(storage.contains("context.packageName == BuildConfig.APPLICATION_ID"))
        assertTrue(storage.contains("context.noBackupFilesDir"))
        val bridge = source("core/ReplyIdentityBridge.kt")
        assertTrue(bridge.contains("contentResolver.call(SettingsProvider.URI"))
        assertFalse(bridge.contains("SQLiteDatabase"))
    }
    @Test fun `drawer finishes identity load before creating editor and edits save independently of generation`() {
        val ui = source("hook/ReplyHostUi.kt")
        val open = ui.indexOf("openResolved(focusMessageId, live, owner, identity, preferredLimit)")
        assertTrue(open >= 0)
        assertTrue(ui.indexOf("ReplyIdentityBridge.load(") in 0 until open)
        assertTrue(ui.indexOf("ReplyLimitBridge.load(") in 0 until open)
        val edits = ui.substringAfter("customRole.addTextChangedListener").substringBefore("invalidateRequest =")
        assertTrue(edits.contains("saveIdentity()"))
        assertFalse(ui.substringAfter("fun controls(").substringBefore("fun renderParts()").contains("customRole.setText"))
        assertTrue(ui.contains("history.recall(selectedTalker, focusMessageId, owner.historyScope)"))
        assertTrue(ui.contains("history.remember(it, owner.historyScope)"))
        assertTrue(ui.substringAfter("val activeConfig =").substringBefore("val accepted =").contains("verifyAccount()"))
    }
}
