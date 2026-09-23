package dev.jev.wechatmood.hook

import android.app.Application
import com.highcapable.yukihookapi.hook.factory.toClassOrNull
import com.highcapable.yukihookapi.hook.factory.method
import dev.jev.wechatmood.core.MoodLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 探索模式。
 *
 * ## 为什么需要它
 * 微信的业务逻辑层是混淆的，类名形如 `x0`、`com.tencent.mm.storage.t4`，
 * 每个版本都可能变。所以「消息对象到底在哪个类上」这件事**没法靠读代码得出**，
 * 只能运行时去问微信自己。
 *
 * 这个类的做法是：挂在微信**公开稳定**的界面类上（聊天界面 Fragment），
 * 在它创建之后把内部字段的类型全部打印出来。混淆改得掉名字，改不掉结构——
 * 聊天界面必然持有一个消息列表（`List`），我们顺着那个字段就能找到消息对象。
 *
 * ## 怎么用
 * 1. 设置页打开「探索模式」
 * 2. 重启微信，进任意一个聊天窗口
 * 3. 设置页导出日志，喂回给开发侧（我）来写定位规则
 *
 * 只读、不改任何东西。不开这个开关时这段代码完全不执行。
 */
object MessageExplorer {

    /** 微信公开稳定的界面类。WeKit 的 APK 里也引用这些名字，说明它们不混淆。 */
    private val STABLE_ANCHORS = listOf(
        "com.tencent.mm.ui.chatting.ChattingUI",
        "com.tencent.mm.ui.chatting.ChattingUIFragment",
        "com.tencent.mm.pluginsdk.ui.chat.ChatFooter",
        "com.tencent.mm.ui.LauncherUI",
        "com.tencent.mm.ui.conversation.ConversationListView",
    )

    fun run(app: Application) {
        // 延后一点，等微信自己的类加载器把大部分类都加载完
        Thread {
            runCatching {
                Thread.sleep(4000)
                reportAnchors()
            }.onFailure { MoodLog.e("探索模式执行失败", it) }
        }.start()
    }

    private fun reportAnchors() {
        val sb = StringBuilder()
        sb.appendLine("微信版本探测：")
        sb.appendLine("  " + describeWeChatVersion())
        sb.appendLine()

        for (name in STABLE_ANCHORS) {
            val clazz = name.toClassOrNull()
            if (clazz == null) {
                sb.appendLine("[缺失] $name —— 这个版本里找不到该类")
                sb.appendLine()
                continue
            }
            sb.appendLine("[命中] $name")
            sb.appendLine("  父类: ${clazz.superclass?.name}")
            sb.appendLine("  字段 (${clazz.declaredFields.size} 个):")
            for (f in sortedFields(clazz.declaredFields)) {
                sb.appendLine("    ${visualize(f)}")
            }
            sb.appendLine("  方法里带 chat/message 字样的:")
            for (m in methodsOfInterest(clazz)) {
                sb.appendLine("    ${signature(m)}")
            }
            sb.appendLine()
        }

        sb.appendLine("混淆区候选：在 com.tencent.mm.storage 下按方法特征找消息类")
        sb.appendLine(probeStoragePackage())

        MoodLog.dump("探索报告", sb.toString())
        MoodLog.i("探索报告已生成，去设置页导出日志")
    }

    /** 按字段类型排序：集合和字符串优先，它们是消息容器的可能性最大。 */
    private fun sortedFields(fields: Array<Field>): List<Field> =
        fields.filter { !Modifier.isStatic(it.modifiers) }
            .sortedBy { f ->
                when {
                    List::class.java.isAssignableFrom(f.type) -> 0
                    Map::class.java.isAssignableFrom(f.type) -> 1
                    CharSequence::class.java.isAssignableFrom(f.type) -> 2
                    else -> 3
                }
            }

    private fun visualize(f: Field): String {
        val type = f.type.name
        // 混淆类名很短，标出来提醒自己「这里不可硬编码」
        val flag = if (isObfuscatedName(type)) "  <-- 混淆" else ""
        return "${Modifier.toString(f.modifiers)} ${f.name}: $type$flag"
    }

    /** 混淆类名的特征：包名后的简单名为 1-3 个字符，或纯小写字母加数字。 */
    private fun isObfuscatedName(fullName: String): Boolean {
        val simple = fullName.substringAfterLast('.').substringBefore('$')
        if (simple.length > 3) return false
        return simple.all { it.isLetterOrDigit() }
    }

    private fun methodsOfInterest(clazz: Class<*>): List<Method> =
        clazz.declaredMethods
            .filter { m ->
                val n = m.name.lowercase()
                n.contains("chat") || n.contains("message") || n.contains("msg") || n.contains("send")
            }
            .take(40)

    private fun signature(m: Method): String {
        val params = m.parameterTypes.joinToString(", ") { it.simpleName }
        return "${m.returnType.simpleName} ${m.name}($params)"
    }

    /**
     * 探 `com.tencent.mm.storage` 包。
     *
     * 这里只能靠名字特征猜——真正的定位得靠 DexKit 的方法特征搜索。
     * 这一步的价值是让我们看到**这个版本里消息类大概长什么样**。
     */
    private fun probeStoragePackage(): String {
        val sb = StringBuilder()
        val candidates = listOf(
            "com.tencent.mm.storage.t4",
            "com.tencent.mm.storage.u4",
            "com.tencent.mm.storage.v4",
        )
        for (c in candidates) {
            val clazz = c.toClassOrNull() ?: continue
            sb.appendLine("  [存在] $c")
            for (m in clazz.declaredMethods.take(30)) {
                // 消息内容读取器通常无参、返回 String
                if (m.parameterCount == 0 && m.returnType == String::class.java) {
                    sb.appendLine("      ${signature(m)}   <-- 可能是内容读取器")
                }
            }
        }
        if (sb.isEmpty()) {
            sb.appendLine("  常见的几个候选名都不存在——这个版本用了别的混淆名，")
            sb.appendLine("  需要用 DexKit 按特征搜（见下一步）。")
        }
        return sb.toString()
    }

    private fun describeWeChatVersion(): String = runCatching {
        val clazz = "com.tencent.mm.boot.BuildConfig".toClassOrNull()
        clazz?.declaredFields
            ?.filter { it.name.contains("VERSION", ignoreCase = true) }
            ?.joinToString(", ") { f ->
                runCatching { "${f.name}=${f.get(null)}" }.getOrDefault(f.name)
            }
            .orEmpty()
            .ifBlank { "拿不到 BuildConfig，看日志其它行" }
    }.getOrDefault("探测失败")
}
