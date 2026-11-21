package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.PersistentAnalysisCacheTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 提醒要能点进对的那个聊天：哈希键必须能换回 talker，而且只换得到记过的那些。 */
class RelationDueStoreTest {
    private lateinit var file: java.io.File

    @Before fun setUp() {
        file = java.io.File.createTempFile("relation-due", ".db").also { it.delete() }
    }

    private fun store() = RelationFactStore(PersistentAnalysisCacheTest.Jdbc(file))

    @Test fun `due contacts carry talker for the deep link`() {
        val store = store()
        val key = ReplyContactKey("b".repeat(64))
        val today = FactTimes.startOfDay(System.currentTimeMillis()) + 19 * 3_600_000L
        store.replace(key, listOf(RelationFact(kind = "约定", text = "晚上一起吃饭", occurredAt = today, updatedAt = 1)))
        store.rememberContact(key, "wxid_abc123")

        val due = store.dueContacts()
        assertEquals(1, due.size)
        assertEquals(key.value, due.first().key)
        assertEquals("wxid_abc123", due.first().talker)
    }

    @Test fun `contact without remembered talker still reminds but without a target`() {
        val store = store()
        val key = ReplyContactKey("c".repeat(64))
        val today = FactTimes.startOfDay(System.currentTimeMillis()) + 19 * 3_600_000L
        store.replace(key, listOf(RelationFact(kind = "约定", text = "打电话", occurredAt = today, updatedAt = 1)))

        val due = store.dueContacts()
        assertEquals(1, due.size)
        assertNull(due.first().talker)
    }

    @Test fun `empty name does not wipe the stored remark name`() {
        val store = store()
        val key = ReplyContactKey("e".repeat(64))
        val today = FactTimes.startOfDay(System.currentTimeMillis()) + 19 * 3_600_000L
        store.rememberContact(key, "wxid_x", "张三")
        store.rememberContact(key, "wxid_x", "")
        store.replace(key, listOf(RelationFact(kind = "约定", text = "吃饭", occurredAt = today, updatedAt = 1)))

        assertEquals("张三", store.dueContacts().first().name)
    }

    @Test fun `marking done clears it from the next reminder`() {
        val store = store()
        val key = ReplyContactKey("d".repeat(64))
        val today = FactTimes.startOfDay(System.currentTimeMillis()) + 19 * 3_600_000L
        store.replace(key, listOf(RelationFact(kind = "约定", text = "交材料", occurredAt = today, updatedAt = 1)))
        val factId = store.dueContacts().first().facts.first().factId

        assertEquals(1, store.setStatusAll(key, listOf(factId), RelationFact.STATUS_DONE))
        assertEquals(0, store.dueContacts().size)
    }
}
