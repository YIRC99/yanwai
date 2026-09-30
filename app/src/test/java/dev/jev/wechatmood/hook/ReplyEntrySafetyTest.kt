package dev.jev.wechatmood.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Host entry wiring only; actual long-press dispatch remains a device acceptance check. */
class ReplyEntrySafetyTest {
    private val sourceRoot = File("src/main/java/dev/jev/wechatmood")
    private fun source(name: String) = File(sourceRoot, "hook/$name.kt").readText()

    @Test fun `production does not install native grid injection or duplicate entry implementations`() {
        val forbidden = listOf("NativeReplyPlus", "ReplyPlusEntry", "ReplyPlusItems", "ReplyPlusLabels",
            "ReplyPlusOwnership", "ReplyPlusRebuildGuard", "suggestReplyFromPlus")
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { symbol -> assertFalse("${file.name} still contains $symbol", text.contains(symbol)) }
        }
    }

    @Test fun `independent plus row is updated and cleared with its owning chat`() {
        val reply = source("ReplyHostUi")
        assertTrue(reply.contains("plusEntry.update(footer())"))
        assertTrue(reply.substringAfter("fun hide()").contains("plusEntry.clear()"))
        assertTrue(reply.contains("ReplyPlusRow(activity) { open() }"))
    }

    @Test fun `plus row uses the historical inner panel mount and restores native content`() {
        val file = File(sourceRoot, "hook/ReplyPlusRow.kt")
        assertTrue("Independent row must be implemented", file.exists())
        val row = file.readText()
        assertTrue(row.contains("isShown"))
        assertTrue(row.contains("removeOnGlobalLayoutListener"))
        assertTrue(row.contains("found.removeView(content)"))
        assertTrue(row.contains("originalParams"))
        assertTrue(row.contains("target.addView(content, originalParams)"))
        assertTrue(row.contains("host.parent === target && target.childCount == 1"))
        assertTrue(row.contains("status(\"ATTACHED\")"))
        assertFalse(row.contains("ChatFooterBottom"))
        assertFalse(row.contains("getLocalVisibleRect"))
        for (operation in listOf("XposedBridge", "setAdapter(", "setOnItemClickListener(",
            "removeView(panel)", "translationY =")) {
            assertFalse("Must not rewrite native grid: $operation", row.contains(operation))
        }
    }

    @Test fun `header long press still opens replies without enabling automatic analysis`() {
        val host = source("HostUi")
        assertTrue(host.contains("setOnLongClickListener { showActions(); true }"))
        assertTrue(host.contains("帮我回 / 上次建议"))
        assertTrue(host.contains("0 -> replyUi.open()"))
        assertTrue(host.contains("fun suggestReply(focusMessageId: Long? = null) = replyUi.open(focusMessageId)"))
        assertTrue(source("MessageMenu").contains("MessageSniffer.suggestReply(view, it)"))
        assertTrue(source("MessageSniffer").contains("ui?.suggestReply(target.messageId) == true"))
    }

    @Test fun `app guide directs users to the available header entry`() {
        val guide = File("src/main/res/layout/activity_main.xml").readText()
        assertFalse(guide.contains("点微信「＋」里的「帮我回」"))
        assertTrue(guide.contains("长按右上角「分析」"))
        assertTrue(guide.contains("帮我回 / 上次建议"))
    }
}
