package com.dogheadstrategist.chat.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Push complete settings to the running host without keeping the settings app alive. */
object SettingsSync {
    private const val ACTION = "com.dogheadstrategist.chat.SETTINGS_CHANGED"
    private const val PERMISSION = "com.dogheadstrategist.chat.permission.SYNC_SETTINGS"
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

    /**
     * 主配置 + 可选备用配置 + 两级超时。
     *
     * 备用三项缺任何一项就当没配——切过去也是白切，不如直接走失败路径。
     */
    private fun decodeReplySettings(bundle: Bundle): com.dogheadstrategist.chat.reply.ReplySettings {
        val primary = runCatching {
            com.dogheadstrategist.chat.reply.ReplySettings.fromInput(
                bundle.getString(com.dogheadstrategist.chat.reply.ReplySettings.KEY_ENDPOINT).orEmpty(),
                bundle.getString(com.dogheadstrategist.chat.reply.ReplySettings.KEY_API_KEY).orEmpty(),
                bundle.getString(com.dogheadstrategist.chat.reply.ReplySettings.KEY_MODEL).orEmpty())
        }.getOrElse { com.dogheadstrategist.chat.reply.ReplySettings.empty() }

        val backup = runCatching {
            com.dogheadstrategist.chat.reply.ReplySettings.fromInput(
                bundle.getString(com.dogheadstrategist.chat.reply.ReplySettings.KEY_BACKUP_ENDPOINT).orEmpty(),
                bundle.getString(com.dogheadstrategist.chat.reply.ReplySettings.KEY_BACKUP_API_KEY).orEmpty(),
                bundle.getString(com.dogheadstrategist.chat.reply.ReplySettings.KEY_BACKUP_MODEL).orEmpty())
        }.getOrNull()

        return primary
            .withBackup(backup?.endpoint.orEmpty(), backup?.apiKey.orEmpty(), backup?.model.orEmpty())
            .withTimeouts(
                bundle.getLong(com.dogheadstrategist.chat.reply.ReplySettings.KEY_FAILOVER_MS,
                    com.dogheadstrategist.chat.reply.ReplySettings.DEFAULT_FAILOVER_MS),
                bundle.getLong(com.dogheadstrategist.chat.reply.ReplySettings.KEY_FINAL_MS,
                    com.dogheadstrategist.chat.reply.ReplySettings.DEFAULT_FINAL_MS))
            .withMaxTokens(
                bundle.getInt(com.dogheadstrategist.chat.reply.ReplySettings.KEY_MAX_TOKENS,
                    com.dogheadstrategist.chat.reply.ReplySettings.DEFAULT_MAX_TOKENS))
    }

    fun decode(bundle: Bundle): RuntimeSettings? = runCatching {
        require(bundle.containsKey(ModulePrefs.KEY_EXPLORE))
        val generation = bundle.getString(SettingsProvider.KEY_GENERATION).orEmpty()
        require(generation.isNotBlank())
        RuntimeSettings(bundle.getLong(SettingsProvider.KEY_REVISION, 0L),
            bundle.getBoolean(ModulePrefs.KEY_EXPLORE), generation,
            decodeReplySettings(bundle),
            bundle.getLong(ReplyIdentityProvider.KEY_ROLE_REVISION, 0),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_ENABLED, false),
            bundle.getInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_MIN_PREFIX,
                com.dogheadstrategist.chat.feature.RelayPrefs.DEFAULT_MIN_PREFIX),
            bundle.getInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_CONTEXT_LIMIT,
                com.dogheadstrategist.chat.feature.RelayPrefs.DEFAULT_CONTEXT_LIMIT),
            bundle.getInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_CANDIDATES,
                com.dogheadstrategist.chat.feature.RelayPrefs.DEFAULT_CANDIDATES),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_REPLY, false),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TOPICS, false),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_RELAY, false),
            bundle.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TEMPLATE)
                ?: com.dogheadstrategist.chat.feature.ThinkingPrefs.DEFAULT_TEMPLATE,
            bundle.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_CUSTOM).orEmpty(),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_ENABLED, false),
            bundle.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_COUNT, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_COUNT),
            bundle.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_CONTEXT, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_CONTEXT),
            bundle.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_COOLDOWN, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_COOLDOWN),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_ONLY_UNREPLIED, true),
            bundle.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_SYNC_USERS,
                com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_SYNC_USERS),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_WHITELIST_ENABLED, false),
            bundle.getString(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_WHITELIST).orEmpty(),
            bundle.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TEMPLATE_BACKUP).orEmpty(),
            bundle.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_CUSTOM_BACKUP).orEmpty(),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_TEST_MODE, false),
            bundle.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_CACHE_TTL_MINUTES, 10),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_ENABLED, false),
            bundle.getString(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_PROFILE).orEmpty(),
            bundle.getString(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_VOCAB).orEmpty(),
            bundle.getInt(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_INTERVAL_HOURS,
                com.dogheadstrategist.chat.feature.WarReviewPrefs.DEFAULT_INTERVAL_HOURS),
            bundle.getInt(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_MIN_SAMPLES,
                com.dogheadstrategist.chat.feature.WarReviewPrefs.DEFAULT_MIN_SAMPLES),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.AidePrefs.KEY_ENABLED, false),
            bundle.getString(com.dogheadstrategist.chat.feature.AidePrefs.KEY_TYPES).orEmpty(),
            bundle.getString(com.dogheadstrategist.chat.feature.AidePrefs.KEY_KEY_USERS).orEmpty(),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.IntelligencePrefs.KEY_ENABLED, false),
            bundle.getInt(com.dogheadstrategist.chat.feature.IntelligencePrefs.KEY_MIN_SAMPLES,
                com.dogheadstrategist.chat.feature.IntelligencePrefs.DEFAULT_MIN_SAMPLES),
            bundle.getInt(com.dogheadstrategist.chat.feature.IntelligencePrefs.KEY_INTERVAL_HOURS,
                com.dogheadstrategist.chat.feature.IntelligencePrefs.DEFAULT_INTERVAL_HOURS),
            // 顺序必须与 RuntimeSettings 末尾四个字段一一对应（位置参数构造）
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_AIDE, false),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_INTEL, false),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_COMMUNICATE, false),
            bundle.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_WAR_REVIEW, false))
    }.getOrNull()
}
