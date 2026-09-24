package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import android.os.SystemClock

object ModulePrefs {
    const val FILE_NAME = "wechatmood_config"
    const val KEY_ENABLED = "enabled"
    const val KEY_EXPLORE = "explore_mode"
    const val KEY_SHOW_BADGE = "show_badge"
    const val KEY_API_KEY = "api_key"
    const val KEY_API_BASE = "api_base"
    const val KEY_API_PROVIDER = "api_provider"
    const val KEY_API_MODEL = "api_model"
    private var context: Context? = null
    private val session = SettingsSession()
    private var lastRead = -1000L
    @Volatile var lastBridgeError: String? = null
        private set
    private var lastFailureSignature = ""
    private var lastFailureAt = -60_000L
    private var lastReportAt = -15_000L

    private fun failure(operation: String, error: Throwable) {
        val detail = "$operation：${error.javaClass.simpleName} ${error.message.orEmpty()}"
        lastBridgeError = MoodLog.sanitize(detail).take(600)
        val now = SystemClock.elapsedRealtime()
        if (detail != lastFailureSignature || now - lastFailureAt >= 60_000L) {
            MoodLog.e(operation, error)
            lastFailureSignature = detail
            lastFailureAt = now
        }
    }

    private fun connected() {
        if (lastBridgeError != null) MoodLog.i("BRIDGE_RECOVERED 设置服务连接恢复")
        lastBridgeError = null
        lastFailureSignature = ""
    }
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        this.context = app
        // Register before reading so a save racing with initial loading cannot be missed.
        runCatching { SettingsSync.register(app, ::receiveSettings) }
            .onFailure { MoodLog.e("SYNC_REGISTER_FAILED", it) }
        reload(force = true)
    }
    // Serialize broadcasts with complete Provider reads, not only snapshot assignment:
    // an old in-flight read must finish before a reset broadcast invalidates credentials.
    @Synchronized private fun receiveSettings(bundle: Bundle) {
        val snapshot = SettingsSync.decode(bundle)
        if (snapshot == null) { MoodLog.w("SYNC_INVALID 广播配置不完整"); return }
        MoodLog.protect(snapshot.api.apiKey)
        MoodLog.i("SYNC_RECEIVED revision=${snapshot.revision} enabled=${snapshot.enabled} badge=${snapshot.showBadge}；广播送达不代表设置服务可访问")
        if (!session.accept(snapshot, fromProvider = false)) reload(force = true)
    }
    @Synchronized fun reload(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRead < 1000) return
        lastRead = now
        val snapshot = runCatching {
            val result = requireNotNull(context).contentResolver.call(SettingsProvider.URI, "config", null, null)
                ?: error("设置服务无响应或不可见；检查隐藏应用列表规则及微信分身所在空间")
            requireNotNull(SettingsSync.decode(result)) { "设置服务返回的配置不完整" }
                .also { MoodLog.protect(it.api.apiKey); connected() }
        }.onFailure { failure("BRIDGE_READ_FAILED", it) }.getOrNull()
        session.accept(snapshot)
    }
    // No verified snapshot means disabled; a lost connection preserves the last explicit choice.
    val bridgeAvailable get() = session.current != null
    val enabled get() = session.current?.enabled == true
    val exploreMode get() = session.current?.exploreMode == true
    val showBadge get() = session.current?.showBadge == true
    val apiKey get() = session.current?.api?.apiKey.orEmpty()
    fun apiSettings(): ApiSettings = session.current?.api ?: ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "")
    val canAnalyze get() = session.current?.canAnalyze == true
    @Synchronized fun setSwitch(key: String, value: Boolean): Boolean = runCatching {
        require(key == KEY_ENABLED || key == KEY_SHOW_BADGE)
        val result = context?.contentResolver?.call(SettingsProvider.URI, "set_switch", key,
            Bundle().apply { putBoolean("value", value) }) ?: error("设置服务无响应或不可见；开关没有确认保存")
        val snapshot = requireNotNull(SettingsSync.decode(result)) { "保存回执不完整" }
        MoodLog.protect(snapshot.api.apiKey)
        check(result.containsKey(key) && result.getBoolean(key) == value) { "保存回读与请求不一致" }
        session.accept(snapshot)
        connected()
        MoodLog.i("SWITCH_SAVED key=$key value=$value revision=${snapshot.revision}")
        lastRead = SystemClock.elapsedRealtime()
        result.getBoolean(key) == value
    }.onFailure { failure("BRIDGE_SAVE_FAILED", it) }.getOrDefault(false)
    @Synchronized fun report(status: String) {
        val now = SystemClock.elapsedRealtime()
        val includeLog = now - lastReportAt >= 15_000L
        runCatching {
            val extras = if (includeLog) Bundle().apply { putString("host_log", MoodLog.read().takeLast(48 * 1024)) } else null
            requireNotNull(context?.contentResolver?.call(SettingsProvider.URI, "report", MoodLog.sanitize(status).take(200), extras)) {
                "运行状态上报服务不可见"
            }
            if (includeLog) lastReportAt = now
        }.onFailure {
            // Read failures already carry the full bridge error; do not flood the log per message.
            if (lastBridgeError == null) failure("BRIDGE_REPORT_FAILED", it)
        }
    }
}
