package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class MessageMetadataTest {
    open class Fields(
        @JvmField val field_type: Int = 1,
        @JvmField val field_isSend: Int = 0,
        @JvmField val field_content: String? = "你好",
        @JvmField val field_talker: String? = "friend",
    )
    class Inherited : Fields(field_content = "12:30")

    @Test fun `read inherited message fields and preserve time-like text`() {
        assertEquals("12:30", MessageMetadata.read(Inherited())?.incomingText())
    }
    @Test fun `all media and app types are skipped even when they contain readable text`() {
        listOf(3, 34, 43, 62, 47, 49, 1090519089, 822083633, 10000, 0).forEach {
            assertNull("type=$it", MessageMetadata.read(Fields(it, field_content = "今晚见.pdf"))?.incomingText())
        }
    }
    @Test fun `our own messages and unknown direction are skipped`() {
        listOf(1, 2, -1).forEach { assertNull(MessageMetadata.read(Fields(field_isSend = it))?.incomingText()) }
    }
    @Test fun `group sender prefix is excluded`() {
        assertEquals("你好\n明天见", MessageMetadata.read(Fields(field_talker = "group@chatroom",
            field_content = "wxid_sender:\n你好\n明天见"))?.incomingText())
    }
    @Test fun `private chat colon and bracket text are retained`() {
        assertEquals("[好的]", MessageMetadata.read(Fields(field_content = "[好的]"))?.incomingText())
        assertEquals("备注:\n你好", MessageMetadata.read(Fields(field_content = "备注:\n你好"))?.incomingText())
    }
    @Test fun `unrecognized item and absent content fail closed`() {
        assertNull(MessageMetadata.read(Any()))
        assertNull(MessageMetadata.read(null))
        assertNull(MessageMetadata.read(Fields(field_content = null))?.incomingText())
        assertNull(MessageMetadata.read(Fields(field_content = "  "))?.incomingText())
    }
    @Test fun `missing conversation identity fails closed`() {
        assertNull(MessageMetadata.read(Fields(field_talker = null))?.incomingText())
    }
}
