package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.hook.RelayCompletion

/** 代笔：现有能力，在聊天里手动触发。这里只登记身份，能力由回复与话题链路实现。 */
object StrategistCore : StrategistModule {
    override val feature = StrategistFeature.STRATEGIST

    override fun install(context: Context) {
        MoodLog.i("FEATURE_INSTALLED ${feature.key} ready=${ready}")
    }
}

/**
 * 自动起草：自动读取通知，在你打开聊天前提前准备回复。
 *
 * 框架阶段刻意不接入任何系统监听：通知读取需要用户在系统设置里单独授权
 * NotificationListenerService，在没有这个入口之前引入后台行为只会带来困惑。
 * 接入时的落点已经明确：
 * 1. AndroidManifest 声明继承 NotificationListenerService 的服务；
 * 2. 在 [install] 里登记该服务的连接状态；
 * 3. 通知到达时解析发送者与内容，交给回复链路预生成。
 */
object Sentinel : StrategistModule {
    override val feature = StrategistFeature.SENTINEL

    override fun install(context: Context) {
        if (context.packageName != "com.tencent.mm") {
            MoodLog.i("FEATURE_INSTALLED ${feature.key} ready=$ready pending=notification-permission")
            return
        }
        com.dogheadstrategist.chat.sentry.SentryAgent.install(context)
        MoodLog.i("FEATURE_INSTALLED ${feature.key} ready=$ready hook=agent")
    }
}

/**
 * 续话：在输入框里根据最近聊天记录和已输入文字，预测接下来的内容。
 *
 * 产品形态是"输入框内联灰字"，但不改动输入框里的任何文字：
 * - 预测结果画在文字末尾之后（hook onDraw），微信与输入法都不知情；
 * - 点一下灰字才通过 commitText 真正填入；
 * - 用户继续打字时，若打的正是建议开头就裁剪复用，否则收起重算；
 * - 任何情况下都不自动发送，也不覆盖用户已经打出来的字。
 *
 * 框架阶段不挂输入监听。接入时的落点与坑：
 * 1. 编辑框复用 ReplyHostUi 的 editor() 查找结果，避免重复扫描视图树；
 * 2. 用 TextWatcher + 防抖（停止输入数百毫秒后）再请求模型，避免逐键请求；
 * 3. 绘制走 onDraw（只画不改文本），避免踩输入法 composing 与微信的文本监听；
 * 4. 填入走 commitText（与「帮我回复」同一路径），失败才退回 append；
 * 5. 之所以不写进 EditText：输入法 composing 会重排 Editable，微信还监听文本变化
 *    来决定发送按钮与高度，灰字方案容易两头打架，气泡方案完全不碰文本。
 */
object Relay : StrategistModule {
    override val feature = StrategistFeature.RELAY

    override fun install(context: Context) {
        // 只在微信进程挂输入框监听，避免把设置页自己的输入框也接管了。
        if (context.packageName != "com.tencent.mm") {
            MoodLog.i("FEATURE_INSTALLED ${feature.key} ready=$ready pending=editor-prediction")
            return
        }
        RelayCompletion.install(context.classLoader)
        MoodLog.i("FEATURE_INSTALLED ${feature.key} ready=$ready hook=installed")
    }
}
