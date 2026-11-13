package dev.jev.wechatmood.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.hook.MessageSniffer
import dev.jev.wechatmood.hook.ReplyDatabaseHistory

/** Direct package entry, independent of the optional initZygote callback. */
class HookEntry : IXposedHookLoadPackage {
    private var installed = false
    private var failedAnnounced = false

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm" || param.processName != "com.tencent.mm") return
        MoodLog.frameworkSink = { XposedBridge.log(it) }
        XposedBridge.log("WeChatMood ${BuildConfig.VERSION_NAME}: entered WeChat main process")
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { initialize(p.args[0] as Context) }
            })
        // Covers module loaders that attach after Application.attach has already run.
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val activity = p.args.firstOrNull() as? Activity ?: return
                if (activity.packageName != "com.tencent.mm") return
                initialize(activity.application)
                if (!installed) {
                    // 每次切页都弹会烦人，失败只提示一次，细节进日志。
                    if (!failedAnnounced) {
                        failedAnnounced = true
                        Toast.makeText(activity, "情绪助手初始化失败，请查看模块日志", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                runCatching { MessageSniffer.resume(activity) }
                    .onFailure { MoodLog.e("HOST_RESUME_FAILED", it) }
            }
        })
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                (p.args.firstOrNull() as? Activity)?.let { MessageSniffer.pause(it) }
            }
        })
    }

    @Synchronized private fun initialize(context: Context) {
        if (installed) return
        runCatching {
            MoodLog.init(context)
            ModulePrefs.init(context)
            MessageSniffer.install(context)
            installed = true
            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
            // 环境采集、日志上报、WCDB 反射扫描都不参与首帧，挪出冷启动主线程。
            Thread({
                runCatching {
                    MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(context)}")
                    ModulePrefs.report("模块 ${BuildConfig.VERSION_NAME} 已加载，等待打开聊天")
                    ReplyDatabaseHistory.install(context.classLoader)
                }.onFailure { MoodLog.e("HOOK_INIT_BACKGROUND_FAILED", it) }
            }, "yanwai-init-bg").start()
        }.onFailure {
            XposedBridge.log("WeChatMood initialization failed: ${it.javaClass.name}")
            MoodLog.e("HOOK_INIT_FAILED", it)
        }
    }
}
