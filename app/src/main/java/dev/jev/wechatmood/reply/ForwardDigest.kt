package dev.jev.wechatmood.reply


/**
 * 合并转发消息：微信把 type=49 / appmsg type=19 的内容放在
 * `<recorditem><![CDATA[ <recordinfo>… ]]></recorditem>` 里。
 *
 * 结构化字段是 `<datalist>` 下的 `<dataitem>`，比 `<desc>` 那串
 * 「昵称: 内容」更可靠，所以优先按 dataitem 取；拿不到时退回 desc。
 * 嵌套的聊天记录只展开一层，再深的直接标注，避免一条消息把上下文撑爆。
 */
object ForwardDigest {
    const val MAX_ITEMS = 80
    const val MAX_ITEM_CHARS = 200
    const val MAX_TOTAL_CHARS = 12000
    private const val MAX_DEPTH = 2

    data class Item(val type: Int, val sender: String, val text: String, val time: String, val nested: Boolean = false)
    data class Message(val title: String, val chatroom: Boolean, val items: List<Item>, val omitted: Int, val nested: Int) {
        /** 给模型的纯文本：不带 XML、不带未展开的条数。 */
        fun transcript(): String = buildString {
            if (title.isNotBlank()) append("聊天记录：").append(title).append('\n')
            items.forEachIndexed { index, item ->
                append(index + 1).append(". ")
                if (item.sender.isNotBlank()) append(item.sender).append(": ")
                append(item.text).append('\n')
            }
            if (omitted > 0) append("（另有 ").append(omitted).append(" 条未展开）\n")
        }.trim()
    }

    fun isForward(raw: String): Boolean = raw.contains("<recorditem>") && raw.contains("<recordinfo")

    fun parse(raw: String): Message? {
        val body = recordInfo(raw) ?: return null
        val items = mutableListOf<Item>()
        var omitted = 0
        var nested = 0
        var budget = MAX_TOTAL_CHARS
        collect(body, depth = 1, out = items, budget = { budget }, spend = { budget -= it },
            omitted = { omitted++ }, nested = { nested++ })
        if (items.isEmpty()) return null
        return Message(tag(body, "title").decode(), tag(body, "isChatRoom") == "1", items, omitted, nested)
    }

    private fun collect(
        body: String,
        depth: Int,
        out: MutableList<Item>,
        budget: () -> Int,
        spend: (Int) -> Unit,
        omitted: () -> Unit,
        nested: () -> Unit,
    ) {
        val list = tag(body, "datalist")
        if (list.isBlank()) {
            // 老版本可能没有 datalist，只能退回 desc 的「昵称: 内容」行。
            tag(body, "desc").decode().split('\n').filter { it.isNotBlank() }.forEach { line ->
                if (out.size >= MAX_ITEMS || budget() <= 0) { omitted(); return@forEach }
                val sender = line.substringBefore(':', "").trim()
                val text = line.substringAfter(':', line).trim().take(MAX_ITEM_CHARS)
                spend(text.length + sender.length)
                out += Item(1, sender, text, "")
            }
            return
        }
        DATA_ITEM.findAll(list).forEach { match ->
            if (out.size >= MAX_ITEMS || budget() <= 0) { omitted(); return@forEach }
            val attributes = match.groupValues[1]
            val itemBody = match.groupValues[2]
            val type = Regex("""datatype\s*=\s*"(\d+)"""").find(attributes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var text = tag(itemBody, "datadesc").decode()
            val sender = tag(itemBody, "sourcename").decode()
            val time = tag(itemBody, "sourcetime").decode()
            // 嵌套：内层记录会被转义进 datadesc，能解就展开一层。
            if (text.contains("<recordinfo")) {
                nested()
                if (depth < MAX_DEPTH) {
                    collect(text, depth + 1, out, budget, spend, omitted, nested)
                    return@forEach
                }
                text = "[聊天记录]"
            }
            val cut = text.take(MAX_ITEM_CHARS)
            spend(cut.length + sender.length)
            out += Item(type, sender, cut, time, depth > 1)
        }
    }

    private fun recordInfo(raw: String): String? {
        val cdata = Regex("""<recorditem>\s*<!\[CDATA\[(.*?)]]></recorditem>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)
        if (cdata != null) return cdata
        // 少数包不带 CDATA，直接取 recordinfo。
        return Regex("""<recordinfo>.*</recordinfo>""", RegexOption.DOT_MATCHES_ALL).find(raw)?.value
    }

    /** 微信的标签常带属性（如 `<datalist count="3">`），匹配时要允许。 */
    private fun tag(body: String, name: String): String =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1).orEmpty()

    /** 微信把换行、空格写成 &#x0A; / &#x20;，还可能二次转义。 */
    internal fun String.decode(): String {
        if (isEmpty()) return this
        var value = this
        repeat(2) {
            value = value
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            value = ENTITY.replace(value) { match ->
                val hex = match.groupValues[1].takeIf { it.isNotEmpty() }
                val dec = match.groupValues[2].takeIf { it.isNotEmpty() }
                val code = hex?.toIntOrNull(16) ?: dec?.toIntOrNull() ?: return@replace match.value
                runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
            }
        }
        return value.trim()
    }

    private val DATA_ITEM = Regex("""<dataitem\b([^>]*)>(.*?)</dataitem>""", RegexOption.DOT_MATCHES_ALL)
    private val ENTITY = Regex("""&#(?:[xX]([0-9a-fA-F]+)|(\d+));""")
}
