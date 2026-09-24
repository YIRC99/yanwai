package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class SettingsSessionTest {
    private fun settings(revision: Long, key: String = "test-key", generation: String = "install-a") =
        RuntimeSettings(revision, false, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, key), generation)

    @Test fun `first failed read never enables analysis`() {
        val session = SettingsSession()
        session.accept(null)
        assertNull(session.current)
    }

    @Test fun `removing settings process retains loaded configuration`() {
        val session = SettingsSession()
        val loaded = settings(1)
        session.accept(loaded)
        repeat(5) { session.accept(null) }
        assertSame(loaded, session.current)
        assertTrue(session.current!!.canAnalyze)
    }

    @Test fun `cleared key stays unconfigured through bridge failure`() {
        val session = SettingsSession()
        session.accept(settings(1))
        session.accept(settings(2, key = ""))
        session.accept(null)
        assertFalse(session.current!!.canAnalyze)
    }

    @Test fun `clearing key replaces previous key even when bridge later fails`() {
        val session = SettingsSession()
        session.accept(settings(1))
        session.accept(settings(2, key = ""))
        session.accept(null)
        assertEquals("", session.current!!.api.apiKey)
        assertFalse(session.current!!.canAnalyze)
    }

    @Test fun `delayed old broadcast cannot restore a cleared key`() {
        val session = SettingsSession()
        session.accept(settings(3, key = ""))
        session.accept(settings(2))
        assertFalse(session.current!!.canAnalyze)
        assertEquals(3L, session.current!!.revision)
    }

    @Test fun `reconnected provider replaces cached key and new process starts empty`() {
        val session = SettingsSession()
        session.accept(settings(1))
        session.accept(null)
        session.accept(settings(2, key = "replacement-key"))
        assertEquals("replacement-key", session.current!!.api.apiKey)
        assertNull(SettingsSession().current)
    }

    @Test fun `data reset replaces old credentials and accepts a cleared key`() {
        val session = SettingsSession()
        session.accept(settings(100))
        session.accept(settings(0, key = "", generation = "install-b"))
        assertFalse(session.current!!.canAnalyze)
        session.accept(settings(1, key = "", generation = "install-b"), fromProvider = false)
        assertFalse(session.current!!.canAnalyze)
        session.accept(settings(101), fromProvider = false)
        assertEquals("install-b", session.current!!.generation)
        assertFalse(session.current!!.canAnalyze)
    }

    @Test fun `unknown generation broadcast pauses analysis pending provider verification`() {
        val session = SettingsSession()
        session.accept(settings(100))
        assertFalse(session.accept(settings(1, generation = "install-b"), fromProvider = false))
        session.accept(null)
        assertNull(session.current)
        assertFalse(session.accept(settings(99), fromProvider = false))
        assertNull(session.current)
        session.accept(settings(1, key = "", generation = "install-b"))
        assertFalse(session.current!!.canAnalyze)
    }
}
