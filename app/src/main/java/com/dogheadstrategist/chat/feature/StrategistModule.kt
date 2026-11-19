package com.dogheadstrategist.chat.feature

import android.content.Context

/**
 * 一个主功能的骨架。
 *
 * 约定：
 * - [install] 只在宿主启动时调用一次，负责挂载该功能需要的钩子或监听；
 *   框架阶段这些方法可以是空的，但不能省略，否则后续接入时没有统一落点。
 * - [release] 与 [install] 对称，用于模块停用、进程退出或设置变更时回收。
 * - [ready] 表示能力是否真的可用，界面据此显示「可用」或「开发中」。
 */
interface StrategistModule {
    val feature: StrategistFeature

    /** 能力是否已经实现并可对外提供。 */
    val ready: Boolean get() = feature.stage == StrategistFeature.Stage.AVAILABLE

    /** 挂载入口。框架阶段保持空实现，不做任何后台行为。 */
    fun install(context: Context) = Unit

    /** 卸载入口，与 [install] 成对出现。 */
    fun release() = Unit
}
