package dev.jev.wechatmood.hook

import android.content.Context
import dev.jev.wechatmood.core.MoodLog
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

/**
 * 定位聊天消息的 Adapter 类。
 *
 * 两级策略，从便宜到贵：
 *
 * **一级 · 结构探测**：微信的 `ChattingUIFragment` 是公开稳定类（WeKit 的 APK 也引用它），
 * 它的内部类里一定有一个是消息列表的 Adapter。遍历 declaredClasses 找实现了
 * `BaseAdapter` / `RecyclerView.Adapter` 的那个——不用 DexKit，毫秒级。
 *
 * **二级 · DexKit 特征搜索**：一级没命中时（微信换了组织方式），扫微信自己的 dex，
 * 按**方法签名特征**找。DexKit 在运行时读 dex，所以混淆改名不影响命中。
 * 特征选的是「有 `getView(int, View, ViewGroup)` 方法」——这是 Android 框架约定，
 * 微信改不掉，比任何类名都稳。
 */
object AdapterLocator {

    private val ANCHORS = listOf(
        "com.tencent.mm.ui.chatting.ChattingUIFragment",
        "com.tencent.mm.ui.chatting.ChattingUI",
    )

    fun locate(context: Context): Class<*>? {
        structuralProbe()?.let {
            MoodLog.i("Adapter 由结构探测命中：${it.name}")
            return it
        }
        MoodLog.w("结构探测没命中，转 DexKit 特征搜索")
        return dexKitSearch(context)
    }

    /** 一级：在稳定锚点类的内部类里找 Adapter。 */
    private fun structuralProbe(): Class<*>? {
        for (anchorName in ANCHORS) {
            val anchor = runCatching { Class.forName(anchorName) }.getOrNull() ?: continue
            anchor.declaredClasses
                .firstOrNull { !it.isInterface && !Modifier.isAbstract(it.modifiers) && isListAdapter(it) }
                ?.let { return it }
        }
        return null
    }

    private fun isListAdapter(clazz: Class<*>): Boolean {
        val bases = listOf(
            "android.widget.BaseAdapter",
            "android.widget.ArrayAdapter",
            "androidx.recyclerview.widget.RecyclerView\$Adapter",
        )
        return bases.any { name ->
            runCatching { Class.forName(name) }.getOrNull()?.isAssignableFrom(clazz) == true
        }
    }

    /**
     * 二级：DexKit 特征搜索。
     *
     * DexKit 的调用很贵（要解析整个 dex），所以：
     * - 只在微信启动时跑一次
     * - 结果拿到就立刻关掉 bridge，不留常驻内存
     * - 所有候选都打进日志，方便人工判断是不是挑错了
     */
    private fun dexKitSearch(context: Context): Class<*>? {
        val apkPath = context.applicationInfo.sourceDir
        MoodLog.i("DexKit 开始搜索，目标 APK: $apkPath")

        return runCatching {
            DexKitBridge.create(apkPath).use { bridge ->
                val candidates: List<String> = bridge.findClass {
                    matcher {
                        methods {
                            add {
                                name = "getView"
                                // 只按参数个数匹配：类型不确定时传 null 会让
                                // paramTypes 的重载产生歧义，paramCount 更直接也更安全。
                                paramCount = 3
                                returnType = "android.view.View"
                            }
                        }
                    }
                }.map { it.name }

                MoodLog.dump(
                    "DexKit Adapter 候选",
                    if (candidates.isEmpty()) "（无命中）"
                    else candidates.joinToString("\n")
                )

                candidates
                    .asSequence()
                    .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
                    .firstOrNull { isListAdapter(it) }
            }
        }.onFailure {
            MoodLog.e("DexKit 搜索失败：${it.javaClass.simpleName} ${it.message}", it)
        }.getOrNull()
    }
}
