package dev.jev.wechatmood.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.core.MoodLog
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Reuse the host's normal row renderer after ordering, without changing its UIC registry. */
internal object HostSettingsEntry {
    private const val SETTINGS = "com.tencent.mm.plugin.setting.ui.setting_new.settings."
    private const val GROUP_TEXT_ID = -0x59414e
    private data class Entry(val showGroup: Boolean)
    private val entries = Collections.synchronizedMap(WeakHashMap<Any, Entry>())
    private val populated = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    @Volatile private var state = "正在连接微信设置列表"
    private var installed = false

    class Points(val personal: Class<*>, val main: Class<*>, val base: Class<*>,
        val key: Method, val title: Method, val group: Method, val click: Method, val build: Method)

    /** Uses the existing background APK scan. No obfuscated class or method name is pinned. */
    fun locate(bridge: DexKitBridge, loader: ClassLoader): Points? = runCatching {
        val personal = loader.loadClass(SETTINGS + "SettingGroupPersonalInfo")
        val clickArgs = arrayOf(Context::class.java, View::class.java, Integer.TYPE)
        val base = generateSequence(personal.superclass) { it.superclass }.first { type ->
            type.declaredMethods.any { Modifier.isAbstract(it.modifiers) && it.parameterTypes.contentEquals(clickArgs) }
        }
        val build = bridge.findMethod {
            matcher { paramTypes(base.name, "java.util.List") }
        }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
            .singleOrNull { it.returnType.constructors.any { c ->
                c.parameterTypes.contentEquals(arrayOf(Any::class.java, Any::class.java))
            } } ?: return@runCatching null
        val key = bridge.findMethod {
            matcher { declaredClass = personal.name; returnType = "java.lang.String"
                paramCount = 0; usingStrings("SettingGroup_Main_PersonalInfo") }
        }.single().getMethodInstance(loader)
        val title = bridge.findMethod {
            matcher { declaredClass = base.name; returnType = "java.lang.String"
                paramCount = 0; usingStrings("getString(...)") }
        }.single().getMethodInstance(loader)
        val group = personal.declaredMethods.single { it.parameterCount == 0 && it.returnType == Integer::class.java }
        val click = personal.methods.single { it.parameterTypes.contentEquals(clickArgs) }
        Points(personal, loader.loadClass(SETTINGS + "SettingGroupMain"), base, key, title, group, click, build)
    }.onFailure { MoodLog.e("SETTINGS_ENTRY_LOCATE_FAILED", it) }.getOrNull()

    @Synchronized fun install(candidates: List<Points>) {
        if (installed) return
        val points = candidates.distinctBy { it.build }.singleOrNull()
        if (points == null) {
            state = "当前微信设置列表未适配，可从桌面打开言外"
            MoodLog.w("SETTINGS_ENTRY_UNSUPPORTED 未找到唯一的原生设置列表")
            return
        }
        val hooks = mutableListOf<XC_MethodHook.Unhook>()
        runCatching {
            fun override(method: Method, value: (Entry, XC_MethodHook.MethodHookParam) -> Any?) {
                hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val entry = entries[param.thisObject] ?: return
                        param.result = value(entry, param)
                    }
                })
            }
            override(points.key) { _, _ -> SettingsEntryPlacement.KEY }
            override(points.title) { _, _ -> "言外设置" }
            override(points.group) { entry, _ -> if (entry.showGroup) GROUP_TEXT_ID else null }
            override(points.click) { _, param -> open(param.args[0] as Context); null }
            hooks += XposedBridge.hookMethod(points.base.getDeclaredMethod("clone"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val entry = entries[param.thisObject] ?: return
                    param.result?.let { entries[it] = entry }
                }
            })
            // Android getString/setText(int) both go through getText. A private negative ID
            // cannot alias a packaged host resource, and is only emitted by our marked row.
            hooks += XposedBridge.hookMethod(Resources::class.java.getMethod("getText", Integer.TYPE), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == GROUP_TEXT_ID) param.result = "插件"
                }
            })
            val constructor = points.personal.constructors.single { it.parameterCount == 1 &&
                it.parameterTypes[0].name == "androidx.appcompat.app.AppCompatActivity" }
            val getActivity = points.personal.getMethod("getActivity")
            val baseGroup = points.base.getMethod(points.group.name)
            // Xposed reverses the order for after callbacks: highest priority runs last.
            hooks += XposedBridge.hookMethod(points.build, object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.args[0] ?: return
                    if (root.javaClass != points.main || param.hasThrowable()) return
                    runCatching {
                        val pair = param.result ?: return@runCatching
                        val fields = pair.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                            .onEach { it.isAccessible = true }
                        val rows = fields.map { it.get(pair) }.filterIsInstance<List<*>>().single()
                        check(fields.any { it.get(pair) === root }) { "设置列表根节点不匹配" }
                        check(rows.all { it != null && points.base.isInstance(it) }) { "设置列表类型不匹配" }
                        val host = rows.firstOrNull { it?.javaClass == points.personal } ?: return@runCatching
                        val activity = getActivity.invoke(host) as Activity
                        if (activity.isFinishing || activity.isDestroyed) return@runCatching
                        val descriptions = rows.map { row ->
                            requireNotNull(row)
                            val groupId = baseGroup.invoke(row) as? Int
                            val group = groupId?.let { runCatching { activity.getString(it) }.getOrNull() }
                            SettingsEntryPlacement.Row(
                                if (entries.containsKey(row)) SettingsEntryPlacement.KEY else row.javaClass.simpleName,
                                group in setOf("插件", "Plugins", "Plug-ins", "Extensions"), groupId != null)
                        }
                        val plan = SettingsEntryPlacement.plan(descriptions) ?: return@runCatching
                        val item = constructor.newInstance(activity)
                        entries[item] = Entry(plan.showGroup)
                        val updated = plan.keepIndices.map { rows[it] }.toMutableList()
                        updated.add(plan.index, item)
                        // Replace the pair, not the host's list. Its original items keep their identity.
                        val newPair = pair.javaClass.getConstructor(Any::class.java, Any::class.java).newInstance(root, updated)
                        param.result = newPair
                        if (populated.put(activity, true) != true) MoodLog.i("SETTINGS_ENTRY_INSERTED 原生插件列表入口已加入")
                    }.onFailure {
                        state = "微信设置入口未加入，可从桌面打开言外"
                        MoodLog.e("SETTINGS_ENTRY_INSERT_FAILED", it)
                    }
                }
            })
            installed = true
            state = "微信设置入口已连接，重新进入设置页后显示"
            MoodLog.i("SETTINGS_ENTRY_HOOK_READY 原生设置列表已连接")
        }.onFailure {
            hooks.forEach { hook -> runCatching { hook.unhook() } }
            state = "微信设置入口连接失败，可从桌面打开言外"
            MoodLog.e("SETTINGS_ENTRY_INSTALL_FAILED", it)
        }
    }

    fun status(activity: Activity): String = if (populated[activity] == true) "言外已加入微信插件列表" else state

    fun open(context: Context) {
        runCatching {
            context.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity"))
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            MoodLog.i("SETTINGS_ACTIVITY_OPEN 请求已发送")
        }.onFailure {
            MoodLog.e("SETTINGS_ACTIVITY_OPEN_FAILED", it)
            (context as? Activity)?.let { activity -> Diagnostics.showFailure(activity, "无法从微信打开言外",
                "请从桌面打开言外。${it.javaClass.simpleName} ${it.message}") }
        }
    }
}
