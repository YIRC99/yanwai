package com.dogheadstrategist.chat.feature

import android.content.Context
import android.content.SharedPreferences
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.SettingsProvider

/**
 * 自动起草设置：收到通知时提前准备回复。
 *
 * 存法与其它模块一致：值落在本 app 的 prefs，经 SettingsProvider 快照同步进微信进程。
 * 模块进程读 prefs，微信进程读快照。
 */
object SentryPrefs {
    const val KEY_ENABLED = "sentry_enabled"
    const val KEY_COUNT = "sentry_count"
    const val KEY_CONTEXT = "sentry_context"
    const val KEY_COOLDOWN = "sentry_cooldown"
    const val KEY_ONLY_UNREPLIED = "sentry_only_unreplied"

    /** 本地同步多少个最近会话的聊天记录，供 app 进程在微信冻结时独立生成。 */
    const val KEY_SYNC_USERS = "sentry_sync_users"

    const val DEFAULT_COUNT = 3
    const val DEFAULT_CONTEXT = 20
    const val DEFAULT_COOLDOWN = 60
    const val DEFAULT_SYNC_USERS = 5
    /** 同步会话数上限，与界面滑块 valueTo 保持一致。 */
    const val MAX_SYNC_USERS = 50

    /**
     * 白名单：开启后只有名单内的联系人会触发自动起草。
     * 注意这里存的是「谁可以进来」，不是「事后过滤」——同步阶段就会跳过名单外的会话，
     * 名单外的聊天记录既不会读也不会传。
     */
    const val KEY_WHITELIST_ENABLED = "sentry_whitelist_enabled"
    /**
     * 测试模式：不管有没有真实建议，所有聊天界面都挂上示例卡片。
     *
     * 只影响「显示」，不触发任何模型请求——用来确认卡片挂载、位置、样式是否正常，
     * 不必等自动起草真的生成一次。
     */
    const val KEY_TEST_MODE = "sentry_test_mode"

    /**
     * 建议的保留时长（分钟），到点自动撤销。
     *
     * 0 表示永不过期：旧建议会一直挂着，直到被新消息顶掉或手动清除。
     * 默认 10 分钟——自动起草是为"正在发生的对话"准备的，放太久反而容易误发。
     */
    const val KEY_CACHE_TTL_MINUTES = "sentry_cache_ttl_minutes"

    const val DEFAULT_CACHE_TTL_MINUTES = 10
    const val KEY_WHITELIST = "sentry_whitelist"

    /** 白名单条目：talker 用于同步阶段的精确过滤，nick 用于通知标题的宽松匹配。 */
    data class WhitelistEntry(val talker: String, val nick: String)
    private const val MODULE = "com.dogheadstrategist.chat"

    // ---- 请求侧（微信进程）：读快照 ----
    fun enabledForRequest(): Boolean = snapshot()?.sentryEnabled ?: false
    fun countForRequest(): Int = (snapshot()?.sentryCount ?: DEFAULT_COUNT).coerceIn(1, 5)
    fun contextForRequest(): Int = (snapshot()?.sentryContext ?: DEFAULT_CONTEXT).coerceIn(6, 60)
    fun cooldownForRequest(): Int = (snapshot()?.sentryCooldown ?: DEFAULT_COOLDOWN).coerceIn(10, 600)
    fun onlyUnrepliedForRequest(): Boolean = snapshot()?.sentryOnlyUnreplied ?: true
    fun syncUsersForRequest(): Int = (snapshot()?.sentrySyncUsers ?: DEFAULT_SYNC_USERS).coerceIn(1, MAX_SYNC_USERS)
    fun whitelistEnabledForRequest(): Boolean = snapshot()?.sentryWhitelistEnabled ?: false

    fun testModeForRequest(): Boolean = runCatching { snapshot()?.sentryTestMode ?: false }.getOrDefault(false)

    fun cacheTtlMinutesForRequest(): Int =
        runCatching { snapshot()?.sentryCacheTtlMinutes ?: DEFAULT_CACHE_TTL_MINUTES }
            .getOrDefault(DEFAULT_CACHE_TTL_MINUTES)
    fun whitelistForRequest(): List<WhitelistEntry> = parseWhitelist(snapshot()?.sentryWhitelist.orEmpty())

    private fun snapshot() = runCatching { ModulePrefs.settings() }.getOrNull()

    // ---- 设置侧（模块进程）----
    fun enabled(context: Context): Boolean = readBoolean(context, KEY_ENABLED, false)
    fun count(context: Context): Int = readInt(context, KEY_COUNT, DEFAULT_COUNT)
    fun contextLimit(context: Context): Int = readInt(context, KEY_CONTEXT, DEFAULT_CONTEXT)
    fun cooldown(context: Context): Int = readInt(context, KEY_COOLDOWN, DEFAULT_COOLDOWN)
    fun onlyUnreplied(context: Context): Boolean = readBoolean(context, KEY_ONLY_UNREPLIED, true)
    fun syncUsers(context: Context): Int = readInt(context, KEY_SYNC_USERS, DEFAULT_SYNC_USERS)
    fun whitelistEnabled(context: Context): Boolean = readBoolean(context, KEY_WHITELIST_ENABLED, false)
    fun whitelist(context: Context): List<WhitelistEntry> = parseWhitelist(readString(context, KEY_WHITELIST, ""))

