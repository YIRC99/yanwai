package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ContactBackgroundTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key(account: String, talker: String) = requireNotNull(ReplyContactKey.of(AnalysisCacheKey.digest(account), talker))
    @Test fun `background survives reopen isolated from roles accounts and contacts and clear advances revision`() {
        val file = folder.newFile()
        val a = key("one", "wxid_a"); val b = key("one", "wxid_b"); val other = key("two", "wxid_a")
        var revision = ""
        ReplyIdentityStore(PersistentAnalysisCacheTest.Jdbc(file)).use {
            revision = it.saveBackground(a, "喜欢散步").revision
            it.saveBackground(b, "同事"); it.saveBackground(other, "同学")
            it.save(a, ReplyIdentitySetting(ReplyRelationship.FRIEND))
            assertEquals("喜欢散步", it.background(a).text)
            it.save(a, ReplyIdentitySetting())
        }
        ReplyIdentityStore(PersistentAnalysisCacheTest.Jdbc(file)).use {
            assertEquals("喜欢散步", it.background(a).text)
            assertEquals(revision, it.background(a).revision)
            assertEquals("同事", it.background(b).text); assertEquals("同学", it.background(other).text)
            assertEquals(revision, it.saveBackground(a, "喜欢散步").revision)
            assertNotEquals(revision, it.saveBackground(a, "").revision)
            assertEquals("", it.background(a).text)
        }
    }
    @Test fun `limits and old identity payload remain compatible`() {
        assertEquals("", ContactBackground().text)
        assertThrows(IllegalArgumentException::class.java) { ContactBackground("字".repeat(2001)) }
        assertEquals(ReplyRelationship.FRIEND, ReplyIdentitySetting.decode("{\"format\":1,\"role\":\"friend\",\"text\":\"\"}").relationship)
    }
    @Test fun `background updates invalidate old reply and analysis keys without mixing direction`() {
        val background = ContactBackground("长期经历", "v1")
        val context = ReplyContext("wxid_a", emptyList(), background = background)
        val composition = ReplyComposition(background = background)
        assertTrue(composition.accept(context, ReplySuggestion("好", ""), "本次简短", null, ReplyRelationship.UNSPECIFIED))
        assertTrue(composition.canUse)
        composition.background = ContactBackground("新经历", "v2")
        assertFalse(composition.canUse)
        assertFalse(composition.accept(context, ReplySuggestion("旧", ""), "", null, ReplyRelationship.UNSPECIFIED))
        val evidence = ReplyProtocol.evidence(context, "", "本次要求")
        assertEquals("长期经历", evidence.getJSONObject("contact_background").getString("text"))
        assertEquals("本次要求", evidence.getString("direction"))
        val input = AnalysisInput("你好", "wxid_a", background = background)
        assertNotEquals(input.key, input.copy(background = composition.background).key)
    }
    @Test fun `reset installation cannot restore or publish an old background cache load`() {
        val cache = ContactBackgroundCache()
        val owner = key("one", "wxid_a")
        cache.selectGeneration("old")
        assertTrue(cache.put("old", owner, ContactBackground("旧资料", "v1")))
        cache.selectGeneration("new")
        assertNull(cache.get("new", owner))
        assertFalse(cache.put("old", owner, ContactBackground("旧回调", "v2")))
        assertTrue(cache.put("new", owner, ContactBackground()))
        assertEquals("", cache.get("new", owner)!!.text)
    }
}
