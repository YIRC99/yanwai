package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.reply.CommunicateProtocol
import org.json.JSONArray

/**
 * 通知代办的开关与本地预筛。
 *
 * 预筛不是为了省 token——用户说过消耗无所谓。它是为了两件更实际的事：
 *
 * 1. 群聊里九成是闲聊。每条都往 prompt 里塞一份类型清单，通知摘要那条通知会变慢，
 *    而且类型清单会把模型的注意力往「抽取」上带，摘要容易写成字段列表。
 * 2. 让模型每次都去判断「这条要不要记」比先筛掉明显不需要的，误记率更低。
 *
 * 命中任何一条规则才走完整 prompt，否则退回只回信的 BRIEF 形态，和现在一模一样。
 */
object AidePrefs {
    const val KEY_ENABLED = "aide_enabled"
    const val KEY_TYPES = "aide_types"
    const val KEY_DIGEST_INTERVAL = "aide_digest_interval_min"
    const val KEY_KEY_USERS = "aide_key_users"

    const val DEFAULT_ENABLED = false

    /** 两次投送之间至少隔多久，单位分钟。默认 5 分钟。 */
    const val DEFAULT_DIGEST_INTERVAL_MIN = 5

    /** 间隔下限。给到 1 分钟已经是高频了，再低就变成拿模型当轮询接口。 */
    const val MIN_DIGEST_INTERVAL_MIN = 1

    /** 间隔上限一小时。更长的间隔意味着消息可能躺到用户都忘了。 */
    const val MAX_DIGEST_INTERVAL_MIN = 60

    /**
     * 预筛规则。宁可松一点——漏掉的要事用户永远不知道该找回来，
     * 而多跑几次模型只是多花点钱。
     */
    private val RULES: List<Regex> = listOf(
        // 时间：明天/后天/周X/下周/几点几分/几月几号
        Regex("明天|后天|大后天|今天|今晚|今早|明早|明晚|周一|周二|周三|周四|周五|周六|周日|周天|下个?周|下周[一二三四五六日天]"),
        Regex("\\d{1,2}\\s*[点時时:：]\\s*\\d{0,2}\\s*分?"),
        Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]"),
        // 地点：几号楼/几室/会议室/门口/集合
        Regex("\\d+\\s*[号楼栋室层]|[一二三四五六七八九十]+号(楼|室)|会议室|办公室|教室|门口|集合|地点"),
        // 待办：动作词
        Regex("记得|别忘|不要忘|交一下|交给我|发给|带上|带过来|截止|之前|前给我|尽快|麻烦你|帮我\\S{0,4}(拿|带|查|买)"),
        // 金额
        Regex("\\d+\\s*[元块钱]|转你|转我|收款|付款|红包|会费|学费|报销"),
        // 码类
        Regex("验证码|取件码|取餐码|取货码|快递|菜鸟|驿站|取件号"),
        Regex("[A-Za-z0-9]{4,}"),
        // 明确的通知口吻
        Regex("通知|开会|例会|面试|报到|签到|报名|缴费|安排|调整|取消|延期"),
    )

    /** 正文短到这个程度基本没有可记的信息，直接不跑抽取。 */
    private const val MIN_LENGTH = 8

    /** 微信进程侧：开关。 */
    fun enabledForRequest(): Boolean =
        runCatching { ModulePrefs.settings()?.aideEnabled ?: DEFAULT_ENABLED }.getOrDefault(DEFAULT_ENABLED)

    /**
     * 微信进程侧：当前生效的类型清单。
     *
     * 读不到、或者解析出来是空的，都退回预置六种——模型没有类型可认的话，
     * 抽出来的 title 会是它自己编的，历史记录里会出现一堆认不出的分组。
     */
    fun typesForRequest(): List<MatterType> {
        val raw = runCatching { ModulePrefs.settings()?.aideTypes }.getOrNull()
        if (raw.isNullOrBlank()) return MatterType.PRESETS
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return MatterType.PRESETS
        if (array.length() == 0) return MatterType.PRESETS
        val list = ArrayList<MatterType>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            MatterType.fromJson(obj)?.let { list.add(it) }
        }
        return if (list.isEmpty()) MatterType.PRESETS else list
    }

    /**
     * 微信进程侧：关键用户清单。
     *
     * 空清单表示不过滤，保持原有行为——老用户升级上来不会因为多了这个设置
     * 就突然什么都提取不到。这是这个功能唯一必须守住的下限行为。
     */
    fun keyUsersForRequest(): List<String> {
        val raw = runCatching { ModulePrefs.settings()?.aideKeyUsers }.getOrNull()
        return decodeKeyUsers(raw)
    }


    /**
     * 这条消息值不值得跑一次抽取。
     *
     * 只看正文字数和关键词，不调模型。误判的代价是「多看一次」或「漏记一条」，
     * 所以门槛设得比直觉低——命中一条规则就放行。
     *
     * 注意：正式链路（[AideDigest.onNotice]）已经不走本地预筛了，判断交给模型。
     * 这里保留是给设置页的「试提取」用——测试时先挡掉明显没信息量的消息，
     * 免得每次点都白白调一次模型。
     */
    fun shouldExtract(text: String): Boolean {
        val body = CommunicateProtocol.splitSpeaker(text).second.trim()
        if (body.length < MIN_LENGTH) return false
        return RULES.any { it.containsMatchIn(body) }
    }

    // ---- 设置侧（模块进程）----

    fun enabled(context: Context): Boolean = readBoolean(context, KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, value: Boolean) = save(context) { putBoolean(KEY_ENABLED, value) }

    /** app 进程侧：关键用户清单。用户只关心与这些人相关的事项。 */
    fun keyUsers(context: Context): List<String> =
        decodeKeyUsers(
            runCatching {
                context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getString(KEY_KEY_USERS, "")
            }.getOrNull(),
        )

    /** 给快照打包用：微信进程读不到 app 的 filesDir，只能靠这一份字符串带过去。 */
    fun keyUsersJson(context: Context): String = JSONArray(keyUsers(context)).toString()

    fun setKeyUsers(context: Context, users: List<String>) {
        // 去空白、去重、丢掉空项：用户手输的东西什么形状都有
        val clean = users.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        save(context) { putString(KEY_KEY_USERS, JSONArray(clean).toString()) }
    }

    /** 解析清单。任何异常都退回空清单，让功能回到「不过滤」。 */
    private fun decodeKeyUsers(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val name = array.optString(index).trim()
            if (name.isNotEmpty()) out.add(name)
        }
        return out
    }

    /** 投送间隔（分钟）。读不到或越界都退回默认值。 */
    fun digestIntervalMin(context: Context): Int =
        readInt(context, KEY_DIGEST_INTERVAL, DEFAULT_DIGEST_INTERVAL_MIN)
            .coerceIn(MIN_DIGEST_INTERVAL_MIN, MAX_DIGEST_INTERVAL_MIN)

    fun setDigestIntervalMin(context: Context, minutes: Int) =
        save(context) {
            putInt(KEY_DIGEST_INTERVAL, minutes.coerceIn(MIN_DIGEST_INTERVAL_MIN, MAX_DIGEST_INTERVAL_MIN))
        }

    private fun readBoolean(context: Context, key: String, fallback: Boolean): Boolean =
        runCatching {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getBoolean(key, fallback)
        }.getOrDefault(fallback)

    private fun readInt(context: Context, key: String, fallback: Int): Int =
        runCatching {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getInt(key, fallback)
        }.getOrDefault(fallback)

    private fun save(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        runCatching { com.dogheadstrategist.chat.core.SettingsProvider.save(context, edit) }
    }
}
