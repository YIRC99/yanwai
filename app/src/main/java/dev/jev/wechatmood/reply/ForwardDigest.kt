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
    const val MAX_ITEMS = 300
    const val MAX_ITEM_CHARS = 150
    const val MAX_TOTAL_CHARS = 40000
    /** 原始 XML 上限：转发里夹带代码或超大卡片时，超过这个长度只处理前面的部分。 */
    const val MAX_RAW_CHARS = 512 * 1024
    /** datatype=17 是嵌套的聊天记录，内层完整 XML 在 <recordxml> 里。 */
    private const val DATATYPE_CHAT_RECORD = 17
    private const val MAX_DEPTH = 6
    private const val FILLER_MIN_LENGTH = 120
    private const val FILLER_MAX_PERIOD = 8
    private const val FILLER_PREFIX_MIN = 32
    /** 任意 2 到 8 字符的片段连续重复 6 次以上，当作填充。 */
    private val REPEAT = Regex("(.{2,8}?)\\1{5,}", setOf(RegexOption.DOT_MATCHES_ALL))
    private val PLACEHOLDER = Regex("^\\[[^]]{1,6}]$")
    private val DESC = Regex("<datadesc(?:\\s[^>]*)?>(.*?)</datadesc>", RegexOption.DOT_MATCHES_ALL)
    private val NOISE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\u2028\\u2029\\uFEFF]")

    data class Item(val type: Int, val sender: String, val text: String, val time: String, val nested: Boolean = false)
    data class Message(val title: String, val chatroom: Boolean, val items: List<Item>, val omitted: Int, val nested: Int,
        val truncatedRaw: Boolean = false) {
        /** 给模型的纯文本：不带 XML、不带未展开的条数。 */
        fun transcript(): String = buildString {
            if (title.isNotBlank()) append("聊天记录：").append(title).append('\n')
            items.forEachIndexed { index, item ->
                append(index + 1).append(". ")
                if (item.sender.isNotBlank()) append(item.sender).append(": ")
                append(item.text.cleanNoise().stripFiller()).append('\n')
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
        // 结构解析之外再兜底扫一遍所有 datadesc：四层嵌套时按 dataitem 配对容易漏，
        // 漏掉的内容不该因为层级对不上就消失。
        val merged = compress(supplement(body, items))
        dev.jev.wechatmood.core.MoodLog.i(
            "FORWARD_DIGEST parsed=${items.size} merged=${merged.size} nested=$nested omitted=$omitted raw=${safe.length}")
        return Message(tag(body, "title").decode().cleanNoise().stripFiller(), tag(body, "isChatRoom") == "1",
            merged, omitted, nested, truncatedRaw)
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
                val text = line.substringAfter(':', line).trim()
                    .cleanNoise().trimFillerPrefix().stripFiller().take(MAX_ITEM_CHARS)
                if (text.isBlank() || text.isFiller()) { omitted(); return@forEach }
                spend(text.length + sender.length)
                out += Item(1, sender, text, "")
            }
            return
        }
        pairs.forEach { (attributes, itemBody) ->
            if (out.size >= MAX_ITEMS || budget() <= 0) { omitted(); return@forEach }
            val type = Regex("""datatype\s*=\s*"(\d+)"""").find(attributes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var text = tag(itemBody, "datadesc").decode()
            val sender = tag(itemBody, "sourcename").decode().cleanNoise().stripFiller()
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
            // 整屏重复字符（群友常发 uinull / 零宽填充）没有信息量，直接跳过，别占摘要开头。
            val cut = text.cleanNoise().trimFillerPrefix().stripFiller().take(MAX_ITEM_CHARS)
            if (cut.isBlank() || cut.isFiller()) {
                omitted()
                return@forEach
            }
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

    /**
     * 压缩：同一人连续发言合并成一条，纯语气和纯表情丢掉。
     * 长转发里这类内容占一半以上，压完输入更短，模型也更容易抓重点。
     */
    private fun compress(items: List<Item>): List<Item> {
        val out = mutableListOf<Item>()
        for (item in items) {
            if (item.text.isBlank() || item.text.isFiller() || item.text.isChatter()) continue
            val last = out.lastOrNull()
            val sameSender = last != null && (item.sender.isBlank() || last.sender.isBlank() || last.sender == item.sender)
            if (last != null && sameSender && last.text.length + item.text.length < MAX_ITEM_CHARS) {
                out[out.lastIndex] = last.copy(text = last.text + "；" + item.text)
            } else {
                out += item
            }
        }
        return out
    }

    /** 只有语气、表情或标点的短句，没有信息量。 */
    internal fun String.isChatter(): Boolean {
        val core = replace(Regex("[\\s\\p{Punct}，。！？、~～…：；]"), "")
        if (core.isEmpty()) return true
        if (core.length > 4) return false
        return core.all { it in "哈呵嗯哦喔噢行好对是的在啊呀吧了呢收到来了去" }
    }

    /** 用全局 datadesc 补齐结构解析漏掉的条目，只按文本去重，不重复计算字数。 */
    private fun supplement(raw: String, parsed: List<Item>): List<Item> {
        if (parsed.size >= MAX_ITEMS) return parsed
        val seen = parsed.mapTo(HashSet()) { it.text }
        val extra = mutableListOf<Item>()
        var room = MAX_ITEMS - parsed.size
        // 全局扫描：结构解析在四层嵌套时只切出外层几条，内层的 datadesc 全靠这里补。
        for (match in DESC.findAll(raw)) {
            if (room <= 0) break
            val text = match.groupValues[1].decode().cleanNoise().trimFillerPrefix().stripFiller().take(MAX_ITEM_CHARS)
            // 占位符（[聊天记录]、[图片]）不补，否则数量会虚增。
            if (text.isBlank() || text.isFiller() || text.matches(PLACEHOLDER) || !seen.add(text)) continue
            extra += Item(0, "", text, "", nested = true)
            room--
        }
        return if (extra.isEmpty()) parsed else parsed + extra
    }

    /** 去掉不可打印字符与零宽填充，避免整屏噪声进摘要。 */
    internal fun String.cleanNoise(): String = NOISE.replace(this, "").trim()

    /**
     * 去掉文本里任意位置的固定周期重复段（nullnull…、uinull…）。
     * 填充不一定在开头，截图里就是「[聊天记录]」夹在两段填充中间。
     */
    internal fun String.stripFiller(): String {
        if (length < FILLER_PREFIX_MIN) return this
        return REPEAT.replace(this) { match ->
            val unit = match.groupValues[1]
            // 单字重复是语气（哈哈哈），不当填充。
            if (unit.toSet().size <= 1) match.value else ""
        }
    }

    /** 行首整段是固定周期重复（如 nullnull…）时剥掉，保留后面的正文。 */
    internal fun String.trimFillerPrefix(): String {
        val text = cleanNoise()
        if (text.length < FILLER_PREFIX_MIN) return text
        // 「哈哈哈…」这类单字重复是语气，不是刷屏填充。
        if (text.take(FILLER_PREFIX_MIN).toSet().size <= 1) return text
        for (period in 2..FILLER_MAX_PERIOD) {
            var end = period
            while (end + period <= text.length &&
                text.regionMatches(end - period, text, end, period)) end += period
            if (end >= FILLER_PREFIX_MIN) return text.substring(end).trim()
        }
        return text
    }

    /** 长且按固定短周期重复的文本基本是刷屏填充，例如 uinulluinull…；正常的重复语气词不在此列。 */
    internal fun String.isFiller(): Boolean {
        val text = cleanNoise()
        if (text.length < FILLER_MIN_LENGTH) return false
        if (text.toSet().size <= 1) return false
        val sample = text.take(1200)
        for (period in 2..FILLER_MAX_PERIOD) {
            var repeating = true
            for (index in period until sample.length) {
                if (sample[index] != sample[index % period]) { repeating = false; break }
            }
            if (repeating) return true
        }
        return false
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
