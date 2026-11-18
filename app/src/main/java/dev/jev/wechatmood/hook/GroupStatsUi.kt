package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.TextClean
import dev.jev.wechatmood.reply.GroupStats
import dev.jev.wechatmood.reply.GroupStatsCalculator
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHttpClient
import dev.jev.wechatmood.reply.StatRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 分析范围：进程内记住上次选择，重开窗口沿用。 */
object StatsFilter {
    enum class Range(val label: String, val millis: Long) {
        HOUR("最近 1 小时", 60L * 60 * 1000),
        HOUR3("最近 3 小时", 3 * 60L * 60 * 1000),
        HOUR12("最近 12 小时", 12 * 60L * 60 * 1000),
        HOUR24("最近 24 小时", 24 * 60L * 60 * 1000),
        TODAY("今天", -1),
    }
    // 默认给一个保守范围，避免误点就把整段聊天送去分析。
    var range: Range = Range.TODAY
    var limit: Int = 20
    val limitLabel: String get() = if (limit == 0) "该时段全部" else "最近 $limit 条"

    fun sinceMillis(now: Long = System.currentTimeMillis()): Long = when (range) {
        Range.HOUR -> now - Range.HOUR.millis
        Range.HOUR3 -> now - Range.HOUR3.millis
        Range.HOUR12 -> now - Range.HOUR12.millis
        Range.HOUR24 -> now - Range.HOUR24.millis
        Range.TODAY -> java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            timeInMillis
        }.timeInMillis
    }
}

