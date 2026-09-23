package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import dev.jev.wechatmood.BuildConfig

object ModulePrefs {
    const val FILE_NAME = "wechatmood_config"
    const val KEY_ENABLED = "enabled"
    const val KEY_EXPLORE = "explore_mode"
    const val KEY_SHOW_BADGE = "show_badge"
    private var context: Context? = null
    private var config: Bundle? = null
    private var lastRead = -1000L
    fun init(context: Context) { this.context = context.applicationContext ?: context; reload() }
    @Synchronized fun reload() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRead < 1000) return
        lastRead = now
        config = runCatching {
            context?.contentResolver?.call(SettingsProvider.URI, "config", null, null)
        }.getOrNull()
    }
    // If the bridge is unavailable, fail closed so a disabled switch cannot silently become enabled.
    val enabled get() = config?.getBoolean(KEY_ENABLED, true) == true
    val exploreMode get() = config?.getBoolean(KEY_EXPLORE, false) == true
    val showBadge get() = config?.getBoolean(KEY_SHOW_BADGE, true) == true
    val apiKey get() = BuildConfig.JEV_API_KEY
    val apiBase get() = BuildConfig.JEV_ENDPOINT
    val apiModel get() = BuildConfig.JEV_MODEL
    val canAnalyze get() = enabled && apiKey.isNotBlank()
    fun report(status: String) {
        runCatching { context?.contentResolver?.call(SettingsProvider.URI, "report", status.take(200), null) }
    }
}
