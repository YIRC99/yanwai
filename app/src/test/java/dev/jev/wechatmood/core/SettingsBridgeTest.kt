package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettingsBridgeTest {
    private fun snapshot(revision: Long = 1, generation: String = "a", key: String = "key") =
        RuntimeSettings(revision, false, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, key), generation)

    @Test fun `scan requests are deferred coalesced and rate limited`() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = SettingsSession()
        var reads = 0
        var time = 0L
        val bridge = SettingsBridge(session, tasks::addLast, { time }, { reads++; snapshot() }, {})
        repeat(20) { bridge.requestReload() }
        assertEquals(0, reads)
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(1, reads)
        assertNotNull(session.current)
        bridge.requestReload()
        tasks.removeFirst().invoke()
        assertEquals(1, reads)
        time = 1000
        bridge.requestReload()
        tasks.removeFirst().invoke()
        assertEquals(2, reads)
    }

    @Test fun `new installation broadcast invalidates credentials before blocked provider returns`() {
        val tasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        val session = SettingsSession().apply { accept(snapshot(100)) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val bridge = SettingsBridge(session, { tasks.add(it) }, { 0L }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            snapshot(100)
        }, {})
        try {
            val read = pool.submit { bridge.reload(force = true) }
            assertTrue("provider read must start", entered.await(2, TimeUnit.SECONDS))
            pool.submit { bridge.receive(snapshot(1, "b", "")) }.get(2, TimeUnit.SECONDS)
            assertNull("broadcast must pause old credentials immediately", session.current)
            release.countDown()
            read.get(2, TimeUnit.SECONDS)
            assertNull("in-flight old read must not restore credentials", session.current)
            assertEquals(1, tasks.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `read overlapping a reset is discarded then new installation is verified`() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = SettingsSession().apply { accept(snapshot(100)) }
        var reads = 0
        lateinit var bridge: SettingsBridge
        bridge = SettingsBridge(session, tasks::addLast, { 0L }, {
            reads++
            if (reads == 1) {
                bridge.receive(snapshot(1, "b", ""))
                snapshot(100)
            } else snapshot(1, "b", "")
        }, {})
        bridge.requestReload()
        tasks.removeFirst().invoke()
        assertEquals(2, reads)
        assertEquals("b", session.current!!.generation)
        assertFalse(session.current!!.canAnalyze)
        assertTrue(tasks.isEmpty())
    }

    @Test fun `delayed cleared key broadcast is not overwritten by an older provider read`() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = SettingsSession().apply { accept(snapshot()) }
        var reads = 0
        lateinit var bridge: SettingsBridge
        bridge = SettingsBridge(session, tasks::addLast, { 0L }, {
            reads++
            if (reads == 1) { bridge.receive(snapshot(2, key = "")); snapshot() }
            else snapshot(2, key = "")
        }, {})
        bridge.reload(force = true)
        assertFalse(session.current!!.canAnalyze)
        tasks.removeFirst().invoke()
        assertFalse(session.current!!.canAnalyze)
    }

    @Test fun `transport failure preserves verified configuration`() {
        val session = SettingsSession().apply { accept(snapshot()) }
        val bridge = SettingsBridge(session, {}, { 0L }, { null }, {})
        bridge.reload(force = true)
        assertTrue(session.current!!.canAnalyze)
    }

    @Test fun `reports are deferred and only latest queued status is sent`() {
        val tasks = ArrayDeque<() -> Unit>()
        val sent = mutableListOf<String>()
        lateinit var bridge: SettingsBridge
        bridge = SettingsBridge(SettingsSession(), tasks::addLast, { 0L }, { null }, {
            sent.add(it)
            if (it == "latest") { bridge.report("during-1"); bridge.report("during-2") }
        })
        bridge.report("old")
        bridge.report("latest")
        assertTrue(sent.isEmpty())
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(listOf("latest", "during-2"), sent)
        bridge.report("after")
        tasks.removeFirst().invoke()
        assertEquals(listOf("latest", "during-2", "after"), sent)
    }
}
