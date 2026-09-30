package dev.jev.wechatmood.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyDrawerSizingTest {
    @Test fun `initial and result drawers add twenty percent of page height`() {
        assertEquals(560, ReplyDrawerSizing.height(1000, 360))
        assertEquals(760, ReplyDrawerSizing.height(1000, 560))
    }

    @Test fun `short window retains room above drawer`() {
        assertEquals(475, ReplyDrawerSizing.height(500, 560))
        assertEquals(285, ReplyDrawerSizing.height(300, 360))
    }

    @Test fun `large screen uses page increment rather than scaling original drawer`() {
        assertEquals(1120, ReplyDrawerSizing.height(2000, 720))
    }
}
