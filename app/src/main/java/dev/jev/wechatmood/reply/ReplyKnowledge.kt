package dev.jev.wechatmood.reply

import android.content.Context

/** Original MIT sources are bundled, pinned and read locally, never fetched at chat time. */
object ReplyKnowledge {
    const val SOURCE_URL = "https://github.com/shengjidaguai-china/goutoujunshi"
    const val REVISION = "6db7354a4002dc7c448a9c87ffdad8132570c9d3"
    private val cached = mutableMapOf<ReplyRelationship, String>()
    @Synchronized fun load(context: Context, relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED): String {
        cached[relationship]?.let { return it }
        val module = if (context.packageName == "dev.jev.wechatmood") context else
            context.createPackageContext("dev.jev.wechatmood", Context.CONTEXT_IGNORE_SECURITY)
        val assets = module.assets
        val paths = ReplyKnowledgeCatalog.paths(relationship)
        return paths.joinToString("\n\n") { path ->
            "## 来源：$path\n" + assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.also { cached[relationship] = it }
    }
}
