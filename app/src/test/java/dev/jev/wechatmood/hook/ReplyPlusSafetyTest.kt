package dev.jev.wechatmood.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Android/Xposed entry points cannot run in these JVM tests. Guard native entry wiring
 * alongside executable data and paging tests in ReplyPlusItemsTest.
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

    @Test fun `native entry does not wrap resize overlay or replace native grid listeners`() {
        val entry = source("NativeReplyPlus")
        for (operation in listOf("removeView(", "addView(", "setLayoutParams(", "layoutParams =",
            "PopupWindow", "setOnItemClickListener(", "setOnTouchListener(", "setNumColumns(")) {
            assertFalse("Plus entry must not interfere with host geometry or listeners: $operation", entry.contains(operation))
        }
        assertTrue(entry.contains("ReplyPlusItems.supports("))
        assertTrue(entry.contains("if (!owns(item)) return"))
        assertTrue(entry.contains("XposedBridge.invokeOriginalMethod(c.rebuild"))
        assertTrue(File(sourceRoot, "xposed/HookEntry.kt").readText().contains("NativeReplyPlus.install(context)"))
    }

    @Test fun `header and message menu still open the existing reply interface`() {
        val host = source("HostUi")
        assertTrue(host.contains("帮我回 / 上次建议"))
        assertTrue(host.contains("0 -> replyUi.open()"))
        assertTrue(host.contains("fun suggestReply(focusMessageId: Long? = null) = replyUi.open(focusMessageId)"))
        assertTrue(source("MessageMenu").contains("MessageSniffer.suggestReply(view, it)"))
        assertTrue(source("MessageSniffer").contains("ui?.suggestReply(target.messageId) == true"))
    }

    @Test fun `dex optimized labels must not require a reflective constructor`() {
        // Actual 8.0.71 DEX y has no declared constructor; x already initializes its labels.
        val entry = source("NativeReplyPlus")
        assertFalse("The host label has no public constructor in DEX", entry.contains("label.getConstructor()"))
        assertFalse("Reuse labels created by the host item constructor", entry.contains("labelClass.getConstructor()"))
    }
}
