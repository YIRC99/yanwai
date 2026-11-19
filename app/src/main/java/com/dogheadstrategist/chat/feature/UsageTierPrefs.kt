package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.SettingsProvider
import com.dogheadstrategist.chat.reply.ReplySettings

/**
 * 用量档位：把散落在各面板里的「烧 token 的参数」打包成低/中/高三个预设。
 *
 * 为什么要有这个：单看「上下文 20 条」「候选 35 个」没人知道这要花多少钱。
 * 分档之后用户只需要选「省着用」还是「效果好」，换算交给这里。
 *
 * 两条原则：
 * 1. **只动参数，不动开关。** 关着的功能不会因为切到高档被打开——
 *    那是用户的意愿，不该被一个用量选项顺手改掉。
 * 2. **用户手一动就退位。** 任何一个相关参数被手动改过，档位立刻变成
 *    自定义，不再声称自己是某个预设，否则界面显示的档位会和实际参数对不上。
 */
object UsageTierPrefs {
    const val KEY_TIER = "usage_tier"

    enum class Tier(val id: String, val label: String, val hint: String) {
        LOW("low", "低", "省着用：上下文减半，触发门槛提高，批量间隔拉长。"),
        MEDIUM("medium", "中", "默认：速度和用量的平衡点，多数人不用改。"),
        HIGH("high", "高", "效果优先：上下文加倍，更早触发，更频繁地批量处理。"),
        CUSTOM("custom", "自定义", "你手动调过参数，档位不再替你管。"),
        ;

        companion object {
            fun fromId(id: String?): Tier? = entries.firstOrNull { it.id == id }
        }
    }

    /** 一档所包含的全部参数。字段顺序与预设表一致，方便直接比对。 */
    data class Params(
        val relayContextLimit: Int,
        val relayCandidates: Int,
        val relayMinPrefix: Int,
        val sentryContext: Int,
        val sentryCount: Int,
        val sentryCooldown: Int,
        val aideIntervalMin: Int,
        val maxTokens: Int,
    )

    /**
     * 预设表。
     *
     * 四个维度决定 token：上下文条数（prompt 大小）、候选数（completion 大小）、
     * 触发门槛与冷却（调用次数）、输出上限（防止模型话痨）。
     * 高中低大致是 2:1:0.5 的关系。
     *
     * maxTokens 中档保持 4200（滑块旧顶格），高档放到 8200（滑块新顶格，约等于模型 8K 输出上限）。
     * 实际 completion 通常只有几十到两百 token，这项主要是兜底防跑飞，
     * 所以中档不动也不亏——真需要长输出时再切高档。
     */
    private val PRESETS: Map<Tier, Params> = mapOf(
        Tier.LOW to Params(
            relayContextLimit = 10,
            relayCandidates = 15,
            relayMinPrefix = 6,
            sentryContext = 10,
            sentryCount = 2,
            sentryCooldown = 180,
            aideIntervalMin = 20,
            maxTokens = 1200,
        ),
        Tier.MEDIUM to Params(
            relayContextLimit = 20,
            relayCandidates = 35,
            relayMinPrefix = 3,
            sentryContext = 20,
            sentryCount = 3,
            sentryCooldown = 60,
            aideIntervalMin = 5,
            maxTokens = 4200,
        ),
        Tier.HIGH to Params(
            relayContextLimit = 40,
            relayCandidates = 60,
            relayMinPrefix = 2,
            sentryContext = 40,
            sentryCount = 5,
            sentryCooldown = 30,
            aideIntervalMin = 2,
            maxTokens = 8200,
        ),
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)

    /** 当前档位。没存过时按现有参数反推，老用户不会被强行改设置。 */
    fun current(context: Context): Tier = runCatching {
        Tier.fromId(prefs(context).getString(KEY_TIER, null))
    }.getOrNull() ?: detect(context)

    /** 按参数反推：正好等于某个预设就是那一档，否则自定义。 */
    private fun detect(context: Context): Tier {
        val now = params(context)
        return PRESETS.entries.firstOrNull { it.value == now }?.key ?: Tier.CUSTOM
    }

    /** 读当前的全部相关参数。 */
    fun params(context: Context): Params = Params(
        relayContextLimit = RelayPrefs.contextLimit(context),
        relayCandidates = RelayPrefs.candidateCount(context),
        relayMinPrefix = RelayPrefs.minPrefix(context),
        sentryContext = SentryPrefs.contextLimit(context),
        sentryCount = SentryPrefs.count(context),
        sentryCooldown = SentryPrefs.cooldown(context),
        aideIntervalMin = AidePrefs.digestIntervalMin(context),
        maxTokens = prefs(context).getInt(ReplySettings.KEY_MAX_TOKENS, ReplySettings.DEFAULT_MAX_TOKENS),
    )

    /**
     * 把一档写进去。
     *
     * [Tier.CUSTOM] 没有预设可写，直接返回——它是个结果状态，不是可执行的动作。
     */
    fun apply(context: Context, tier: Tier) {
        val preset = PRESETS[tier] ?: return
        // 八项合并成一次写入。逐个调 setter 的话，每一次背后都是一遍
        // SettingsProvider.save ——那种写法会连着递增 8 次 revision、
        // 发 8 次跨进程广播，微信进程要跟着 reload 八遍设置。
        SettingsProvider.save(context) {
            putInt(
                RelayPrefs.KEY_CONTEXT_LIMIT,
                preset.relayContextLimit.coerceIn(
                    RelayPrefs.CONTEXT_LIMIT_RANGE.first, RelayPrefs.CONTEXT_LIMIT_RANGE.last),
            )
            putInt(
                RelayPrefs.KEY_CANDIDATES,
                preset.relayCandidates.coerceIn(
                    RelayPrefs.CANDIDATES_RANGE.first, RelayPrefs.CANDIDATES_RANGE.last),
            )
            putInt(
                RelayPrefs.KEY_MIN_PREFIX,
                preset.relayMinPrefix.coerceIn(
                    RelayPrefs.MIN_PREFIX_RANGE.first, RelayPrefs.MIN_PREFIX_RANGE.last),
            )
            putInt(SentryPrefs.KEY_CONTEXT, preset.sentryContext.coerceIn(6, 60))
            putInt(SentryPrefs.KEY_COUNT, preset.sentryCount.coerceIn(1, 5))
            putInt(SentryPrefs.KEY_COOLDOWN, preset.sentryCooldown.coerceIn(10, 600))
            putInt(
                AidePrefs.KEY_DIGEST_INTERVAL,
                preset.aideIntervalMin.coerceIn(
                    AidePrefs.MIN_DIGEST_INTERVAL_MIN, AidePrefs.MAX_DIGEST_INTERVAL_MIN),
            )
            putInt(
                ReplySettings.KEY_MAX_TOKENS,
                preset.maxTokens.coerceIn(ReplySettings.MIN_MAX_TOKENS, ReplySettings.MAX_MAX_TOKENS),
            )
        }
        saveTier(context, tier)
    }

    fun saveTier(context: Context, tier: Tier) {
        runCatching {
            prefs(context).edit().putString(KEY_TIER, tier.id).apply()
        }
        ModulePrefs.reload(true)
    }

    /**
     * 用户手动改了任一相关参数时调用。
     *
     * 只在档位还不是自定义时写一次，避免每拖一格滑块就刷一遍磁盘。
     */
    fun markCustom(context: Context) {
        if (current(context) != Tier.CUSTOM) saveTier(context, Tier.CUSTOM)
    }
}
