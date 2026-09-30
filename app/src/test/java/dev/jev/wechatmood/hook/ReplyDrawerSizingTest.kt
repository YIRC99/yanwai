package dev.jev.wechatmood.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyDrawerSizingTest {
    @Test fun `compact content keeps its natural height without adding empty space`() {
        assertEquals(360, ReplyDrawerSizing.height(1000, 360))
        assertEquals(560, ReplyDrawerSizing.height(1000, 560))
    }

    @Test fun `short window retains room above drawer`() {
        assertEquals(475, ReplyDrawerSizing.height(500, 560))
        assertEquals(285, ReplyDrawerSizing.height(300, 360))
        assertEquals(190, ReplyDrawerSizing.height(200, 560))
    }

    @Test fun `folding content shrinks a tall drawer while overflow remains capped`() {
        assertEquals(1900, ReplyDrawerSizing.height(2000, 2400))
        assertEquals(720, ReplyDrawerSizing.height(2000, 720))
    }
}
