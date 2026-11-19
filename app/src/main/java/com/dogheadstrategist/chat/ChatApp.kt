package com.dogheadstrategist.chat

import android.app.Application
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.ModulePrefs

/**
 * app 主进程入口。
 *
 * 以前 app 进程没有任何地方调 [ModulePrefs.init]（只有微信进程的 HookEntry 调过），
 * 于是 [ModulePrefs] 里的 context 一直是 null，readSettings() 里 requireNotNull(context)
 * 直接抛异常，配置快照永远读不进来。表现就是：
 * - 保存了地址和 API Key，点「检查连接」却提示「请先保存地址、API Key 和模型名」；
 * - 思考模式片段填了内容，界面上仍显示「不注入」（因为 ThinkingPrefs 也读同一份快照）。
 *
 * 放在 Application 而不是 MainActivity，是因为从通知点进来的 OpenChatReceiver、
 * 桌面小组件等入口同样需要读到配置。
 */
class ChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 日志要先开：ModulePrefs 的读取失败、WarReviewStore 的损坏都要留痕，
        // 否则出问题时 app 侧一条日志都没有，只能靠猜。
        runCatching { MoodLog.init(this) }
        runCatching { ModulePrefs.init(this) }
    }
}
