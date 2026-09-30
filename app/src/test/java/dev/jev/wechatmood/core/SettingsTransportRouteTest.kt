package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class SettingsTransportRouteTest {
    private class Connection : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun `missing provider uses bound connection and releases it`() {
        val bound = Connection()
        val result = SettingsTransportRoute.call({ null as Connection? }, { bound }) { "settings" }
        assertEquals("settings", result)
        assertTrue(bound.closed)
    }

    @Test fun `available provider never starts service`() {
        val provider = Connection()
        val result = SettingsTransportRoute.call({ provider }, { error("unexpected bind") }) { "roles" }
        assertEquals("roles", result)
        assertTrue(provider.closed)
    }

    @Test fun `failed write is not replayed over another connection`() {
        val provider = Connection()
        var writes = 0
        val failure = IllegalStateException("response lost after write")
        val caught = runCatching {
            SettingsTransportRoute.call({ provider }, { error("must not replay") }) {
                writes++
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, writes)
        assertTrue(provider.closed)
    }

    @Test fun `permission failure never falls back`() {
        val denied = SecurityException("denied")
        val caught = runCatching {
            SettingsTransportRoute.call<Connection, Unit>({ throw denied }, { error("must not bind") }) { }
        }.exceptionOrNull()
        assertSame(denied, caught)
    }

    @Test fun `bound call failure still releases connection`() {
        val bound = Connection()
        runCatching {
            SettingsTransportRoute.call({ null as Connection? }, { bound }) { error("failed") }
        }
        assertTrue(bound.closed)
    }
}