    fun setEnabled(context: Context, value: Boolean) = save(context) { putBoolean(KEY_ENABLED, value) }
    fun setCount(context: Context, value: Int) = save(context) { putInt(KEY_COUNT, value.coerceIn(1, 5)) }
    fun setContextLimit(context: Context, value: Int) = save(context) { putInt(KEY_CONTEXT, value.coerceIn(6, 60)) }
    fun setCooldown(context: Context, value: Int) = save(context) { putInt(KEY_COOLDOWN, value.coerceIn(10, 600)) }
    fun setOnlyUnreplied(context: Context, value: Boolean) = save(context) { putBoolean(KEY_ONLY_UNREPLIED, value) }
    fun setSyncUsers(context: Context, value: Int) = save(context) { putInt(KEY_SYNC_USERS, value.coerceIn(1, 20)) }
    fun setWhitelistEnabled(context: Context, value: Boolean) = save(context) { putBoolean(KEY_WHITELIST_ENABLED, value) }

    fun cacheTtlMinutes(context: Context): Int =
        readInt(context, KEY_CACHE_TTL_MINUTES, DEFAULT_CACHE_TTL_MINUTES)

    fun setCacheTtlMinutes(context: Context, value: Int) =
        save(context) { putInt(KEY_CACHE_TTL_MINUTES, value.coerceAtLeast(0)) }

    fun testMode(context: Context): Boolean = readBoolean(context, KEY_TEST_MODE, false)
    fun setTestMode(context: Context, value: Boolean) = save(context) { putBoolean(KEY_TEST_MODE, value) }

    fun setWhitelist(context: Context, entries: List<WhitelistEntry>) = save(context) { putString(KEY_WHITELIST, toJson(entries)) }

    private fun prefs(context: Context): SharedPreferences? =
        if (context.packageName == MODULE) context.getSharedPreferences(ModulePrefs.FILE_NAME, 0) else null

    private fun readBoolean(context: Context, key: String, fallback: Boolean): Boolean =
        runCatching { prefs(context)?.getBoolean(key, fallback) ?: fallback }.getOrDefault(fallback)

    private fun readInt(context: Context, key: String, fallback: Int): Int =
        runCatching { prefs(context)?.getInt(key, fallback) ?: fallback }.getOrDefault(fallback)

    private fun readString(context: Context, key: String, fallback: String): String =
        runCatching { prefs(context)?.getString(key, fallback) ?: fallback }.getOrDefault(fallback)

    /**
     * 昵称归一化。同一个联系人，通知标题里可能带表情占位（「小倩倩[微笑]」），
     * 而聊天库里存的是「小倩倩」，直接比会漏。去掉方括号占位和所有空白再比。
     */
    fun nickKey(raw: String): String =
        raw.replace(Regex("\\[[^\\]]{1,8}\\]"), "").replace(Regex("[\\s\\u2005\\u00a0]"), "").trim()

    /** 单条白名单命中：talker 精确相等，或昵称归一化后相等。 */
    /**
     * 微信内置的系统账号：既不是真人也不是群聊。
     *
     * 这些会话出现在清单里没有意义——自动起草不可能给「文件传输助手」准备回复，
     * 而且它们会占掉同步名额，把真正要用的联系人挤出去。
     */
    private val SYSTEM_TALKERS = setOf(
        "filehelper",          // 文件传输助手
        "weixin",              // 微信团队
        "notifymessage",       // 服务通知
        "qqmail",              // QQ 邮箱提醒
        "medianote",           // 语音记事本
        "floatbottle",         // 漂流瓶
        "newsapp",             // 腾讯新闻
        "fmessage",            // 朋友推荐消息
        "weibo",               // 腾讯微博
        "tmessage",            // 摇一摇
        "qmessage",            // QQ 离线消息
        "mphelper",            // 公众号助手
        "brandsessionholder",  // 品牌服务号容器
        "weixinpay",           // 微信支付
        "wxpay",
        "weixincard",
    )


    fun hits(entry: WhitelistEntry, talker: String, nick: String): Boolean =
        (entry.talker.isNotEmpty() && entry.talker == talker) ||
            (entry.nick.isNotEmpty() && nickKey(entry.nick) == nickKey(nick))

    /** 名单是否放行这个联系人。名单为空视为全部放行，避免误开开关后什么都不触发。 */
    fun allows(entries: List<WhitelistEntry>, talker: String, nick: String): Boolean =
        entries.isEmpty() || entries.any { hits(it, talker, nick) }

    private fun parseWhitelist(json: String): List<WhitelistEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val talker = obj.optString("t").orEmpty()
                val nick = obj.optString("n").orEmpty()
                if (talker.isEmpty() && nick.isEmpty()) null else WhitelistEntry(talker, nick)
            }
        }.getOrDefault(emptyList())
    }

    private fun toJson(entries: List<WhitelistEntry>): String =
        org.json.JSONArray().apply {
            entries.forEach { entry ->
                put(org.json.JSONObject().apply { put("t", entry.talker); put("n", entry.nick) })
            }
        }.toString()

    private fun save(context: Context, edit: SharedPreferences.Editor.() -> Unit) {
        runCatching { SettingsProvider.save(context, edit) }
    }
}
