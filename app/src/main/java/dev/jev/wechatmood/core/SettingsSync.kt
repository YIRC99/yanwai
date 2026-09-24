package dev.jev.wechatmood.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Push complete settings to the running host without keeping the settings app alive. */
object SettingsSync {
    private const val ACTION = "dev.jev.wechatmood.SETTINGS_CHANGED"
    private const val PERMISSION = "dev.jev.wechatmood.permission.SYNC_SETTINGS"
    private const val EXTRA = "config"
    private var registered = false

    @Synchronized fun register(context: Context, receive: (Bundle) -> Unit) {
        if (registered || context.packageName != "com.tencent.mm") return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION) intent.getBundleExtra(EXTRA)?.let(receive)
            }
        }
        // The permission here restricts the SENDER to the signed module app, not WeChat.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), PERMISSION, null,
            ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun publish(context: Context, config: Bundle) {
        context.sendBroadcast(Intent(ACTION).setPackage("com.tencent.mm")
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY).putExtra(EXTRA, config))
    }

    fun decode(bundle: Bundle): RuntimeSettings? = runCatching {
        require(listOf(ModulePrefs.KEY_ENABLED, ModulePrefs.KEY_SHOW_BADGE, ModulePrefs.KEY_EXPLORE,
            ModulePrefs.KEY_API_BASE, ModulePrefs.KEY_API_KEY).all(bundle::containsKey))
        val generation = bundle.getString(SettingsProvider.KEY_GENERATION).orEmpty()
        require(generation.isNotBlank())
        RuntimeSettings(bundle.getLong(SettingsProvider.KEY_REVISION, 0L),
            bundle.getBoolean(ModulePrefs.KEY_ENABLED), bundle.getBoolean(ModulePrefs.KEY_SHOW_BADGE),
            bundle.getBoolean(ModulePrefs.KEY_EXPLORE), ApiSettings.fromInput(
                bundle.getString(ModulePrefs.KEY_API_BASE).orEmpty(), bundle.getString(ModulePrefs.KEY_API_KEY).orEmpty(),
                bundle.getString(ModulePrefs.KEY_API_PROVIDER), bundle.getString(ModulePrefs.KEY_API_MODEL).orEmpty()), generation)
    }.getOrNull()
}
