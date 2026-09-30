package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android/host wiring complements the actual SQLite and queue tests; no device claims. */
class ReplyIdentityWiringTest {
    private fun source(path: String) = File("src/main/java/dev/jev/wechatmood/$path").readText()
    @Test fun `role library applies on explicit selection and new role revisions guard late responses`() {
        val ui = source("hook/ReplyHostUi.kt")
        val menu = ui.substringAfter("fun showRoleMenu(").substringBefore("invalidateRequest =")
        assertTrue(menu.contains("setOnMenuItemClickListener"))
        assertTrue(menu.contains("ReplyIdentityBridge.applyRole"))
        assertTrue(menu.contains("composition.background = applied.background"))
        assertTrue(menu.contains("if (applyingRole") || ui.contains("if (applyingRole || !ownsDrawer())"))
        assertTrue(ui.contains("rolesRevision == ModulePrefs.analysisSettings()?.rolesRevision"))
        val provider = source("core/ReplyIdentityProvider.kt")
        assertTrue(provider.substringAfter("if (method == \"reply_role_list\")").substringBefore("val requested")
            .contains("roles.templates()"))
        assertFalse(provider.substringAfter("if (method == \"reply_role_list\")").substringBefore("val requested")
            .contains("roles.list()"))
        assertTrue(source("core/ReplyIdentityBridge.kt").contains("generation.current() && backgrounds.put"))
    }
    @Test fun `private storage stays behind authorized provider`() {
        val provider = source("core/SettingsProvider.kt")
        assertTrue(provider.indexOf("if (!own && !wechat)") < provider.indexOf("ReplyIdentityProvider.call"))
        val storage = source("core/ReplyIdentityProvider.kt")
        assertTrue(storage.contains("context.packageName == BuildConfig.APPLICATION_ID"))
        assertTrue(storage.contains("context.noBackupFilesDir"))
        val bridge = source("core/ReplyIdentityBridge.kt")
        assertTrue(bridge.contains("SettingsTransport.call(context,"))
        assertFalse(bridge.contains("SQLiteDatabase"))
        val service = source("core/SettingsConnectionService.kt")
        assertTrue(service.contains("SettingsProvider.dispatch(this@SettingsConnectionService, Binder.getCallingUid()"))
    }
    @Test fun `drawer finishes identity load before creating editor and edits save independently of generation`() {
        val ui = source("hook/ReplyHostUi.kt")
        val open = ui.indexOf("openResolved(focusMessageId, live, owner, identity, preferredLimit, background)")
        assertTrue(open >= 0)
        assertTrue(ui.indexOf("ReplyIdentityBridge.load(") in 0 until open)
        assertTrue(ui.indexOf("ReplyLimitBridge.load(") in 0 until open)
        assertTrue(ui.indexOf("ReplyIdentityBridge.loadBackground(") in 0 until open)
        val edits = ui.substringAfter("customRole.addTextChangedListener").substringBefore("invalidateRequest =")
        assertTrue(edits.contains("saveIdentity()"))
        assertFalse(ui.substringAfter("fun controls(").substringBefore("fun renderParts()").contains("customRole.setText"))
        assertTrue(ui.contains("history.recall(selectedTalker, focusMessageId, owner.historyScope)"))
        assertTrue(ui.contains("history.remember(it, owner.historyScope)"))
        assertTrue(ui.substringAfter("val activeConfig =").substringBefore("val accepted =").contains("verifyAccount()"))
    }
}
