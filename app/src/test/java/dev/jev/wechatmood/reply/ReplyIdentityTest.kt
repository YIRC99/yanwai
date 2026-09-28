package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.PersistentAnalysisCacheTest.Jdbc
import dev.jev.wechatmood.hook.MessageMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyIdentityTest {
    @get:Rule val folder = TemporaryFolder()
    private val account = AnalysisCacheKey.digest("account-a")
    private val alice = requireNotNull(ReplyContactKey.of(account, "wxid_alice"))
    private val bob = requireNotNull(ReplyContactKey.of(account, "wxid_bob"))
    private val anotherAccount = requireNotNull(ReplyContactKey.of(AnalysisCacheKey.digest("account-b"), "wxid_alice"))

    @Test fun `selection alone survives database reopen and separates contacts and accounts`() {
        val file = folder.newFile()
        ReplyIdentityStore(Jdbc(file)).use {
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.FRIEND))
            it.save(bob, ReplyIdentitySetting(ReplyRelationship.COLLEAGUE))
            it.save(anotherAccount, ReplyIdentitySetting(ReplyRelationship.ELDER))
        }
        ReplyIdentityStore(Jdbc(file)).use {
            assertEquals(ReplyRelationship.FRIEND, it.find(alice).relationship)
            assertEquals(ReplyRelationship.COLLEAGUE, it.find(bob).relationship)
            assertEquals(ReplyRelationship.ELDER, it.find(anotherAccount).relationship)
            // A display name is deliberately absent from the key and API.
            assertEquals(it.find(alice), it.find(requireNotNull(ReplyContactKey.of(account, "wxid_alice"))))
            assertEquals(ReplyIdentitySetting(), it.find(requireNotNull(ReplyContactKey.of(account, "wxid_new"))))
        }
    }

    @Test fun `custom draft keeps exact text on restart while whitespace cannot generate and clear removes it`() {
        val file = folder.newFile()
        val draft = ReplyIdentitySetting(ReplyRelationship.OTHER, " 前同事  ")
        ReplyIdentityStore(Jdbc(file)).use { it.save(alice, draft) }
        ReplyIdentityStore(Jdbc(file)).use {
            assertEquals(draft, it.find(alice))
            it.save(alice, draft.copy(customText = "相亲对象"))
            assertEquals("相亲对象", it.find(alice).customText)
            it.save(alice, draft.copy(customText = "   "))
            assertFalse(ReplyComposition(identity = it.find(alice)).hasValidRelationship)
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.UNSPECIFIED, "遗留文字"))
            assertEquals(ReplyIdentitySetting(), it.find(alice))
        }
        ReplyIdentityStore(Jdbc(file)).use { assertEquals(ReplyIdentitySetting(), it.find(alice)) }
    }

    @Test fun `unknown accounts unstable targets and group identities cannot get persistent keys`() {
        listOf(null, "", "memory:old", "pending:old").forEach { assertNull(ReplyContactKey.of(it, "wxid_alice")) }
        listOf(null, "", "张三", "Some Name", "123@chatroom").forEach { assertNull(ReplyContactKey.of(account, it)) }
    }

    @Test fun `new setting overrides old reply without relabeling it including after explicit clear`() {
        val old = RememberedReply(ReplyContext("wxid_alice", emptyList()), ReplySuggestion("好", ""),
            relationship = ReplyRelationship.OTHER, customRelationship = "前同事")
        val c = ReplyComposition(old, ReplyIdentitySetting(ReplyRelationship.OTHER, "相亲对象"))
        assertEquals("相亲对象", c.customRelationship)
        assertEquals("前同事", c.result!!.customRelationship)
        assertFalse(c.canUse)
        assertFalse(ReplyComposition(old, ReplyIdentitySetting()).canUse)
    }

    @Test fun `scope guard rejects account chat and lifecycle changes including switching away and back`() {
        val owner = ReplyIdentityOwner("epoch-a", account, "wxid_alice")
        assertTrue(owner.isCurrent("epoch-a", "wxid_alice"))
        assertFalse(owner.isCurrent("epoch-b", "wxid_alice"))
        assertFalse(owner.isCurrent("epoch-a", "wxid_bob"))
        assertFalse(owner.acceptsAccount(AnalysisCacheKey.digest("account-b")))
        assertFalse(owner.acceptsAccount(null))
        assertTrue(owner.acceptsAccount(account))
    }

    @Test fun `account resolution requires exact anchor and rejects ambiguous databases`() {
        val row = MessageMetadata(1, 1, "你好", "wxid_alice", 9, 1234)
        val page = ReplyContext.collect(row.talker, listOf(row))
        fun source(record: MessageMetadata) = ReplyHistoryQuery { _, _ -> listOf(record) }
        assertEquals(account, ReplyAccountIdentity.resolve(page, listOf(account to source(row))))
        assertNull(ReplyAccountIdentity.resolve(page, listOf(account to source(row.copy(content = "别的账号")))))
        assertNull(ReplyAccountIdentity.resolve(page, listOf(account to source(row),
            AnalysisCacheKey.digest("account-b") to source(row))))
        assertNull(ReplyAccountIdentity.resolve(page.copy(historyAnchor = null), listOf(account to source(row))))
    }

    @Test fun `reply history has no cross-account or legacy fallback`() {
        val history = ReplyHistory()
        val old = RememberedReply(ReplyContext("wxid_alice", emptyList()), ReplySuggestion("旧", ""))
        history.remember(old)
        assertNull(history.recall("wxid_alice", accountScope = account))
        history.remember(old, account)
        assertNotNull(history.recall("wxid_alice", accountScope = account))
        assertNull(history.recall("wxid_alice", accountScope = "other"))
    }

    @Test fun `queued edits survive drawer close and load waits for newest write without rebinding`() {
        val tasks = java.util.ArrayDeque<() -> Unit>()
        ReplyIdentityStore(Jdbc(folder.newFile())).use { store ->
            val queue = ReplyIdentityQueue({ tasks.add(it) }, store::find, store::save)
            val ownerA = ReplyIdentityOwner("page-a", account, "wxid_alice")
            val ownerB = ReplyIdentityOwner("page-b", account, "wxid_bob")
            queue.save(ownerA, ReplyIdentitySetting(ReplyRelationship.OTHER, "前同"), { account }) { assertTrue(it) }
            queue.save(ownerA, ReplyIdentitySetting(ReplyRelationship.OTHER, "前同事"), { account }) { assertTrue(it) }
            queue.save(ownerB, ReplyIdentitySetting(ReplyRelationship.COLLEAGUE), { account }) { assertTrue(it) }
            var loaded: ReplyIdentitySetting? = null
            queue.load(alice) { loaded = it.getOrThrow() }
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
            assertEquals("前同事", loaded!!.customText)
            assertEquals(ReplyRelationship.COLLEAGUE, store.find(bob).relationship)
            queue.save(ownerA, ReplyIdentitySetting(), { account }) { assertTrue(it) }
            queue.load(alice) { loaded = it.getOrThrow() }
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
            assertEquals(ReplyIdentitySetting(), loaded)
        }
    }

    @Test fun `failed verification never writes and storage failures are not reported as success`() {
        var writes = 0
        val owner = ReplyIdentityOwner("page", account, "wxid_alice")
        val queue = ReplyIdentityQueue({ it() }, { error("offline") }, { _, _ -> writes++; error("disk full") })
        queue.save(owner, ReplyIdentitySetting(ReplyRelationship.FRIEND), { null }) { assertFalse(it) }
        assertEquals(0, writes)
        queue.save(owner, ReplyIdentitySetting(ReplyRelationship.FRIEND), { account }) { assertFalse(it) }
        assertEquals(1, writes)
        queue.load(alice) { assertTrue(it.isFailure) }
    }
}
