package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class AnalysisPopupStateTest {
    @Test fun `close suppresses repeated scan and cached completion`() {
        val state = AnalysisPopupState()
        assertTrue(state.offer("a"))
        assertTrue(state.offer("a"))
        state.dismiss()
        assertFalse(state.offer("a"))
    }

    @Test fun `other rows do not steal the active result or alternate every tick`() {
        val state = AnalysisPopupState()
        assertTrue(state.offer("a"))
        assertFalse(state.offer("b"))
        assertTrue(state.offer("a"))
        state.dismiss()
        assertFalse(state.offer("b"))
        assertNull(state.activeKey)
    }

    @Test fun `explicit request reopens closed message and can replace active message`() {
        val state = AnalysisPopupState()
        state.offer("a")
        state.dismiss()
        state.request("a")
        assertTrue(state.offer("a"))
        state.request("b")
        assertFalse(state.offer("a"))
        assertTrue(state.offer("b"))
    }

    @Test fun `dismissed key retention is bounded`() {
        val state = AnalysisPopupState(retention = 2)
        listOf("a", "b", "c").forEach { state.offer(it); state.dismiss() }
        assertFalse(state.offer("b"))
        assertTrue(state.offer("a"))
    }

    @Test fun `percentage foreground has high contrast against popup background`() {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val v = ((color ushr shift) and 255) / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }
        val background = luminance(AnalysisPopupPalette.BACKGROUND)
        for (color in listOf(AnalysisPopupPalette.FOREGROUND, AnalysisPopupPalette.LABEL)) {
            assertTrue((background + 0.05) / (luminance(color) + 0.05) >= 4.5)
        }
    }
}
