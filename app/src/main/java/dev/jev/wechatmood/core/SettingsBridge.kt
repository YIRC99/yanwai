package dev.jev.wechatmood.core

/** Coordinates bridge work without doing transport work under the snapshot lock. */
class SettingsBridge(
    private val session: SettingsSession,
    private val execute: (() -> Unit) -> Unit,
    private val now: () -> Long,
    private val read: () -> RuntimeSettings?,
    private val sendReport: (String) -> Unit,
) {
    private val stateLock = Any()
    private val readLock = Any()
    private var lastRead: Long? = null
    private var broadcastEpoch = 0L
    private var reloadQueued = false
    private var reloadForced = false
    private var reportQueued = false
    private var pendingReport: String? = null

    /** Called by UI scans: coalesce work without waiting for an in-flight Provider call. */
    fun requestReload(force: Boolean = false) {
        val schedule = synchronized(stateLock) {
            reloadForced = reloadForced || force
            if (reloadQueued) false else { reloadQueued = true; true }
        }
        if (schedule) execute(::drainReloads)
    }

    private fun drainReloads() {
        try {
            while (true) {
                val force = synchronized(stateLock) { reloadForced.also { reloadForced = false } }
                reload(force)
                val again = synchronized(stateLock) {
                    if (reloadForced) true else { reloadQueued = false; false }
                }
                if (!again) return
            }
        } catch (error: Throwable) {
            synchronized(stateLock) { reloadQueued = false }
            throw error
        }
    }

    /** Synchronous entry for local app initialization/saves, never host scans or analysis. */
    fun reload(force: Boolean = false) {
        synchronized(readLock) {
            val epoch = synchronized(stateLock) {
                val time = now()
                if (!force && lastRead?.let { time - it in 0L until 1000L } == true) return
                lastRead = time
                broadcastEpoch
            }
            val snapshot = read()
            val stale = synchronized(stateLock) {
                if (epoch != broadcastEpoch) true else { session.accept(snapshot); false }
            }
            // Even a same-generation broadcast makes an overlapping read untrustworthy:
            // a reset can otherwise return an old installation after its invalidation.
            if (stale) requestReload(force = true)
        }
    }

    fun receive(snapshot: RuntimeSettings) {
        val verify = synchronized(stateLock) {
            broadcastEpoch++
            !session.accept(snapshot, fromProvider = false)
        }
        if (verify) requestReload(force = true)
    }

    /** Keep only the latest waiting status; transport and log collection run on the worker. */
    fun report(status: String) {
        val schedule = synchronized(stateLock) {
            pendingReport = status
            if (reportQueued) false else { reportQueued = true; true }
        }
        if (schedule) execute(::drainReports)
    }

    private fun drainReports() {
        try {
            while (true) {
                val status = synchronized(stateLock) {
                    pendingReport?.also { pendingReport = null }
                        ?: run { reportQueued = false; return }
                }
                sendReport(status)
            }
        } catch (error: Throwable) {
            synchronized(stateLock) { reportQueued = false }
            throw error
        }
    }
}
