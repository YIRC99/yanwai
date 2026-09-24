package dev.jev.wechatmood.ui

import org.junit.Assert.*
import org.junit.Test

class SetupOverviewTest {
    private fun state(key: Boolean = true, dirty: Boolean = false, probe: ProbeState = ProbeState.UNTESTED,
        seen: Long = 0) =
        SetupPresenter.resolve(key, dirty, probe, seen, 1_000_000)

    @Test fun `first use points to credentials instead of updates`() {
        assertEquals(SetupAction.CONFIGURE, state(key = false).action)
        assertEquals("未配置", state(key = false).modelLabel)
    }
    @Test fun `unsaved edits invalidate a previous successful check`() {
        val value = state(dirty = true, probe = ProbeState.PASSED)
        assertEquals("有未保存修改", value.modelLabel)
        assertEquals(SetupAction.TEST, value.action)
        assertNotEquals(StatusTone.SUCCESS, value.modelTone)
    }
    @Test fun `successful model check does not imply wechat is connected`() {
        val value = state(probe = ProbeState.PASSED)
        assertEquals(SetupAction.GUIDE, value.action)
        assertEquals("尚无运行记录", value.hostLabel)
        assertEquals(StatusTone.WARNING, value.hostTone)
    }
    @Test fun `old host record never claims currently online`() {
        val value = state(probe = ProbeState.PASSED, seen = 1)
        assertEquals("有历史记录", value.hostLabel)
        assertNotEquals(StatusTone.SUCCESS, value.hostTone)
    }
    @Test fun `request failure has a visible recovery action`() {
        val value = state(probe = ProbeState.FAILED)
        assertEquals(StatusTone.ERROR, value.tone)
        assertEquals(SetupAction.TEST, value.action)
    }
    @Test fun `ready state directs users to the per chat analysis switch`() {
        val value = state(probe = ProbeState.PASSED, seen = 999_999)
        assertTrue(value.description.contains("右上角「分析」"))
        assertTrue(value.description.contains("默认关闭"))
    }
}
