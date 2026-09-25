package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.Executors

object ModulePrefs {
    const val FILE_NAME = "wechatmood_config"
    const val KEY_EXPLORE = "explore_mode"
    const val KEY_API_KEY = "api_key"
    const val KEY_API_BASE = "api_base"
    const val KEY_API_PROVIDER = "api_provider"
    const val KEY_API_MODEL = "api_model"
    @Volatile private var context: Context? = null
    @Volatile private var conversations: ConversationSwitches? = null
    private val manualAnalysis = ManualAnalysis()
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
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        this.context = app
        if (app.packageName == "com.tencent.mm" && conversations == null) {
            val local = app.getSharedPreferences("yanwai_conversations", Context.MODE_PRIVATE)
            conversations = ConversationSwitches(local.getStringSet("enabled_chats", emptySet()).orEmpty()) {
                local.edit().putStringSet("enabled_chats", it).commit()
            }
        }
        // Register before reading so a save racing with initial loading cannot be missed.
        runCatching { SettingsSync.register(app, ::receiveSettings) }
            .onFailure { MoodLog.e("SYNC_REGISTER_FAILED", it) }
        if (app.packageName == "com.tencent.mm") requestReload(force = true) else reload(force = true)
    }
    private fun receiveSettings(bundle: Bundle) {
        val snapshot = SettingsSync.decode(bundle)
        if (snapshot == null) { MoodLog.w("SYNC_INVALID 广播配置不完整"); return }
        // Invalidate a changed installation immediately, without waiting for Provider I/O.
        bridge.receive(snapshot)
        MoodLog.protect(snapshot.api.apiKey)
        MoodLog.i("SYNC_RECEIVED revision=${snapshot.revision}；广播送达不代表设置服务可访问")
    }
    fun requestReload(force: Boolean = false) = bridge.requestReload(force)
    fun reload(force: Boolean = false) = bridge.reload(force)

    private fun readSettings(): RuntimeSettings? = runCatching {
        val result = requireNotNull(context).contentResolver.call(SettingsProvider.URI, "config", null, null)
            ?: error("设置服务无响应或不可见；检查隐藏应用列表规则及微信分身所在空间")
        requireNotNull(SettingsSync.decode(result)) { "设置服务返回的配置不完整" }
            .also { MoodLog.protect(it.api.apiKey); connected() }
    }.onFailure { failure("BRIDGE_READ_FAILED", it) }.getOrNull()
    // No verified snapshot means disabled; a lost connection preserves the last explicit choice.
    val bridgeAvailable get() = session.current != null
    val exploreMode get() = session.current?.exploreMode == true
    val apiKey get() = session.current?.api?.apiKey.orEmpty()
    fun apiSettings(): ApiSettings = session.current?.api ?: ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "")
    fun isChatEnabled(talker: String?) = conversations?.isEnabled(talker) == true
    fun canAnalyze(talker: String?) = isChatEnabled(talker) && session.current?.canAnalyze == true
    fun analysisInput(input: AnalysisInput): AnalysisInput = manualAnalysis.selectedInput(input) ?: input
    fun shouldDisplay(input: AnalysisInput): Boolean = manualAnalysis.allows(input, isChatEnabled(input.talker))
    fun canAnalyze(input: AnalysisInput): Boolean = shouldDisplay(input) && session.current?.canAnalyze == true
    fun selectMessage(input: AnalysisInput): Boolean = manualAnalysis.select(input)
    fun setChatEnabled(talker: String?, value: Boolean): Boolean = runCatching {
        val saved = conversations?.setEnabled(talker, value) == true
        // Only an explicit, successfully saved switch-off resets this chat's manual choices.
        if (saved && !value && talker != null) manualAnalysis.clearConversation(talker)
        saved
    }.onFailure { MoodLog.e("CHAT_SWITCH_SAVE_FAILED 本地会话开关保存失败", it) }.getOrDefault(false)
    fun report(status: String) = bridge.report(status)

    private fun reportNow(status: String) {
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
