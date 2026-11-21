package dev.jev.wechatmood.reply

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DraftGuardTest {
    @Test fun `hostile draft is flagged without calling the model`() {
        assertNotNull(DraftGuard.hint("操你妈的"))
        assertNotNull(DraftGuard.hint("你是不是傻逼"))
        assertNotNull(DraftGuard.hint("你麻痹"))
    }

    @Test fun `ordinary drafts pass the local guard`() {
        assertNull(DraftGuard.hint("行吧"))
        assertNull(DraftGuard.hint("那我先不折腾了，你定？"))
        assertNull(DraftGuard.hint("滚去睡觉"))  // 单字「滚」不算，避免误伤
    }

    @Test fun `hint names the matched word for traceability`() {
        val hint = DraftGuard.hint("操你妈的")
        assertNotNull(hint)
        assert(hint!!.contains("攻击性"))
    }

    @Test fun `random letters are caught locally`() {
        assertNotNull(DraftGuard.hint("asdfgh"))
        assertNotNull(DraftGuard.hint("qwrtypsdfg"))
    }

    @Test fun `real english words are not treated as gibberish`() {
        assertNull(DraftGuard.hint("hahaha"))
        assertNull(DraftGuard.hint("okok"))
        assertNull(DraftGuard.hint("明天见"))
    }

    @Test fun `fallback flags unrelated draft`() {
        val hint = DraftFallback.hint("科技公好士", "星辰云电脑你们有在搞么")
        assert(hint.contains("接不上"))
        assert(hint.contains("生成回复"))
    }

    @Test fun `fallback keeps quiet when there is overlap`() {
        val hint = DraftFallback.hint("星辰云电脑没搞过", "星辰云电脑你们有在搞么")
        assert(hint.contains("语气合不合适"))
    }

    @Test fun `fallback handles missing context`() {
        assert(DraftFallback.hint("行吧", null).contains("上下文也不够"))
    }
}
