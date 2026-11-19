package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 流式回复：第一个候选在整段 JSON 写完之前就要能被取出来显示。 */
class ReplyStreamTest {
    @Test fun `complete first candidate is extracted from a partial payload`() {
        assertEquals("最推荐的候选", ReplyHttpClient.firstReply("{\"replies\":[\"最推荐的候选\""))
    }

    @Test fun `incomplete string yields nothing`() {
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[\"还没写完"))
        assertNull(ReplyHttpClient.firstReply("{\"reason\":\"没有候选\""))
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[]"))
    }

    @Test fun `escaped quotes and unicode survive`() {
        assertEquals("他说\"好\"", ReplyHttpClient.firstReply("{\"replies\":[\"他说\\\"好\\\"\",\"第二\"]}"))
        assertEquals("好", ReplyHttpClient.firstReply("{\"replies\":[\"\\u597d\"],\"reason\":\"x\"}"))
    }

    @Test fun `blank candidate is ignored`() {
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[\"   \""))
    }
}
