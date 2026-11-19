package com.dogheadstrategist.chat.core

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.Executors

object ModulePrefs {
    const val FILE_NAME = "goutoujunshi_config"
    const val KEY_EXPLORE = "explore_mode"
    @Volatile private var context: Context? = null

    /** 宿主启动后再取；取不到说明 init 还没跑（此时不该记录统计）。 */
    fun appContext(): Context? = context
    private val session = SettingsSession()
    private val bridgeWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "yanwai-settings") }
    private val bridge = SettingsBridge(session, { bridgeWorker.execute(it) },
        SystemClock::elapsedRealtime, ::readSettings, ::reportNow)
    @Volatile var lastBridgeError: String? = null
        private set
    private var lastFailureSignature = ""
    private var lastFailureAt = -60_000L
    private var lastReportAt = -15_000L

    @Synchronized private fun failure(operation: String, error: Throwable) {
        val detail = "$operation：${error.javaClass.simpleName} ${error.message.orEmpty()}"
        lastBridgeError = MoodLog.sanitize(detail).take(600)
        val now = SystemClock.elapsedRealtime()
        if (detail != lastFailureSignature || now - lastFailureAt >= 60_000L) {
            MoodLog.e(operation, error)
            lastFailureSignature = detail
            lastFailureAt = now
        }
    }

    @Synchronized private fun connected() {
        if (lastBridgeError != null) MoodLog.i("BRIDGE_RECOVERED 设置服务连接恢复")
        lastBridgeError = null
        lastFailureSignature = ""
    }

    /**
     * 设置宿主上下文并开始读取配置。
     *
     * 以前只有微信进程的 HookEntry 调它，app 主进程从来没调过——于是 context 一直
     * 为 null，readSettings() 里 requireNotNull(context) 直接抛异常，快照永远读不进来。
     * 表现就是「明明保存了地址和密钥，点检查却提示请先保存」。
     *
     * 一律走 requestReload（后台线程）：app 是在 onCreate 主线程调用的，
     * 同步 reload 会阻塞首屏。
     */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        if (this.context === app) return          // 重复调用直接返回，避免重复注册广播
        this.context = app
        // Register before reading so a save racing with initial loading cannot be missed.
        runCatching { SettingsSync.register(app, ::receiveSettings) }
            .onFailure { MoodLog.e("SYNC_REGISTER_FAILED", it) }
        requestReload(force = true)
    }

    private fun receiveSettings(bundle: Bundle) {
        val snapshot = SettingsSync.decode(bundle)
        if (snapshot == null) { MoodLog.w("SYNC_INVALID 广播配置不完整"); return }
        // Invalidate a changed installation immediately, without waiting for Provider I/O.
        bridge.receive(snapshot)
        MoodLog.protect(snapshot.reply.apiKey)
        MoodLog.i("SYNC_RECEIVED revision=${snapshot.revision}；广播送达不代表设置服务可访问")
    }

    fun requestReload(force: Boolean = false) = bridge.requestReload(force)
    fun reload(force: Boolean = false) = bridge.reload(force)

    private fun readSettings(): RuntimeSettings? = runCatching {
        val result = SettingsTransport.call(requireNotNull(context), "config", null, null)
            ?: error("设置服务无响应或不可见；检查隐藏应用列表规则及微信分身所在空间")
        requireNotNull(SettingsSync.decode(result)) { "设置服务返回的配置不完整" }
            .also { MoodLog.protect(it.reply.apiKey); connected() }
    }.onFailure { failure("BRIDGE_READ_FAILED", it) }.getOrNull()

    // No verified snapshot means disabled; a lost connection preserves the last explicit choice.
    val bridgeAvailable get() = session.current != null
    fun settings(): RuntimeSettings? = session.current
    fun replySettings() = session.current?.reply ?: com.dogheadstrategist.chat.reply.ReplySettings.empty()
    val canGenerateReply get() = session.current?.canGenerateReply == true
    val exploreMode get() = session.current?.exploreMode == true
    val rolesRevision get() = session.current?.rolesRevision ?: 0L
    fun report(status: String) = bridge.report(status)

    private fun reportNow(status: String) {
        val now = SystemClock.elapsedRealtime()
        val includeLog = now - lastReportAt >= 15_000L
        runCatching {
            val extras = if (includeLog) Bundle().apply { putString("host_log", MoodLog.read().takeLast(48 * 1024)) } else null
            requireNotNull(SettingsTransport.call(requireNotNull(context), "report", MoodLog.sanitize(status).take(200), extras)) {
                "运行状态上报服务不可见"
            }
            if (includeLog) lastReportAt = now
        }.onFailure {
            // Read failures already carry the full bridge error; do not flood the log per message.
            if (lastBridgeError == null) failure("BRIDGE_REPORT_FAILED", it)
        }
    }
}
