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
    /** 原始 XML 上限：转发里夹带代码或超大卡片时，超过这个长度只处理前面的部分。 */
    const val MAX_RAW_CHARS = 512 * 1024
    /** datatype=17 是嵌套的聊天记录，内层完整 XML 在 <recordxml> 里。 */
    private const val DATATYPE_CHAT_RECORD = 17
    private const val MAX_DEPTH = 3

    data class Item(val type: Int, val sender: String, val text: String, val time: String, val nested: Boolean = false)
    data class Message(val title: String, val chatroom: Boolean, val items: List<Item>, val omitted: Int, val nested: Int,
        val truncatedRaw: Boolean = false) {
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
        // 只解析外层结构，媒体 URL、头像、base64 这些字段一律不取。
        val truncatedRaw = raw.length > MAX_RAW_CHARS
        val safe = if (truncatedRaw) raw.substring(0, MAX_RAW_CHARS) else raw
        val body = recordInfo(safe) ?: return null
        val items = mutableListOf<Item>()
        var omitted = 0
        var nested = 0
        var budget = MAX_TOTAL_CHARS
        collect(body, depth = 1, out = items, budget = { budget }, spend = { budget -= it },
            omitted = { omitted++ }, nested = { nested++ })
        if (items.isEmpty()) return null
        return Message(tag(body, "title").decode(), tag(body, "isChatRoom") == "1", items, omitted, nested, truncatedRaw)
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
        // 不能先截 <datalist>：嵌套记录里的 <datalist> 会让非贪婪匹配提前闭合。
        val pairs = dataItems(body)
        if (pairs.isEmpty()) {
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
        pairs.forEach { (attributes, itemBody) ->
            if (out.size >= MAX_ITEMS || budget() <= 0) { omitted(); return@forEach }
            val type = Regex("""datatype\s*=\s*"(\d+)"""").find(attributes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var text = tag(itemBody, "datadesc").decode()
            val sender = tag(itemBody, "sourcename").decode()
            val time = tag(itemBody, "sourcetime").decode()
            // 公众号、视频、语音、表情这些类型，微信通常已经写成 [视频]/标题；为空时补个占位。
            if (text.isBlank()) {
                text = tag(itemBody, "datatitle").decode().ifBlank { "[类型$type]" }
            }
            // 嵌套：datatype=17，内层完整 XML 在 <recordxml>；老格式可能塞在 datadesc 里。
            val nestedXml = block(itemBody, "recordxml")
            if (type == DATATYPE_CHAT_RECORD || nestedXml.contains("<recordinfo") || text.contains("<recordinfo")) {
                nested()
                if (depth < MAX_DEPTH) {
                    val inner = when {
                        nestedXml.contains("<recordinfo") -> nestedXml
                        text.contains("<recordinfo") -> text
                        else -> ""
                    }
                    if (inner.isNotEmpty()) {
                        // recordxml 本身就是 <recordinfo>…</recordinfo>，直接递归；
                        // 再用正则截 recordinfo 会被更深的同名标签提前闭合。
                        collect(inner, depth + 1, out, budget, spend, omitted, nested)
                        return@forEach
                    }
                    // 只有平铺文本时，退化成「昵称: 内容」行，不再递归。
                    text = tag(itemBody, "datadesc").decode()
                } else {
                    text = "[聊天记录]"
                }
            }
            val cut = text.take(MAX_ITEM_CHARS)
            spend(cut.length + sender.length)
            out += Item(type, sender, cut, time, depth > 1)
        }
    }

    /**
     * 逐条切 `<dataitem>`，按标签深度配对。
     * 嵌套记录的内层也带 `<dataitem>`，非贪婪正则会在第一个 `</dataitem>` 提前截断。
     */
    private fun dataItems(src: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < src.length) {
            val start = src.indexOf("<dataitem", index)
            if (start < 0) break
            val attrEnd = src.indexOf('>', start)
            if (attrEnd < 0) break
            val attributes = src.substring(start + "<dataitem".length, attrEnd)
            var depth = 1
            var cursor = attrEnd + 1
            var end = -1
            while (cursor < src.length) {
                val nextOpen = src.indexOf("<dataitem", cursor)
                val nextClose = src.indexOf("</dataitem>", cursor)
                if (nextClose < 0) break
                if (nextOpen in 0 until nextClose) {
                    depth++
                    cursor = nextOpen + 1
                    continue
                }
                depth--
                if (depth == 0) { end = nextClose; break }
                cursor = nextClose + 1
            }
            if (end < 0) break
            out += attributes to src.substring(attrEnd + 1, end)
            index = end + "</dataitem>".length
        }
        return out
    }

    private fun recordInfo(raw: String): String? {
        val cdata = Regex("""<recorditem>\s*<!\[CDATA\[(.*?)]]></recorditem>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)
        if (cdata != null) return cdata
        // 少数包不带 CDATA，直接取 recordinfo。
        return Regex("""<recordinfo>.*</recordinfo>""", RegexOption.DOT_MATCHES_ALL).find(raw)?.value
    }

    /** 同名标签可嵌套（recordxml 里还有 recordxml），必须按深度配对，不能用非贪婪正则。 */
    private fun block(src: String, name: String): String {
        val open = "<$name"
        val close = "</$name>"
        val start = src.indexOf(open)
        if (start < 0) return ""
        val head = src.indexOf('>', start)
        if (head < 0) return ""
        var depth = 1
        var cursor = head + 1
        while (cursor < src.length) {
            val nextOpen = src.indexOf(open, cursor)
            val nextClose = src.indexOf(close, cursor)
            if (nextClose < 0) return ""
            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen + 1
                continue
            }
            depth--
            if (depth == 0) return src.substring(head + 1, nextClose)
            cursor = nextClose + 1
        }
        return ""
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

    private val ENTITY = Regex("""&#(?:[xX]([0-9a-fA-F]+)|(\d+));""")
}
