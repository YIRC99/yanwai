package dev.jev.wechatmood.reply

import android.content.Context

/** Original MIT sources are bundled, pinned and read locally, never fetched at chat time. */
object ReplyKnowledge {
    const val SOURCE_URL = "https://github.com/shengjidaguai-china/goutoujunshi"
    const val REVISION = "6db7354a4002dc7c448a9c87ffdad8132570c9d3"
    @Volatile private var cached: String? = null
    @Synchronized fun load(context: Context): String {
        cached?.let { return it }
        val module = if (context.packageName == "dev.jev.wechatmood") context else
            context.createPackageContext("dev.jev.wechatmood", Context.CONTEXT_IGNORE_SECURITY)
        val assets = module.assets
        val paths = listOf("goutoujunshi/SKILL.md") + listOf("knowledge", "practical").flatMap { directory ->
            val path = "goutoujunshi/references/$directory"
            assets.list(path).orEmpty().filter { it.endsWith(".md") }.sorted().map { "$path/$it" }
        }
        check(paths.size == 44) { "回复知识库不完整，请重新安装言外" }
        return paths.joinToString("\n\n") { path ->
            "## 来源：$path\n" + assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.also { cached = it }
    }
}
