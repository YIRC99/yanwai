package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.SettingsProvider

/**
 * 续话（内联补全）设置：开关 + 触发字数。
 *
 * 都存进回复设置同一份配置文件，随 [SettingsProvider] / SettingsSync 的快照同步到微信进程。
 *
 * 为什么不用 LSPosed 的 XSharedPreferences：微信进程读模块私有目录会被 SELinux 拒掉
 * （实测读到的一直是 false），而这套设置通道本来就是给微信用的，权限模型是现成的。
 */
object RelayPrefs {
    const val KEY_ENABLED = "relay_enabled"
    const val KEY_MIN_PREFIX = "relay_min_prefix"
    const val KEY_CONTEXT_LIMIT = "relay_context_limit"
    const val KEY_CANDIDATES = "relay_candidates"
    const val DEFAULT_MIN_PREFIX = 4
    const val DEFAULT_CONTEXT_LIMIT = 20
    /** 一次拉取多少条候选：条数越多本地命中率越高，单次请求也越慢、越贵。 */
    const val DEFAULT_CANDIDATES = 50
    val MIN_PREFIX_RANGE = 2..20
    val CONTEXT_LIMIT_RANGE = 10..100
    val CANDIDATES_RANGE = 5..150
    private const val MODULE = "com.dogheadstrategist.chat"

    fun enabled(context: Context): Boolean = runCatching {
        if (isModule(context)) {
            local(context)?.getBoolean(KEY_ENABLED, false) ?: false
        } else {
            // 微信进程：settings() 为 null 说明快照还没同步过来，按关闭处理。
            ModulePrefs.settings()?.relayEnabled ?: false
        }
    }.getOrDefault(false)

    /** 输入达到多少字数才开始预测。 */
    fun minPrefix(context: Context): Int = runCatching {
        val value = if (isModule(context)) {
            local(context)?.getInt(KEY_MIN_PREFIX, DEFAULT_MIN_PREFIX) ?: DEFAULT_MIN_PREFIX
        } else {
            ModulePrefs.settings()?.relayMinPrefix ?: DEFAULT_MIN_PREFIX
        }
        value.coerceIn(MIN_PREFIX_RANGE.first, MIN_PREFIX_RANGE.last)
    }.getOrDefault(DEFAULT_MIN_PREFIX)

    /** 续话读多少条聊天记录作为上下文。条数越多越贴题，token 花费也越高。 */
    fun contextLimit(context: Context): Int = runCatching {
        val value = if (isModule(context)) {
            local(context)?.getInt(KEY_CONTEXT_LIMIT, DEFAULT_CONTEXT_LIMIT) ?: DEFAULT_CONTEXT_LIMIT
        } else {
            ModulePrefs.settings()?.relayContextLimit ?: DEFAULT_CONTEXT_LIMIT
        }
        value.coerceIn(CONTEXT_LIMIT_RANGE.first, CONTEXT_LIMIT_RANGE.last)
    }.getOrDefault(DEFAULT_CONTEXT_LIMIT)

    /** 一次让模型生成多少条候选。 */
    fun candidateCount(context: Context): Int = runCatching {
        val value = if (isModule(context)) {
            local(context)?.getInt(KEY_CANDIDATES, DEFAULT_CANDIDATES) ?: DEFAULT_CANDIDATES
        } else {
            ModulePrefs.settings()?.relayCandidates ?: DEFAULT_CANDIDATES
        }
        value.coerceIn(CANDIDATES_RANGE.first, CANDIDATES_RANGE.last)
    }.getOrDefault(DEFAULT_CANDIDATES)

    /** 写入后立即 publish，正在运行的微信会收到广播并刷新快照。 */
    fun setEnabled(context: Context, value: Boolean) {
        runCatching { SettingsProvider.save(context) { putBoolean(KEY_ENABLED, value) } }
    }

    fun setMinPrefix(context: Context, value: Int) {
        val clamped = value.coerceIn(MIN_PREFIX_RANGE.first, MIN_PREFIX_RANGE.last)
        runCatching { SettingsProvider.save(context) { putInt(KEY_MIN_PREFIX, clamped) } }
    }

    fun setContextLimit(context: Context, value: Int) {
        val clamped = value.coerceIn(CONTEXT_LIMIT_RANGE.first, CONTEXT_LIMIT_RANGE.last)
        runCatching { SettingsProvider.save(context) { putInt(KEY_CONTEXT_LIMIT, clamped) } }
    }

    fun setCandidateCount(context: Context, value: Int) {
        val clamped = value.coerceIn(CANDIDATES_RANGE.first, CANDIDATES_RANGE.last)
        runCatching { SettingsProvider.save(context) { putInt(KEY_CANDIDATES, clamped) } }
    }

    private fun isModule(context: Context) = context.packageName == MODULE

    private fun local(context: Context) = runCatching {
        context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
    }.getOrNull()
}
