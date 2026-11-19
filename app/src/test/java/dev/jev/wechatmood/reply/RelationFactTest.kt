package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationFactTest {
    private fun fact(kind: String, text: String, at: Long = 0) = RelationFact(kind, text, at, at)

    @Test fun `same kind with overlapping text is replaced and keeps the first seen time`() {
        val existing = listOf(fact("工作", "在游戏公司做运营", 100))
        val merged = RelationFacts.merge(existing, listOf(fact("工作", "在游戏公司做运营主管", 200)), 300)
        assertEquals(1, merged.size)
        assertEquals("在游戏公司做运营主管", merged[0].text)
        assertEquals(100, merged[0].evidenceTime)
        assertEquals(300, merged[0].updatedAt)
    }

    @Test fun `different kinds are kept separately`() {
        val merged = RelationFacts.merge(listOf(fact("工作", "在游戏公司做运营", 100)),
            listOf(fact("宠物", "养了一只橘猫", 200)), 300)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.kind == "宠物" })
    }

    @Test fun `unrelated text of the same kind is appended`() {
        val merged = RelationFacts.merge(listOf(fact("喜好", "喜欢喝冰美式", 100)),
            listOf(fact("喜好", "周末常去爬山", 200)), 300)
        assertEquals(2, merged.size)
    }

    @Test fun `manual facts keep their source when merged`() {
        val existing = listOf(RelationFact("工作", "在做运营", 100, 100, source = "manual"))
        val merged = RelationFacts.merge(existing, listOf(fact("工作", "在做运营主管", 200)), 300)
        assertEquals("manual", merged[0].source)
    }

    @Test fun `list is capped`() {
        val many = (1..50).map { RelationFact("喜好", "第 $it 件事", it.toLong(), it.toLong()) }
        val merged = RelationFacts.merge(emptyList(), many, 999)
        assertEquals(RelationFacts.LIMIT, merged.size)
    }

    @Test fun `similarity treats rewording as the same fact`() {
        assertTrue(RelationFacts.similarity("在游戏公司做运营", "在游戏公司做运营主管") >= 0.55)
        assertTrue(RelationFacts.similarity("喜欢喝冰美式", "周末常去爬山") < 0.2)
    }

    @Test fun `extractor only accepts known kinds and short text`() {
        val extractor = FactExtractor()
        val content = org.json.JSONArray()
            .put(org.json.JSONObject().put("kind", "工作").put("text", "在游戏公司做运营").put("confidence", 0.8))
            .put(org.json.JSONObject().put("kind", "乱写").put("text", "不该进来"))
            .put(org.json.JSONObject().put("kind", "城市").put("text", "很长的内容".repeat(20)))
            .toString()
        val body = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("message", org.json.JSONObject().put("content", content)))).toString()
        val facts = extractor.parse(body)
        assertEquals(1, facts.size)
        assertEquals("工作", facts[0].kind)
    }

    @Test fun `extractor tolerates prose around the array`() {
        val extractor = FactExtractor()
        val content = "好的，结果如下：[{\"kind\":\"宠物\",\"text\":\"养了一只橘猫\"}] 以上。"
        val body = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("message", org.json.JSONObject().put("content", content)))).toString()
        assertEquals(1, extractor.parse(body).size)
    }

    @Test fun `extractor returns empty on malformed json`() {
        val extractor = FactExtractor()
        assertEquals(0, extractor.parse("not json").size)
        val prose = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("message", org.json.JSONObject().put("content", "没有可以提取的内容")))).toString()
        assertEquals(0, extractor.parse(prose).size)
    }
}
