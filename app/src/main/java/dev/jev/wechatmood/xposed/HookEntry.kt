package dev.jev.wechatmood.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.hook.MessageSniffer
import dev.jev.wechatmood.hook.ReplyDatabaseHistory

/**
 * 模块入口：微信主进程加载后直接初始化。
 *
 * 2.9.114/2.9.115 曾把初始化推到 resume 后、聊天页后，用来避开和 WeKit 抢首帧。
 * 后来抓到的主线程堆栈显示白屏是 WeKit 自己在 Application.onCreate 的 hook 里
 * 同步等待 native payload 加载（CompletableFuture.get），言外不在那条链上；
 * 所以这里回到最初的写法，不再为第三方模块的启动问题做额外延迟。
 */
class HookEntry : IXposedHookLoadPackage {
    private var installed = false
    private var failedAnnounced = false

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm" || param.processName != "com.tencent.mm") return
        MoodLog.frameworkSink = { XposedBridge.log(it) }
        XposedBridge.log("WeChatMood ${BuildConfig.VERSION_NAME}: entered WeChat main process")
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    initialize(p.args[0] as Context)
                }
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

    /**
     * App 侧的「清空所有联系人的关系档案」：档案在微信进程里，只能由模块自己删。
     * 只认言外 App 的 UID，别的应用发同样广播不会生效。
     */
    private fun registerClearReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != RelationFactBridge.CLEAR_ALL_ACTION) return
                // Android 9–13 上 sentFromUid 固定是 -1，校验必然失败；
                // 这种系统版本不走广播，改由落盘的清空请求在微信进程里执行并回执。
                if (Build.VERSION.SDK_INT < 34) {
                    MoodLog.i("RELATION_FACT_CLEAR_SENDER_UNKNOWN sdk=" + Build.VERSION.SDK_INT)
                    return
                }
                val from = sentFromUid
                val appUid = runCatching {
                    c.packageManager.getPackageUid("dev.jev.wechatmood", 0)
                }.getOrDefault(-1)
                if (appUid <= 0 || from != appUid) {
                    MoodLog.w("RELATION_FACT_CLEAR_REJECT from=$from")
                    return
                }
                RelationFactBridge.clearAllLocal(c)
            }
        }
        runCatching {
            val filter = IntentFilter(RelationFactBridge.CLEAR_ALL_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { MoodLog.w("RELATION_FACT_CLEAR_RECEIVER_FAIL " + it.javaClass.simpleName) }
    }

    @Synchronized private fun initialize(context: Context) {
        if (installed) return
        runCatching {
            MoodLog.init(context)
            ModulePrefs.init(context)
            MessageSniffer.install(context)
            registerClearReceiver(context)
            installed = true
            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
            // 环境采集、日志上报、WCDB 反射扫描都不参与首帧，放后台线程。
            Thread({
                runCatching {
                    MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(context)}")
                    ModulePrefs.report("模块 ${BuildConfig.VERSION_NAME} 已加载，等待打开聊天")
                    ReplyDatabaseHistory.install(context.classLoader)
                    // 旧版档案还在 App 的库里：搬到微信库一次，搬完让 App 删掉旧副本。
                    RelationFactBridge.migrateLegacy(context)
                    // 微信没运行时记下的清空请求，这里补执行并回执。
                    RelationFactBridge.runPendingClear(context)
                }.onFailure { MoodLog.e("HOOK_INIT_BACKGROUND_FAILED", it) }
            }, "yanwai-init-bg").start()
        }.onFailure {
            XposedBridge.log("WeChatMood initialization failed: ${it.javaClass.name}")
            MoodLog.e("HOOK_INIT_FAILED", it)
        }
    }
}
