package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog

/**
 * 四个主功能的统一登记处。
 *
 * 界面只从这里取模块与状态，不直接引用具体实现，新增功能时改这里即可。
 */
object StrategistModules {
    val all: List<StrategistModule> = listOf(StrategistCore, Sentinel, Relay, Intelligence, Communicate, Aide)

    fun of(feature: StrategistFeature): StrategistModule =
        all.firstOrNull { it.feature == feature } ?: StrategistCore

    /** 宿主启动时挂载全部功能。单个功能失败不影响其他功能。 */
    fun install(context: Context) {
        for (module in all) {
            runCatching { module.install(context) }
                .onFailure { MoodLog.e("FEATURE_INSTALL_FAILED ${module.feature.key}", it) }
        }
    }

    fun release() {
        for (module in all) runCatching { module.release() }
    }

    /** 界面用的状态文案，与 [StrategistFeature.Stage] 保持一致。 */
    fun statusText(module: StrategistModule): String =
        if (module.ready) "可用" else "开发中"

    /**
     * 该功能当前是否真的开着。
     *
     * 代笔没有开关（在微信里手动点才触发），永远算启用；
     * 其余各自查自己的配置，没有配置的按关闭处理。
     */
    fun isEnabled(context: Context, feature: StrategistFeature): Boolean = when (feature) {
        StrategistFeature.STRATEGIST -> true
        StrategistFeature.SENTINEL -> SentryPrefs.enabled(context)
        StrategistFeature.RELAY -> RelayPrefs.enabled(context)
        StrategistFeature.INTELLIGENCE -> IntelligencePrefs.enabled(context)
        StrategistFeature.COMMUNICATE -> CommunicatePrefs.enabled(context)
        StrategistFeature.AIDE -> AidePrefs.enabled(context)
    }

}
