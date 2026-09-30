package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.AnalysisCacheDatabase
import dev.jev.wechatmood.core.PersistentAnalysisCacheTest.Jdbc
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyRoleStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val alice = ReplyContactKey(AnalysisCacheKey.digest("uid", "account-a", "alice"))
    private val bob = ReplyContactKey(AnalysisCacheKey.digest("uid", "account-a", "bob"))

    @Test fun `new roles can be listed edited searched by name and deleted after reopening`() {
        val file = folder.newFile()
        val id = ReplyIdentityStore(Jdbc(file)).use {
            it.roles.save(null, " 前同事 ", "一起做过项目").id
        }
        ReplyIdentityStore(Jdbc(file)).use {
            val role = it.roles.list().single { value -> value.name.contains("同事") }
            assertEquals(id, role.id)
            assertEquals("一起做过项目", role.background)
            val updated = it.roles.save(id, "老同事", "喜欢徒步", role.revision)
            assertEquals("喜欢徒步", it.roles.find(id)!!.background)
            assertNotEquals(role.revision, updated.revision)
            it.roles.delete(id, updated.revision)
            assertTrue(it.roles.list().isEmpty())
            assertEquals(3L, it.roles.revision())
        }
    }
    @Test fun `existing contact roles are editable without moving or rekeying their data`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.OTHER, "相亲对象"))
            it.saveBackground(alice, "喜欢安静")
            it.save(bob, ReplyIdentitySetting(ReplyRelationship.FRIEND))
            it.saveBackground(bob, "不能串到别人")
            val row = it.roles.list().single { role -> role.name == "相亲对象" }
            val edited = it.roles.save(row.id, "女朋友", "喜欢画画", row.revision)
            assertEquals("女朋友", it.find(alice).customText)
            assertEquals("喜欢画画", it.background(alice).text)
            assertEquals("不能串到别人", it.background(bob).text)
            it.roles.delete(edited.id, edited.revision)
            assertEquals(ReplyIdentitySetting(), it.find(alice))
            assertTrue(it.background(alice).text.isEmpty())
            assertEquals(ReplyRelationship.FRIEND, it.find(bob).relationship)
        }
    }
    @Test fun `choosing a library role explicitly copies identity and background to only one contact`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val role = it.roles.save(null, "合作伙伴", "项目沟通简洁")
            val applied = it.roles.apply(role.id, role.revision, alice)
            assertEquals("合作伙伴", applied.identity.customText)
            assertEquals("项目沟通简洁", applied.background.text)
            assertEquals(ReplyIdentitySetting(), it.find(bob))
            it.roles.save(role.id, "合作伙伴", "新模板背景", role.revision)
            assertEquals("项目沟通简洁", it.background(alice).text)
        }
    }
    @Test fun `stale edits selections and deletes cannot overwrite newer personal data`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val role = it.roles.save(null, "朋友", "旧背景")
            it.roles.save(role.id, "朋友", "新背景", role.revision)
            assertThrows(IllegalStateException::class.java) { it.roles.save(role.id, "朋友", "覆盖", role.revision) }
            assertThrows(IllegalStateException::class.java) { it.roles.delete(role.id, role.revision) }
            assertThrows(IllegalStateException::class.java) { it.roles.apply(role.id, role.revision, alice) }
            assertEquals("新背景", it.roles.find(role.id)!!.background)
            assertThrows(IllegalArgumentException::class.java) { it.roles.save(null, " ", "") }
            assertThrows(IllegalArgumentException::class.java) { it.roles.save(null, "朋友", "a".repeat(2001)) }
        }
    }

    @Test fun `applying a role rolls back identity when background persistence fails`() {
        val jdbc = Jdbc(folder.newFile())
        var fail = false
        val db = object : AnalysisCacheDatabase by jdbc {
            override fun execute(sql: String, args: List<String>) {
                if (fail && sql.startsWith("INSERT OR REPLACE INTO contact_backgrounds")) error("disk full")
                jdbc.execute(sql, args)
            }
        }
        ReplyIdentityStore(db).use {
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.FRIEND))
            it.saveBackground(alice, "原背景")
            val role = it.roles.save(null, "同事", "新背景")
            fail = true
            assertThrows(IllegalStateException::class.java) { it.roles.apply(role.id, role.revision, alice) }
            assertEquals(ReplyRelationship.FRIEND, it.find(alice).relationship)
            assertEquals("原背景", it.background(alice).text)
            assertEquals(1L, it.roles.revision())
        }
    }

    @Test fun `contact edits detect host changes and preserve preset guidance when only background changes`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.FRIEND))
            val original = it.roles.find("contact:${alice.value}")!!
            it.saveBackground(alice, "微信内刚保存")
            assertThrows(IllegalStateException::class.java) { it.roles.save(original.id, original.name, "旧页面覆盖", original.revision) }
            val current = it.roles.find(original.id)!!
            it.roles.save(current.id, current.name, "新背景", current.revision)
            assertEquals(ReplyRelationship.FRIEND, it.find(alice).relationship)
            assertEquals("新背景", it.background(alice).text)
        }
    }

    @Test fun `host role lists contain only library summaries and never stored contact backgrounds`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val role = it.roles.save(null, "同事", "私人背景")
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.OTHER, "联系人私有身份"))
            it.saveBackground(alice, "联系人私有背景")
            assertEquals(1, it.roles.templates().size)
            val summary = ReplyRole.decode(role.encode(includeBackground = false))
            assertEquals("同事", summary.name)
            assertEquals("", summary.background)
            assertEquals(role.revision, summary.revision)
            assertFalse(role.toString().contains("私人背景"))
        }
    }
}
