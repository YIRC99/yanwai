package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.SettingsProvider

/**
 * 思考模式设置：每个功能一个开关 + 一份全局「关闭片段」。
 *
 * 开关决定「哪个功能要关思考」，片段决定「怎么关」。之所以拆开：关闭思考的字段各家不同，
 * 而这个差异无法从模型名可靠推断（中转站常改名），交给用户选比猜更安全。
 *
 * 存储与 [RelayPrefs] 相同，随 SettingsProvider / SettingsSync 快照进微信进程。
 */
object ThinkingPrefs {
    const val KEY_REPLY = "thinking_reply"
    const val KEY_TOPICS = "thinking_topics"
    const val KEY_RELAY = "thinking_relay"
    const val KEY_TEMPLATE = "thinking_template"
    const val KEY_CUSTOM = "thinking_custom"
    /** 备用模型往往换了服务商，关闭思考的字段不一样，所以片段单独存一份。 */
    const val KEY_TEMPLATE_BACKUP = "thinking_template_backup"
    const val KEY_CUSTOM_BACKUP = "thinking_custom_backup"
    // 后加的四个功能开关：通知代办、人物档案、通知摘要、我的风格。
    // 之前这三条路径要么没有开关，要么借用「帮我回复」的开关，关不干净。
    const val KEY_AIDE = "thinking_aide"
    const val KEY_INTEL = "thinking_intel"
    const val KEY_COMMUNICATE = "thinking_communicate"
    const val KEY_WAR_REVIEW = "thinking_war_review"
    const val DEFAULT_TEMPLATE = "none"
    private const val MODULE = "com.dogheadstrategist.chat"

    /** 请求侧（微信进程）：读快照，未同步时按关闭处理。 */
    fun replyForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingReply ?: false }.getOrDefault(false)
    fun topicsForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingTopics ?: false }.getOrDefault(false)
    fun relayForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingRelay ?: false }.getOrDefault(false)
    fun aideForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingAide ?: false }.getOrDefault(false)
    fun intelForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingIntel ?: false }.getOrDefault(false)
    fun communicateForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingCommunicate ?: false }.getOrDefault(false)
    fun warReviewForRequest(): Boolean = runCatching { ModulePrefs.settings()?.thinkingWarReview ?: false }.getOrDefault(false)
    fun templateForRequest(): String = runCatching { ModulePrefs.settings()?.thinkingTemplate ?: DEFAULT_TEMPLATE }.getOrDefault(DEFAULT_TEMPLATE)
    fun customForRequest(): String = runCatching { ModulePrefs.settings()?.thinkingCustom ?: "" }.getOrDefault("")

    /** 备用模型用的片段。没单独设置时沿用主片段，省得两处都要配。 */
    fun templateBackupForRequest(): String = runCatching {
        ModulePrefs.settings()?.thinkingTemplateBackup?.takeIf { it.isNotBlank() } ?: templateForRequest()
    }.getOrDefault(DEFAULT_TEMPLATE)

    fun customBackupForRequest(): String = runCatching {
        val own = ModulePrefs.settings()?.thinkingCustomBackup.orEmpty()
        if (own.isNotBlank()) own else customForRequest()
    }.getOrDefault("")

    fun reply(context: Context): Boolean = readBoolean(context, KEY_REPLY, ModulePrefs.settings()?.thinkingReply)
    fun topics(context: Context): Boolean = readBoolean(context, KEY_TOPICS, ModulePrefs.settings()?.thinkingTopics)
    fun relay(context: Context): Boolean = readBoolean(context, KEY_RELAY, ModulePrefs.settings()?.thinkingRelay)
    fun aide(context: Context): Boolean = readBoolean(context, KEY_AIDE, ModulePrefs.settings()?.thinkingAide)
    fun intel(context: Context): Boolean = readBoolean(context, KEY_INTEL, ModulePrefs.settings()?.thinkingIntel)
    fun communicate(context: Context): Boolean = readBoolean(context, KEY_COMMUNICATE, ModulePrefs.settings()?.thinkingCommunicate)
    fun warReview(context: Context): Boolean = readBoolean(context, KEY_WAR_REVIEW, ModulePrefs.settings()?.thinkingWarReview)

    fun template(context: Context): String = runCatching {
        if (context.packageName == MODULE) {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)?.getString(KEY_TEMPLATE, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        } else {
            ModulePrefs.settings()?.thinkingTemplate ?: DEFAULT_TEMPLATE
        }
    }.getOrDefault(DEFAULT_TEMPLATE)

    fun custom(context: Context): String = runCatching {
        if (context.packageName == MODULE) {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)?.getString(KEY_CUSTOM, "").orEmpty()
        } else {
            ModulePrefs.settings()?.thinkingCustom.orEmpty()
        }
    }.getOrDefault("")

    fun backupTemplate(context: Context): String = runCatching {
        context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)?.getString(KEY_TEMPLATE_BACKUP, "").orEmpty()
    }.getOrDefault("")

    fun backupCustom(context: Context): String = runCatching {
        context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)?.getString(KEY_CUSTOM_BACKUP, "").orEmpty()
    }.getOrDefault("")

    fun setBackupTemplate(context: Context, id: String) {
        runCatching { SettingsProvider.save(context) { putString(KEY_TEMPLATE_BACKUP, id) } }
    }

    fun setBackupCustom(context: Context, text: String) {
        runCatching { SettingsProvider.save(context) { putString(KEY_CUSTOM_BACKUP, text) } }
    }

    fun setReply(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_REPLY, value) } }
    }

    fun setTopics(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_TOPICS, value) } }
    }

    fun setRelay(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_RELAY, value) } }
    }

    fun setAide(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_AIDE, value) } }
    }

    fun setIntel(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_INTEL, value) } }
    }

    fun setCommunicate(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_COMMUNICATE, value) } }
    }

    fun setWarReview(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_WAR_REVIEW, value) } }
    }

    fun setTemplate(context: Context, value: String) {
        runCatching { SettingsProvider.save(context) { putString(KEY_TEMPLATE, value) } }
    }

    fun setCustom(context: Context, value: String) {
        runCatching { SettingsProvider.save(context) { putString(KEY_CUSTOM, value.take(500)) } }
    }

    private fun readBoolean(context: Context, key: String, snapshot: Boolean?): Boolean = runCatching {
        if (context.packageName == MODULE) {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)?.getBoolean(key, false) ?: false
        } else {
            snapshot ?: false
        }
    }.getOrDefault(false)
}
