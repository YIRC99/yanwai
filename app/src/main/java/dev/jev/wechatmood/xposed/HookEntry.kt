package dev.jev.wechatmood.xposed

import android.app.Application
import android.content.Context
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.hook.MessageExplorer
import dev.jev.wechatmood.hook.MessageSniffer

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {
    private var installed = false
    override fun onInit() {}
    override fun onHook() {
        YukiHookAPI.encase {
            XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (context.packageName != "com.tencent.mm" || installed ||
                            Application.getProcessName() != "com.tencent.mm") return
                        installed = true
                        runCatching {
                            MoodLog.init(context)
                            ModulePrefs.init(context)
                            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
                            ModulePrefs.report("模块已加载，等待打开聊天")
                            if (ModulePrefs.exploreMode) MessageExplorer.run(param.thisObject as Application)
                            MessageSniffer.install(context)
                        }.onFailure { MoodLog.e("模块初始化失败：${it.javaClass.simpleName}") }
                    }
                })
        }
    }
}
