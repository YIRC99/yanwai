package dev.jev.wechatmood.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Host entry wiring only; actual long-press dispatch remains a device acceptance check. */
class ReplyEntrySafetyTest {
    private val sourceRoot = File("src/main/java/dev/jev/wechatmood")
    private fun source(name: String) = File(sourceRoot, "hook/$name.kt").readText()

    @Test fun `production does not install or contain a plus panel integration`() {
        val forbidden = listOf("NativeReplyPlus", "ReplyPlusEntry", "ReplyPlusItems", "ReplyPlusLabels",
            "ReplyPlusOwnership", "ReplyPlusRebuildGuard", "suggestReplyFromPlus")
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { symbol -> assertFalse("${file.name} still contains $symbol", text.contains(symbol)) }
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
