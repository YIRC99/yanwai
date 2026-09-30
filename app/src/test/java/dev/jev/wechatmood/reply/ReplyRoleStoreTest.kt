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

    @Test fun `new app and drawer share all eight default roles`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val defaults = ReplyRelationship.entries.filter { r -> r != ReplyRelationship.UNSPECIFIED && r != ReplyRelationship.OTHER }
            assertEquals(defaults.map { r -> r.label }.toSet(), it.roles.templates().map { r -> r.name }.toSet())
            assertTrue(it.roles.templates().all { r -> r.background.isNotBlank() })
            assertEquals(it.roles.templates().map { r -> r.id }, it.roles.list().map { r -> r.id })
        }
    }

    @Test fun `edited and deleted defaults stay changed after reopening`() {
        val file = folder.newFile()
        ReplyIdentityStore(Jdbc(file)).use {
            val friend = it.roles.templates().single { r -> r.name == "朋友" }
            it.roles.save(friend.id, "老朋友", "认识十年", friend.revision)
            val colleague = it.roles.templates().single { r -> r.name == "同事" }
            it.roles.delete(colleague.id, colleague.revision)
        }
        ReplyIdentityStore(Jdbc(file)).use {
            assertEquals(7, it.roles.templates().size)
            assertTrue(it.roles.templates().any { r -> r.name == "老朋友" && r.background == "认识十年" })
            assertFalse(it.roles.templates().any { r -> r.name == "朋友" || r.name == "同事" })
        }
    }

    @Test fun `new roles can be listed edited searched by name and deleted after reopening`() {
        val file = folder.newFile()
        val id = ReplyIdentityStore(Jdbc(file)).use {
            it.roles.save(null, " 前同事 ", "一起做过项目").id
        }
        ReplyIdentityStore(Jdbc(file)).use {
            val role = it.roles.list().single { value -> value.name.contains("前同事") }
            assertEquals(id, role.id)
            assertEquals("一起做过项目", role.background)
            val updated = it.roles.save(id, "老同事", "喜欢徒步", role.revision)
            assertEquals("喜欢徒步", it.roles.find(id)!!.background)
            assertNotEquals(role.revision, updated.revision)
            it.roles.delete(id, updated.revision)
            assertNull(it.roles.find(id))
            assertEquals(8, it.roles.templates().size)
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
    @Test fun `choosing a library role binds that contact to the current role without affecting unbound contacts`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val role = it.roles.save(null, "合作伙伴", "项目沟通简洁")
            val applied = it.roles.apply(role.id, role.revision, alice)
            assertEquals("合作伙伴", applied.identity.customText)
            assertEquals("项目沟通简洁", applied.background.text)
            assertEquals(ReplyIdentitySetting(), it.find(bob))
            it.roles.save(role.id, "合作伙伴", "新模板背景", role.revision)
            assertEquals("新模板背景", it.background(alice).text)
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
            assertEquals(9, it.roles.templates().size)
            val summary = ReplyRole.decode(role.encode(includeBackground = false))
            assertEquals("同事", summary.name)
            assertEquals("", summary.background)
            assertEquals(role.revision, summary.revision)
            assertFalse(role.toString().contains("私人背景"))
        }
    }

    @Test fun `saving a drawer role appears in both catalogs and updates without duplicates after reopening`() {
        val file = folder.newFile()
        val identity = ReplyIdentitySetting(ReplyRelationship.OTHER, "球友")
        val id = ReplyIdentityStore(Jdbc(file)).use {
            val saved = it.roles.saveFromChat(alice, identity, "每周打球")
            assertEquals(saved.identity, it.find(alice))
            assertEquals("每周打球", it.background(alice).text)
            val role = it.roles.templates().single { r -> r.name == "球友" }
            assertTrue(it.roles.list().any { r -> r.id == role.id })
            role.id
        }
        ReplyIdentityStore(Jdbc(file)).use {
            val old = it.roles.find(id)!!
            it.roles.saveFromChat(alice, it.find(alice), "周末打球")
            assertEquals(9, it.roles.templates().size)
            assertEquals("周末打球", it.roles.find(id)!!.background)
            assertThrows(IllegalStateException::class.java) { it.roles.apply(id, old.revision, bob) }
            val updated = it.roles.save(id, "老球友", "喜欢双打", it.roles.find(id)!!.revision)
            val applied = it.roles.apply(id, updated.revision, bob)
            assertEquals("老球友", applied.identity.customText)
            assertEquals("喜欢双打", applied.background.text)
            assertEquals("喜欢双打", it.background(alice).text)
            it.roles.delete(id, updated.revision)
            assertFalse(it.roles.templates().any { r -> r.id == id })
            assertEquals(ReplyIdentitySetting(), it.find(alice))
            assertEquals("", it.background(alice).text)
        }
    }

    @Test fun `drawer save rolls back all data when role write fails`() {
        val jdbc = Jdbc(folder.newFile())
        var fail = false
        val db = object : AnalysisCacheDatabase by jdbc {
            override fun execute(sql: String, args: List<String>) {
                if (fail && sql.startsWith("INSERT OR REPLACE INTO reply_roles")) error("disk full")
                jdbc.execute(sql, args)
            }
        }
        ReplyIdentityStore(db).use {
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.FRIEND))
            it.saveBackground(alice, "旧背景")
            fail = true
            assertThrows(IllegalStateException::class.java) {
                it.roles.saveFromChat(alice, ReplyIdentitySetting(ReplyRelationship.OTHER, "同学"), "新背景")
            }
            assertEquals(ReplyRelationship.FRIEND, it.find(alice).relationship)
            assertEquals("旧背景", it.background(alice).text)
            assertEquals(8, it.roles.templates().size)
        }
    }

    @Test fun `defaults retain relationship guidance after background editing and serialization`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val friend = it.roles.templates().single { r -> r.name == "朋友" }
            val edited = it.roles.save(friend.id, friend.name, "喜欢运动", friend.revision)
            assertEquals(ReplyRelationship.FRIEND, ReplyRole.decode(edited.encode()).relationship)
            assertEquals(ReplyRelationship.FRIEND, it.roles.apply(edited.id, edited.revision, alice).identity.relationship)
            val renamed = it.roles.save(edited.id, "导师", "工作沟通", edited.revision)
            assertEquals(ReplyRelationship.OTHER, it.roles.apply(renamed.id, renamed.revision, alice).identity.relationship)
        }
    }

    @Test fun `switching to an empty default role clears the previous roles background`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            it.saveBackground(alice, "认识十年，避免提起旧事")
            val friend = it.roles.templates().single { r -> r.name == "朋友" }
            val empty = it.roles.save(friend.id, friend.name, "", friend.revision)
            val applied = it.roles.apply(empty.id, empty.revision, alice)
            assertEquals("", applied.background.text)
            assertEquals(applied.background, it.background(alice))
        }
    }

    @Test fun `stale drawer cannot overwrite or resurrect an edited or deleted selected role`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val role = it.roles.save(null, "球友", "原背景")
            val selected = it.roles.apply(role.id, role.revision, alice)
            val edited = it.roles.save(role.id, role.name, "APP 新背景", role.revision)
            assertThrows(IllegalStateException::class.java) { it.roles.saveFromChat(alice, selected.identity, "旧抽屉覆盖") }
            it.roles.delete(edited.id, edited.revision)
            assertThrows(IllegalStateException::class.java) { it.roles.saveFromChat(alice, selected.identity, "重新创建") }
            assertEquals(8, it.roles.templates().size)
        }
    }

    @Test fun `new custom roles in one conversation receive independent ids and selection survives reopen`() {
        val file = folder.newFile()
        val ids = ReplyIdentityStore(Jdbc(file)).use {
            val first = it.roles.saveFromChat(alice, ReplyIdentitySetting(ReplyRelationship.OTHER, "同学"), "学生时代")
            val second = it.roles.saveFromChat(alice, ReplyIdentitySetting(ReplyRelationship.OTHER, "同学"), "培训认识")
            assertNotEquals(first.identity.roleId, second.identity.roleId)
            first.identity.roleId!! to second.identity.roleId!!
        }
        ReplyIdentityStore(Jdbc(file)).use {
            assertEquals(ids.second, it.find(alice).roleId)
            assertEquals("培训认识", it.background(alice).text)
            assertEquals("学生时代", it.roles.find(ids.first)!!.background)
            assertEquals(10, it.roles.list().size)
        }
    }

    @Test fun `upgrade fills only unchanged empty presets once and preserves deleted or edited roles`() {
        val file = folder.newFile()
        ReplyIdentityStore(Jdbc(file)).use {
            val friend = it.roles.templates().single { r -> r.name == "朋友" }
            it.roles.save(friend.id, friend.name, "", friend.revision)
            val colleague = it.roles.templates().single { r -> r.name == "同事" }
            it.roles.save(colleague.id, colleague.name, "我写的背景", colleague.revision)
            val elder = it.roles.templates().single { r -> r.name == "长辈" }
            it.roles.delete(elder.id, elder.revision)
        }
        Jdbc(file).use { it.execute("DELETE FROM reply_role_migrations WHERE migration = ?", listOf("defaults-background-v1")) }
        ReplyIdentityStore(Jdbc(file)).use {
            val friend = it.roles.templates().single { r -> r.name == "朋友" }
            assertTrue(friend.background.isNotBlank())
            assertEquals("我写的背景", it.roles.templates().single { r -> r.name == "同事" }.background)
            assertFalse(it.roles.templates().any { r -> r.name == "长辈" })
            it.roles.save(friend.id, friend.name, "", friend.revision)
        }
        ReplyIdentityStore(Jdbc(file)).use {
            assertEquals("", it.roles.templates().single { r -> r.name == "朋友" }.background)
        }
    }

    @Test fun `drawer edits the selected role and switching between roles keeps backgrounds separate`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val a = it.roles.save(null, "甲角色", "甲背景")
            val b = it.roles.save(null, "乙角色", "乙背景")
            val selected = it.roles.apply(a.id, a.revision, alice)
            it.roles.saveFromChat(alice, selected.identity, "甲的新背景")
            assertEquals("甲的新背景", it.roles.find(a.id)!!.background)
            assertEquals("乙背景", it.roles.apply(b.id, b.revision, alice).background.text)
            val latest = it.roles.find(a.id)!!
            assertEquals("甲的新背景", it.roles.apply(a.id, latest.revision, alice).background.text)
            assertEquals(10, it.roles.templates().size)
        }
    }

    @Test fun `upgrade seeds defaults once while preserving legacy templates and contacts`() {
        val file = folder.newFile()
        val id = "role:00000000-0000-0000-0000-000000000001"
        Jdbc(file).use {
            it.execute("CREATE TABLE reply_roles (role_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            val legacy = org.json.JSONObject(ReplyRole(id, "同学", "旧模板", "v1").encode()).apply { remove("relationship") }.toString()
            it.execute("INSERT INTO reply_roles(role_id, payload) VALUES(?, ?)", listOf(id, legacy))
            it.execute("CREATE TABLE reply_identities (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            it.execute("INSERT INTO reply_identities(contact_key, payload) VALUES(?, ?)", listOf(alice.value, ReplyIdentitySetting(ReplyRelationship.FRIEND).encode()))
        }
        repeat(2) {
            ReplyIdentityStore(Jdbc(file)).use {
                assertEquals(9, it.roles.templates().size)
                assertEquals("旧模板", it.roles.find(id)!!.background)
                assertEquals(ReplyRelationship.OTHER, it.roles.find(id)!!.relationship)
                assertEquals(ReplyRelationship.FRIEND, it.find(alice).relationship)
            }
        }
    }

    @Test fun `same named roles from different contacts do not overwrite each other and drafts stay private`() {
        ReplyIdentityStore(Jdbc(folder.newFile())).use {
            val identity = ReplyIdentitySetting(ReplyRelationship.OTHER, "同学")
            it.save(alice, identity)
            assertFalse(it.roles.templates().any { r -> r.name == "同学" })
            it.roles.saveFromChat(alice, identity, "甲的背景")
            it.roles.saveFromChat(bob, identity, "乙的背景")
            assertEquals(setOf("甲的背景", "乙的背景"), it.roles.templates().filter { r -> r.name == "同学" }.map { r -> r.background }.toSet())
        }
    }
}
