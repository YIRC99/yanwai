package com.dogheadstrategist.chat.xposed

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
import com.dogheadstrategist.chat.BuildConfig
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.Diagnostics
import com.dogheadstrategist.chat.hook.MessageSniffer
import com.dogheadstrategist.chat.hook.ReplyDatabaseHistory
import com.dogheadstrategist.chat.feature.StrategistModules

/** Direct package entry, independent of the optional initZygote callback. */
class HookEntry : IXposedHookLoadPackage {
    private var installed = false
    private var announced = false

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
                    Toast.makeText(activity, "狗头军师初始化失败，请查看模块日志", Toast.LENGTH_LONG).show()
                    return
                }
                runCatching {
                    MessageSniffer.resume(activity)
                    com.dogheadstrategist.chat.sentry.SentryCard.attach(activity)
                    if (!announced) {
                        announced = true
                        Toast.makeText(activity, "狗头军师已加载", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { MoodLog.e("HOST_RESUME_FAILED", it) }
            }
        })
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                (p.args.firstOrNull() as? Activity)?.let {
                    MessageSniffer.pause(it)
                    runCatching { com.dogheadstrategist.chat.sentry.SentryCard.detach(it) }
                }
            }
        })
    }

    @Synchronized private fun initialize(context: Context) {
        if (installed) return
        runCatching {
            MoodLog.init(context)
            MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(context)}")
            ModulePrefs.init(context)
            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
            ModulePrefs.report("模块 ${BuildConfig.VERSION_NAME} 已加载，等待打开聊天")
            ReplyDatabaseHistory.install(context.classLoader)
            MessageSniffer.install(context)
            StrategistModules.install(context)
            installed = true
        }.onFailure {
            XposedBridge.log("WeChatMood initialization failed: ${it.javaClass.name}")
            MoodLog.e("HOOK_INIT_FAILED", it)
        }
    }
}
