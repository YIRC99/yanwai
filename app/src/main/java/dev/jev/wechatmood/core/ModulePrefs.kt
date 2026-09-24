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
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        this.context = app
        // Register before reading so a save racing with initial loading cannot be missed.
        runCatching { SettingsSync.register(app, ::receiveSettings) }
            .onFailure { MoodLog.w("设置同步注册失败：${it.javaClass.simpleName}") }
        reload(force = true)
    }
    // Serialize broadcasts with complete Provider reads, not only snapshot assignment:
    // an old in-flight read must finish before a reset broadcast invalidates credentials.
    @Synchronized private fun receiveSettings(bundle: Bundle) {
        if (!session.accept(SettingsSync.decode(bundle), fromProvider = false)) reload(force = true)
    }
    @Synchronized fun reload(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRead < 1000) return
        lastRead = now
        val snapshot = runCatching {
            context?.contentResolver?.call(SettingsProvider.URI, "config", null, null)?.let(SettingsSync::decode)
        }.getOrNull()
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
            Bundle().apply { putBoolean("value", value) }) ?: return false
        session.accept(SettingsSync.decode(result))
        lastRead = SystemClock.elapsedRealtime()
        result.getBoolean(key) == value
    }.getOrDefault(false)
    fun report(status: String) {
        runCatching { context?.contentResolver?.call(SettingsProvider.URI, "report", status.take(200), null) }
    }
}
