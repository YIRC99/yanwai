package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.SettingsProvider

/**
 * 人物档案的开关与触发阈值。
 *
 * 阈值定成「攒够多少条才总结」而不是「多久总结一次」，是因为聊天有疏有密：
 * 按时间触发的话，冷清的对象会拿三五条消息就去总结，画像必然很飘；
 * 按条数触发则每条画像都有足够的观察量撑着。
 */
object IntelligencePrefs {
    const val KEY_ENABLED = "intel_enabled"
    const val KEY_MIN_SAMPLES = "intel_min_samples"
    const val KEY_MAX_CHARS = "intel_max_chars"
    const val KEY_INTERVAL_HOURS = "intel_interval_hours"

    const val DEFAULT_ENABLED = false

    /**
     * 默认攒 20 条再总结。
     *
     * 低于 10 条基本只能看出「这个人会说话」，再往上又会让画像更新太慢，
     * 一天聊几十条的活跃对象可能两天才更新一次。20 是个折中。
     */
    const val DEFAULT_MIN_SAMPLES = 20

    /**
     * 时间兜底：距上次总结超过这么多小时，即使没攒够条数也整理一次。
     *
     * 0 = 关闭，只看条数。默认关闭——按时间触发本质上是拿观察量换时效：
     * 一条消息就总结出来的画像必然很飘，值不值得由用户自己定。
     */
    const val DEFAULT_INTERVAL_HOURS = 0

    /** 画像上限。太长会挤占回复 prompt 的预算，600 字已经够写清一个人。 */
    const val DEFAULT_MAX_CHARS = 600

    const val MIN_SAMPLES_FLOOR = 5
    const val MIN_SAMPLES_CEIL = 100
    const val MAX_CHARS_FLOOR = 200
    const val MAX_CHARS_CEIL = 1200
    const val INTERVAL_FLOOR = 0
    const val INTERVAL_CEIL = 168

    fun enabled(context: Context): Boolean =
        readBoolean(context, KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, value: Boolean) =
        save(context) { putBoolean(KEY_ENABLED, value) }

    fun minSamples(context: Context): Int =
        readInt(context, KEY_MIN_SAMPLES, DEFAULT_MIN_SAMPLES)
            .coerceIn(MIN_SAMPLES_FLOOR, MIN_SAMPLES_CEIL)

    fun setMinSamples(context: Context, value: Int) = save(context) {
        putInt(KEY_MIN_SAMPLES, value.coerceIn(MIN_SAMPLES_FLOOR, MIN_SAMPLES_CEIL))
    }

    fun maxChars(context: Context): Int =
        readInt(context, KEY_MAX_CHARS, DEFAULT_MAX_CHARS)
            .coerceIn(MAX_CHARS_FLOOR, MAX_CHARS_CEIL)

    fun setMaxChars(context: Context, value: Int) = save(context) {
        putInt(KEY_MAX_CHARS, value.coerceIn(MAX_CHARS_FLOOR, MAX_CHARS_CEIL))
    }

    /** 时间兜底的小时数。0 表示关闭。 */
    fun intervalHours(context: Context): Int =
        readInt(context, KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
            .coerceIn(INTERVAL_FLOOR, INTERVAL_CEIL)

    fun setIntervalHours(context: Context, value: Int) = save(context) {
        putInt(KEY_INTERVAL_HOURS, value.coerceIn(INTERVAL_FLOOR, INTERVAL_CEIL))
    }

    /** 微信进程侧：开关。总结与读取画像都以此来判。 */
    fun enabledForRequest(): Boolean =
        runCatching { ModulePrefs.settings()?.intelEnabled ?: DEFAULT_ENABLED }
            .getOrDefault(DEFAULT_ENABLED)

    /** 微信进程侧：阈值。 */
    fun minSamplesForRequest(): Int =
        runCatching { ModulePrefs.settings()?.intelMinSamples ?: DEFAULT_MIN_SAMPLES }
            .getOrDefault(DEFAULT_MIN_SAMPLES)
            .coerceIn(MIN_SAMPLES_FLOOR, MIN_SAMPLES_CEIL)

    /** 微信进程侧：时间兜底小时数。 */
    fun intervalHoursForRequest(): Int =
        runCatching { ModulePrefs.settings()?.intelIntervalHours ?: DEFAULT_INTERVAL_HOURS }
            .getOrDefault(DEFAULT_INTERVAL_HOURS)
            .coerceIn(INTERVAL_FLOOR, INTERVAL_CEIL)

    private fun readBoolean(context: Context, key: String, fallback: Boolean): Boolean =
        runCatching {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getBoolean(key, fallback)
        }.getOrDefault(fallback)

    private fun readInt(context: Context, key: String, fallback: Int): Int =
        runCatching {
            context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getInt(key, fallback)
        }.getOrDefault(fallback)

    private fun save(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        runCatching { SettingsProvider.save(context, edit) }
    }
}
