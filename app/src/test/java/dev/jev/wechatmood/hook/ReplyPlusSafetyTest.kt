package dev.jev.wechatmood.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Android/Xposed entry points cannot run in these JVM tests. Guard the safe fallback's
 * wiring: until a host grid contract is verified, no code may target the plus panel.
 * This does not verify host touch dispatch; that still requires device acceptance.
 */
class ReplyPlusSafetyTest {
    private val sourceRoot = File("src/main/java/dev/jev/wechatmood")
    private fun source(name: String) = File(sourceRoot, "hook/$name.kt").readText()

    @Test fun `chat refresh and teardown never install or restore the legacy panel wrapper`() {
        val reply = source("ReplyHostUi")
        assertFalse("Periodic refresh still owns the unsafe plus-panel entry", reply.contains("ReplyPlusEntry"))
        assertFalse("Lifecycle still mutates the plus panel", reply.contains("plusEntry."))
    }

    @Test fun `unverified native panel has no injection implementation including dormant code`() {
        val panelAnchors = Regex("\\b(AppPanel|AppGrid|MMFlipper)\\b")
        val offenders = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { panelAnchors.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).path }.toList()
        assertEquals("Native grid integration needs verified data, paging and click contracts first",
            emptyList<String>(), offenders)
    }

    @Test fun `header and message menu still open the existing reply interface`() {
        val host = source("HostUi")
        assertTrue(host.contains("帮我回 / 上次建议"))
        assertTrue(host.contains("0 -> replyUi.open()"))
        assertTrue(host.contains("fun suggestReply(focusMessageId: Long? = null) = replyUi.open(focusMessageId)"))
        assertTrue(source("MessageMenu").contains("MessageSniffer.suggestReply(view, it)"))
        assertTrue(source("MessageSniffer").contains("ui?.suggestReply(target.messageId) == true"))
    }
}