/** 群聊统计窗口：数字 + 活跃成员 + 一次 AI 解读，结果可复制。 */
internal class GroupStatsUi(private val activity: Activity, private val context: ReplyContext,
    private val dbMessages: List<dev.jev.wechatmood.reply.ReplyMessage> = emptyList(),
    private val reload: (() -> Unit)? = null) {
    private val theme = ReplyTheme(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stats: GroupStats
    private val topContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var currentNames: Map<String, String> = emptyMap()
    /** 成员原始名 → 表格里显示的文本，生成图片时复用同一套规则。 */
    private val displayedNames = mutableMapOf<String, String>()
    private var analysisText: String = ""

    /** 看着有码点、渲染却是空白的字符。 */
    private val BLANK_CODES = setOf(
        0x3164, 0x2800, 0x115F, 0x1160, 0xFFA0, 0x00A0, 0x3000,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x200B, 0x200C, 0x200D, 0x2028, 0x2029, 0x202F, 0x205F, 0xFEFF, 0xFE0F,
    )
    private var dialog: Dialog? = null

    /** 按选择的时段与条数裁剪消息。 */
    private fun selectedMessages(): List<dev.jev.wechatmood.reply.ReplyMessage> {
        val since = StatsFilter.sinceMillis()
        // 优先用数据库按时间查到的消息，页面上下文只有最近几十条。
        val source = dbMessages.ifEmpty { context.messages }
        val byTime = source.filter {
            val stamp = if (it.time > 1_000_000_000_000L) it.time else it.time * 1000
            stamp >= since
        }
        return if (StatsFilter.limit > 0) byTime.takeLast(StatsFilter.limit) else byTime
    }

    init {
        val records = selectedMessages().map {
            StatRecord(it.time, if (it.speaker == "我") 1 else 0, 1, it.text, it.speaker)
        }
        val base = GroupStatsCalculator.compute(records, sampled = context.trimmed)
        // 媒体不在回复上下文里，用漏掉的条数补上，否则永远是 0。
        val voices = context.messages.count { it.voiceState == dev.jev.wechatmood.voice.VoiceState.READY }
        stats = base.copy(media = context.omittedMedia, voice = voices)
    }

    fun show() {
        lateinit var scroll: android.widget.ScrollView
        val dialog = Dialog(activity).also { this.dialog = it }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(18), theme.dp(20), theme.dp(18))
            background = theme.shape(theme.surface, 22, theme.border)
        }
        body.addView(title(if (context.talker.endsWith("@chatroom")) "群聊统计" else "聊天统计"))
        val first = context.messages.firstOrNull { it.time > 0 }?.time
        val last = context.messages.lastOrNull { it.time > 0 }?.time
        val span = if (first != null && last != null) {
            val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            " · " + format.format(java.util.Date(first)) + "–" + format.format(java.util.Date(last))
        } else ""
        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        filters.addView(filterPill("时段 · ${StatsFilter.range.label}") { pickRange() },
            LinearLayout.LayoutParams(0, theme.dp(34), 1f).apply { rightMargin = theme.dp(8) })
        filters.addView(filterPill("条数 · ${StatsFilter.limitLabel}") { pickLimit() },
            LinearLayout.LayoutParams(0, theme.dp(34), 1f))
        body.addView(filters)
        val windowText = when (StatsFilter.range) {
            StatsFilter.Range.TODAY -> "今天 00:00–现在"
            else -> StatsFilter.range.label
        }
        body.addView(caption("$windowText · ${StatsFilter.limitLabel} · 共 ${stats.total} 条 · 人均 ${stats.perCapita} 条 · 只在本机计算"))
        body.addView(caption("数据范围 $span"))

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        numbers.addView(number("${stats.total}", "条消息"), weight())
        numbers.addView(number("${stats.senders}", "人发言"), weight())
        numbers.addView(number("${stats.media + stats.voice}", "图片语音"), weight())
        numbers.addView(number("${stats.activeHours.size}", "个时段"), weight())
        body.addView(numbers, LinearLayout.LayoutParams(-1, theme.dp(72)))

        body.addView(LinearLayout(activity), LinearLayout.LayoutParams(-1, theme.dp(6)))
        body.addView(topContainer, LinearLayout.LayoutParams(-1, -2))
        renderTop(emptyMap())

        if (stats.activeHours.isNotEmpty()) {
            body.addView(section("活跃时段"))
            body.addView(caption(stats.activeHours.joinToString("、") { "${it} 点" }))
        }

        val analysisBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(analysisBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        analysisText = ""

        fun showAnalysis(text: String) {
            analysisText = text
            analysisBox.removeAllViews()
            analysisBox.visibility = View.VISIBLE
            val parsed = splitSections(text)
            // 模型没按标签写时，本地按句子切成几块，至少不是一整段。
            val sections = parsed.ifEmpty {
                val sentences = text.split("。", "！", "？").map { it.trim() }.filter { it.isNotEmpty() }
                sentences.chunked(2).mapIndexed { index, chunk ->
                    "要点 ${index + 1}" to (chunk.joinToString("。") + "。")
                }
            }
            if (sections.isEmpty()) {
                analysisBox.addView(panel("分析", text))
                return
            }
            sections.forEachIndexed { index, (label, content) ->
                // 默认只展开前两块，其余折起来，避免一屏全是字。
                val card = panel(label, content, collapsed = index >= 2)
                analysisBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            }
        }

        lateinit var aiButton: TextView
        aiButton = theme.action("让 AI 说说这群在聊什么", primary = true) {
            aiButton.isEnabled = false
            aiButton.text = "正在分析…"
            analysisBox.visibility = View.VISIBLE
            analysisBox.removeAllViews()
            val body = TextView(activity).apply {
                text = "正在分析…"
                textSize = 13f
                setTextColor(theme.ink)
                setPadding(0, theme.dp(4), 0, 0)
                setLineSpacing(0f, 1.3f)
            }
            analysisBox.addView(panelWith("AI 观察", body),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            scope.launch {
                val text = runCatching { analyzeAll(context) }.getOrElse { error ->
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED " + error.message)
                    "分析失败：" + (error.message ?: "请重试")
                }
                body.text = text.ifBlank { "（模型返回空内容）" }
                analysisText = text
                aiButton.text = "重新分析"
                aiButton.isEnabled = true
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        actions.addView(theme.action("复制", quiet = true) {
            copy(exportText(analysisText))
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("生成图片", quiet = true) {
            val summary = analysisText.takeIf {
                it.isNotBlank() && !it.startsWith("分析失败")
            }
            val bitmap = StatsImage.render(stats, displayedNames, summary, "群聊统计")
            val uri = StatsImage.save(activity, bitmap)
            Toast.makeText(activity,
                if (uri != null) "已保存到相册，可在相册里发送" else "保存失败，检查相册权限",
                Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(14))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.shape(theme.surface, 22, theme.border)
        }
        body.background = null

        scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(aiButton, LinearLayout.LayoutParams(-1, theme.dp(44)).apply {
            setMargins(theme.dp(20), theme.dp(6), theme.dp(20), 0)
        })
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        val screenHeight = activity.resources.displayMetrics.heightPixels
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (screenHeight * 0.82).toInt())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
        }
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()

        // 昵称在数据库里，异步查一次再替换 wxid；顺便记一次表名，方便确认字段位置。
        scope.launch {
            runCatching { ReplyDatabaseHistory.tableNames() }
            val names = runCatching {
                ReplyDatabaseHistory.displayNames(stats.members.map { it.name })
            }.getOrDefault(emptyMap())
            if (names.isNotEmpty()) renderTop(names)
        }
    }

    private fun renderTop(names: Map<String, String>) {
        currentNames = names
        topContainer.removeAllViews()
        if (stats.members.isEmpty()) {
            topContainer.addView(caption("没有可统计的发言"))
            return
        }
        val merged = LinkedHashMap<String, Int>()
        var anonymous = 0
        var unknown = 0
        for (member in stats.members) {
            val resolved = resolveName(member.name, names)
            val shown = resolved ?: "未获取昵称#${++unknown}"
            displayedNames[member.name] = shown
            merged[shown] = (merged[shown] ?: 0) + member.count
        }
        topContainer.addView(headerRow(), LinearLayout.LayoutParams(-1, theme.dp(24)))
        val ordered = merged.entries.sortedByDescending { it.value }
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(ordered.map { it.value })
        ordered.forEachIndexed { index, entry ->
            topContainer.addView(tableRow(entry.key, "${entry.value}", "${percents[index]}%"),
                LinearLayout.LayoutParams(-1, theme.dp(26)))
        }
    }

    private fun resolveName(raw: String, names: Map<String, String>): String? {
        // 数据库里有名字优先用；没有就用原始名字。只要不是空白就显示，截断即可。
        val fromDb = names[raw]?.let { dev.jev.wechatmood.core.TextClean.plain(it).trim() }?.takeIf { it.isNotEmpty() }
        val clean = (fromDb ?: raw).trim()
        // 空白、零宽、格式控制符都算「没有名字」，避免表格里出现空行。
        // 只把「真的能看见」的字符算名字：字母、数字、标点、符号。
        // 但 U+3164、U+2800 这类字符虽然属于「字母/符号」，渲染出来是空白，必须单独排除。
        val visible = clean.codePoints().anyMatch { cp ->
            if (cp in BLANK_CODES) {
                false
            } else {
                val type = Character.getType(cp).toInt()
                type in 1..5 || type in 9..11 || type in 20..30
            }
        }
        if (!visible || clean.startsWith("对方（群成员未知")) {
            if (clean.isNotEmpty()) {
                val codes = clean.codePoints().toArray().take(8).joinToString(",")
                dev.jev.wechatmood.core.MoodLog.w("STATS_BLANK_NAME len=${clean.length} codes=$codes")
            }
            return null
        }
        if (clean.startsWith("wxid_") || clean.matches(Regex("[A-Za-z0-9_-]{16,}"))) return null
        return shorten(clean)
    }

    private fun headerRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = "成员"
            textSize = 11f
            setTextColor(theme.muted)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = "条数"
            textSize = 11f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(TextView(activity).apply {
            text = "占比"
            textSize = 11f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    private fun tableRow(name: String, count: String, percent: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = name
            textSize = 13f
            setTextColor(theme.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = count
            textSize = 13f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(TextView(activity).apply {
            text = percent
            textSize = 13f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    /** 点成员看他自己最近说了什么，不展示全员明细。 */
    private fun showMemberDetail(member: dev.jev.wechatmood.reply.MemberStat, shownName: String) {
        val lines = context.messages.filter { it.speaker == member.name }
            .takeLast(6)
            .joinToString("\n") { "· " + it.text.take(40) }
            .ifBlank { "没有可取的代表发言" }
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val span = if (member.firstAt > 0) {
            format.format(java.util.Date(member.firstAt)) + "–" + format.format(java.util.Date(member.lastAt))
        } else ""
        android.app.AlertDialog.Builder(activity)
            .setTitle("$shownName · ${member.count} 条")
            .setMessage("活跃时段：${member.hours.joinToString("、") { "$it 点" }.ifBlank { "未知" }}\n" +
                "发言范围：${span.ifBlank { "未知" }}\n\n$lines")
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * 按标签把分析拆成板块。模型经常不换行，所以按关键词出现的位置切，
     * 而不是按行切；没出现任何标签就返回空，交给句子兜底。
     */
    private fun splitSections(text: String): List<Pair<String, String>> {
        val labels = listOf("氛围" to "群氛围", "话题" to "核心话题", "角色" to "成员角色",
            "互动" to "互动关系", "风险" to "风险提示", "建议" to "沟通建议")
        val hits = mutableListOf<Triple<Int, Int, Pair<String, String>>>()
        for ((key, label) in labels) {
            val regex = Regex("[【\\[]?$key[】\\]]?")
            var match = regex.find(text)
            while (match != null) {
                hits += Triple(match.range.first, match.range.last + 1, key to label)
                match = regex.find(text, match.range.last + 1)
            }
        }
        if (hits.isEmpty()) return emptyList()
        val sorted = hits.sortedBy { it.first }
        val result = mutableListOf<Pair<String, String>>()
        for ((index, hit) in sorted.withIndex()) {
            val start = hit.second
            val end = sorted.getOrNull(index + 1)?.first ?: text.length
            if (end <= start) continue
            val body = text.substring(start, end).trim().trimStart('：', ':', '，', ' ')
            if (body.isNotBlank()) result += hit.third.second to body
        }
        return result
    }

    /** 分析板块卡片：点标题折叠展开。 */
    private fun panelWith(label: String, body: TextView): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, 14, theme.border)
        }
        val title = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title)
        card.addView(body)
        title.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        return card
    }

    private fun panel(label: String, content: String, collapsed: Boolean = false): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, 14, theme.border)
        }
        val title = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(activity).apply {
            this.text = content
            textSize = 13f
            setTextColor(theme.ink)
            setPadding(0, theme.dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        card.addView(title)
        card.addView(body)
        if (collapsed) body.visibility = View.GONE
        title.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        return card
    }

    /**
     * 昵称显示规则：
     * 1. 数据库里查到备注/昵称就用它
     * 2. 查不到且看着像 wxid，标成「未获取昵称#N」，不裸奔原始 id
     * 3. 空白或纯符号，标成「匿名成员#N」，多个空名也能区分
     */
    private fun shorten(text: String): String = if (text.length > 10) text.take(10) + "…" else text

    private fun exportText(analysis: String): String = buildString {
        appendLine("群聊统计 · 最近 ${context.messages.size} 条")
        appendLine("共 ${stats.total} 条 · ${stats.senders} 人发言")
        if (stats.topSenders.isNotEmpty()) {
            appendLine("最活跃：" + stats.topSenders.joinToString("、") { "${it.first} ${it.second}" })
        }
        if (stats.activeHours.isNotEmpty()) {
            appendLine("活跃时段：" + stats.activeHours.joinToString("、") { "${it} 点" })
        }
        if (analysis.isNotBlank() && !analysis.startsWith("正在") && !analysis.startsWith("分析失败")) {
            appendLine()
            appendLine(analysis)
        }
    }.trim()

    /** 六个板块分开问，避免模型把内容揉成一段。 */
    private val sections = listOf(
        "群氛围" to "用两句话描述这个微信群的聊天风格和情绪走向：是互损玩梗、轻松闲聊还是公事公办。不要复述统计数字。",
        "核心话题" to "用两句话说这群最近在聊什么，归到日常闲聊、工作业务、生活琐事、八卦吐槽、事务通知其中一类。",
        "成员角色" to "用两句话说谁在发起话题、谁在接话、谁基本潜水。只依据给出的消息，不编造。",
        "互动关系" to "用两句话说成员之间的互动特点，有没有明显的小圈子。",
        "风险提示" to "用一句话说有没有争议、敏感表述或负面情绪，没有就直接说没有。",
        "沟通建议" to "用一句话说结合这个群的调性，接下来适合聊什么。",
    )

    /** 一次请求拿到六段观察，避免六次往返。 */
    private suspend fun analyzeAll(context: ReplyContext): String {
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) {
            ModulePrefs.reload(force = true)
            kotlinx.coroutines.delay(1200)
        }
        val current = ModulePrefs.replySettings()
        check(current.isConfigured) {
            if (!ModulePrefs.bridgeAvailable) "设置服务未连接：先打开一次言外再回来分析" else "先在言外「回复」里配置模型"
        }
        val sample = context.messages.takeLast(30).joinToString("\n") { "${it.speaker}: ${it.text.take(60)}" }
        val client = ReplyHttpClient()
        var limit = 3000
        repeat(2) { attempt ->
            val payload = JSONObject().put("model", current.model).put("stream", false)
                .put("max_tokens", limit).put("temperature", 0.6)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        "你在读一个微信群。按六个板块写观察，每块单独一行，分别以【氛围】【话题】【角色】【互动】【风险】【建议】开头，" +
                            "每块不超过 60 字。要写出这个群的具体风格、提到的人说了什么，不要流水账，不要复述统计数字，" +
                            "不要 Markdown，不要星号和编号。只用给出的消息，不编造。"))
                    .put(JSONObject().put("role", "user").put("content",
                        "统计：${stats.lines().joinToString("；")}\n最近消息：\n$sample")))
            val raw = withContext(Dispatchers.IO) {
                client.request(current, payload, parse = { body ->
                    val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    val message = choice.getJSONObject("message")
                    val content = message.optString("content").trim()
                    val reasoning = message.optString("reasoning_content").trim()
                    dev.jev.wechatmood.core.MoodLog.i(
                        "STATS_AI_RAW attempt=${attempt + 1} content=${content.length} reasoning=${reasoning.length} " +
                            "finish=${choice.optString("finish_reason")}")
                    content
                })
            }
            val clean = TextClean.plain(raw).take(1200)
            dev.jev.wechatmood.core.MoodLog.i("STATS_AI_ALL attempt=${attempt + 1} chars=${clean.length}")
            if (clean.isNotBlank()) return clean
            limit = 4000
        }
        throw IllegalStateException("模型两次都没返回正文，换一个非推理模型试试")
    }

    private fun copy(text: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("言外统计", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun filterPill(label: String, action: () -> Unit): TextView =
        theme.action(label, quiet = true) { action() }.apply {
            textSize = 12f
            minHeight = theme.dp(34)
            setPadding(theme.dp(8), 0, theme.dp(8), 0)
        }

    /**
     * 切换筛选后必须重新查数据库：只重建界面会继续用旧数据，
     * 于是出现「有时候对有时候不对」。
     */
    private fun recreate() {
        dialog?.dismiss()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            reload?.invoke() ?: GroupStatsUi(activity, context).show()
        }, 400)
    }

    private fun pickRange() {
        val values = StatsFilter.Range.values()
        android.app.AlertDialog.Builder(activity).setTitle("分析时段")
            .setItems(values.map { it.label }.toTypedArray()) { _, which ->
                StatsFilter.range = values[which]
                dev.jev.wechatmood.core.MoodLog.i("STATS_FILTER range=${StatsFilter.range}")
                recreate()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickLimit() {
        val options = listOf(10, 30, 50, 100, 0)
        android.app.AlertDialog.Builder(activity).setTitle("消息条数")
            .setItems(options.map { if (it == 0) "该时段全部" else "最近 $it 条" }.toTypedArray()) { _, which ->
                StatsFilter.limit = options[which]
                dev.jev.wechatmood.core.MoodLog.i("STATS_FILTER limit=${StatsFilter.limit}")
                recreate()
            }.setNegativeButton("取消", null).show()
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun title(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 18f
        setTextColor(theme.ink)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun section(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.muted)
        setPadding(0, theme.dp(14), 0, theme.dp(4))
    }
    private fun caption(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.muted)
        setPadding(0, theme.dp(4), 0, 0)
    }
    private fun number(value: String, label: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(activity).apply {
            text = value
            textSize = 20f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(theme.muted)
        })
    }
    private fun row(name: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(40)
        setPadding(0, theme.dp(2), 0, theme.dp(2))
        addView(TextView(activity).apply {
            text = name
            textSize = 13f
            setTextColor(theme.ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(theme.muted)
        }, LinearLayout.LayoutParams(-2, -2))
    }
}
