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
    private val accountScope: String? = null,
    private val reload: (() -> Unit)? = null) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stats: GroupStats
    /** 筛选变化时原地重算，不重建面板。 */
    private var onFilterChanged: (() -> Unit)? = null
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

    /** 当前筛选命中的消息；AI 观察与表格使用同一份，口径一致。 */
    private var filtered: List<dev.jev.wechatmood.reply.ReplyMessage> = selectedMessages()

    init { stats = computeStats() }

    /** 按当前筛选重算；媒体数量与表格、AI 观察共用同一批记录。 */
    private fun computeStats(): GroupStats {
        filtered = selectedMessages()
        val records = filtered.map {
            StatRecord(it.time, if (it.speaker == "我") 1 else 0, it.type, it.text, it.speaker)
        }
        return GroupStatsCalculator.compute(records, sampled = context.trimmed)
    }

    /** 说明行：跟着筛选走，切时段时只换文字。 */
    private fun summaryText(): String {
        val window = when (StatsFilter.range) {
            StatsFilter.Range.TODAY -> "今天 00:00–现在"
            else -> StatsFilter.range.label
        }
        return "$window · ${StatsFilter.limitLabel} · 共 ${stats.total} 条 · 人均 ${stats.perCapita} 条 · 只在本机计算"
    }

    private fun spanText(): String {
        val first = context.messages.firstOrNull { it.time > 0 }?.time
        val last = context.messages.lastOrNull { it.time > 0 }?.time
        if (first == null || last == null) return "未知"
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        return format.format(java.util.Date(first)) + "–" + format.format(java.util.Date(last))
    }

    fun show() {
        lateinit var scroll: android.widget.ScrollView
        val dialog = Dialog(activity).also { this.dialog = it }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(18), theme.dp(20), theme.dp(18))
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }
        body.addView(kit.heading(if (context.talker.endsWith("@chatroom")) "群聊统计" else "聊天统计"))
        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        val rangePill = filterPill("时段 · ${StatsFilter.range.label}") { pickRange() }
        val limitPill = filterPill("条数 · ${StatsFilter.limitLabel}") { pickLimit() }
        filters.addView(rangePill, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = theme.dp(8) })
        filters.addView(limitPill, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(filters)
        val summaryLine = kit.caption(summaryText())
        val spanLine = kit.caption("数据范围 ${spanText()}")
        body.addView(summaryLine)
        body.addView(spanLine)

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        val cells = listOf("条消息", "人发言", "图片语音", "个时段").map { number(it) }
        cells.forEach { numbers.addView(it.first, weight()) }
        body.addView(numbers, LinearLayout.LayoutParams(-1, -2))

        body.addView(LinearLayout(activity), LinearLayout.LayoutParams(-1, theme.dp(6)))
        body.addView(topContainer, LinearLayout.LayoutParams(-1, -2))
        renderTop(emptyMap())

        val activeBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(activeBox, LinearLayout.LayoutParams(-1, -2))
        renderActiveHours(activeBox)

        val analysisBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(analysisBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        analysisText = ""

        lateinit var aiButton: TextView
        aiButton = theme.action("让 AI 说说这群在聊什么", primary = true) {
            aiButton.isEnabled = false
            aiButton.text = "正在分析…"
            analysisBox.visibility = View.VISIBLE
            analysisBox.removeAllViews()
            analysisBox.addView(panelWith("群里在聊什么", kit.loading().apply { showLoading("正在分析最近聊天…") }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            scope.launch {
                val text = runCatching { analyzeAll(context) }.getOrElse { error ->
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED " + error.message)
                    "分析失败：" + (error.message ?: "请重试")
                }
                val failed = text.startsWith("分析失败")
                val shown = text.ifBlank { "（模型返回空内容）" }
                analysisText = text
                analysisBox.removeAllViews()
                val content = kit.body(shown).apply { if (failed) setTextColor(theme.error) }
                analysisBox.addView(panelWith("群里在聊什么", content),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
                aiButton.text = "重新分析"
                aiButton.isEnabled = true
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        // 切换筛选：只改数字、表格和时段，不关窗口、不重建面板，滚动位置也保留。
        onFilterChanged = {
            stats = computeStats()
            rangePill.text = "时段 · ${StatsFilter.range.label}"
            limitPill.text = "条数 · ${StatsFilter.limitLabel}"
            summaryLine.text = summaryText()
            spanLine.text = "数据范围 ${spanText()}"
            cells[0].second.text = "${stats.total}"
            cells[1].second.text = "${stats.senders}"
            cells[2].second.text = "${stats.media + stats.voice}"
            cells[3].second.text = "${stats.activeHours.size}"
            renderTop(currentNames)
            renderActiveHours(activeBox)
            // 换了范围，旧的观察对不上新数据，直接清掉，避免复制或出图带错内容。
            analysisBox.removeAllViews()
            analysisBox.visibility = View.GONE
            analysisText = ""
            aiButton.text = "让 AI 说说这群在聊什么"
            scope.launch {
                val names = runCatching {
                    ReplyDatabaseHistory.displayNames(stats.members.map { it.name }, accountScope)
                }.getOrDefault(emptyMap())
                if (names.isNotEmpty()) renderTop(currentNames + names)
            }
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        actions.addView(theme.action("复制", quiet = true) {
            copy(exportText(analysisText))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(theme.action("生成图片", quiet = true) {
            val summary = analysisText.takeIf {
                it.isNotBlank() && !it.startsWith("分析失败")
            }
            val bitmap = StatsImage.render(stats, displayedNames, summary, "群聊统计")
            val uri = StatsImage.save(activity, bitmap)
            Toast.makeText(activity,
                if (uri != null) "已保存到相册，可在相册里发送" else "保存失败，检查相册权限",
                Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(14))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }
        body.background = null

        scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (accountScope == null) {
            root.addView(TextView(activity).apply {
                text = "无法确认当前登录账号，只统计当前页面可见的 ${filtered.size} 条消息"
                textSize = ReplyMetrics.TEXT_CAPTION
                setTextColor(theme.muted)
                setPadding(theme.dp(20), theme.dp(4), theme.dp(20), 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val modelName = ModulePrefs.replySettings().model.takeIf { it.isNotBlank() } ?: "未配置"
        root.addView(TextView(activity).apply {
            text = "点「群里在聊什么」会把当前筛选的 ${minOf(filtered.size, 35)} 条消息文本发给模型（$modelName）"
            textSize = ReplyMetrics.TEXT_CAPTION
            setTextColor(theme.muted)
            setPadding(theme.dp(20), theme.dp(4), theme.dp(20), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(aiButton, LinearLayout.LayoutParams(-1, -2).apply {
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
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()

        // 昵称在数据库里，异步查一次再替换 wxid；顺便记一次表名，方便确认字段位置。
        scope.launch {
            runCatching { ReplyDatabaseHistory.tableNames() }
            val names = runCatching {
                ReplyDatabaseHistory.displayNames(stats.members.map { it.name }, accountScope)
            }.getOrDefault(emptyMap())
            if (names.isNotEmpty()) renderTop(names)
        }
    }

    private fun renderTop(names: Map<String, String>) {
        currentNames = names
        topContainer.removeAllViews()
        displayedNames.clear()
        if (stats.members.isEmpty()) {
            topContainer.addView(kit.caption("没有可统计的发言"))
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
        topContainer.addView(headerRow(), LinearLayout.LayoutParams(-1, -2))
        val ordered = merged.entries.sortedByDescending { it.value }
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(ordered.map { it.value })
        ordered.forEachIndexed { index, entry ->
            topContainer.addView(tableRow(entry.key, "${entry.value}", "${percents[index]}%"),
                LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun resolveName(raw: String, names: Map<String, String>): String? {
        // 数据库里有名字优先用；没有就用原始名字。只要不是空白就显示，截断即可。
        val fromDb = names[raw]?.let { dev.jev.wechatmood.core.TextClean.plain(it).trim() }?.takeIf { it.isNotEmpty() }
        val clean = (fromDb ?: raw).trim()
        // 数据库里缺失的字段会写 null / uinull，那不是名字。
        if (clean.isEmpty() || clean.equals("null", true) || clean.equals("uinull", true)) return null
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
        minimumHeight = theme.dp(24)
        addView(theme.label("成员", ReplyMetrics.TEXT_CAPTION, theme.muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(theme.label("条数", ReplyMetrics.TEXT_CAPTION, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(theme.label("占比", ReplyMetrics.TEXT_CAPTION, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    private fun tableRow(name: String, count: String, percent: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(28)
        addView(theme.label(name, ReplyMetrics.TEXT_BODY, theme.ink).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(theme.label(count, ReplyMetrics.TEXT_BODY, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(theme.label(percent, ReplyMetrics.TEXT_BODY, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(52), -2))
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
        kit.info("$shownName · ${member.count} 条",
            "活跃时段：${member.hours.joinToString("、") { "$it 点" }.ifBlank { "未知" }}\n" +
                "发言范围：${span.ifBlank { "未知" }}\n\n$lines")
    }

    /** 分析卡片：点标题折叠展开。 */
    private fun panelWith(label: String, content: View): LinearLayout {
        val card = kit.card()
        val header = kit.body(label).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        card.addView(header)
        card.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })
        header.setOnClickListener {
            content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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

    /**
     * 群聊转述的提示词：先概括、再挑事举例、最后有梗收尾，写法照合并转发摘要。
     * 素材里的 speaker 已经换成昵称，模型不该再看到 wxid。
     */
    private val summaryPrompt = """
        你在一个微信群里蹲了一阵子，现在跟旁边的人聊起这个群最近在干嘛。

        按这个顺序写，分三段，段与段之间空一行：
        第一段：一两句话概括这帮人最近主要在折腾什么，顺带一句你对他们状态的感觉（活跃、水、跑题、互相抬杠，挑一个说得准的）。
        第二段：挑两到三件最有意思、最具体的事，说清是谁做的、说了什么。挑有画面感或者好笑的，不要按时间顺着讲。
        第三段：一句收尾。可以是吐槽、可以埋个后续的梗，也可以一句话给这帮人定性。要有意思，但别喊口号。

        说话方式：
        - 短句为主，可以有半截话，像边想边说。
        - 不要「首先、其次、最后」「虽然、但是」「不仅、而且」这类连接词，
          也不要「综上所述」「总而言之」「值得一提的是」「值得注意的是」。
        - 不分点、不编号、不加小标题、不用 Markdown、不用 emoji 当标题，
          不写「群氛围」「成员角色」「沟通建议」这种分析标题。
        - 不要按时间一条条复述消息，那是记流水账；只挑值得说的。
        - 引用的原话必须真的在消息里出现过，不要编。
        - 群友的昵称照原样说，别人的话别改写成书面语。
        - 如果只是普通闲聊没什么事，就照实说在聊什么，别硬编冲突，也别总结出什么群体规律。
        - 写成 [图片] [语音] 的只当占位，不要猜内容。
        - 不复述统计数字，不评价这个群好还是不好。
        - 全文 300 字左右，最多三段。

        结构示例（只说明怎么组织，内容不要照搬）：
        这帮人最近没干正事，全在折腾刷机，从模块冲突聊到开机卡在 logo，气氛是一边互相鼓励一边互相劝退。
        最有意思的是老王，前脚说「再刷一次就退群」，后脚又贴了个新版本；小张在旁边专门泼冷水，「你上次也是这么说的」。
        最后谁也没真刷，话题拐到外卖点什么去了，倒也挺和谐。

        只输出这三段。
    """.trimIndent()

    /** 一次请求拿到一段转述。 */
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
        // 与界面筛选保持一致：只发当前筛选命中的消息文本；
        // speaker 是 wxid 或微信号，喂模型之前换成群里的显示名，查不到的编成「群友 N」。
        val sampleSource = filtered.takeLast(35)
        val rawSpeakers = sampleSource.map { it.speaker }.distinct()
        val resolved = runCatching { ReplyDatabaseHistory.displayNames(rawSpeakers, accountScope) }
            .getOrDefault(emptyMap())
        val labels = LinkedHashMap<String, String>()
        fun speakerLabel(raw: String): String = labels.getOrPut(raw) {
            val fromDb = resolved[raw]?.let { TextClean.plain(it).trim() }
            fromDb?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
                ?: raw.takeIf { !it.startsWith("wxid") && !it.matches(Regex("[A-Za-z0-9_-]{14,}")) }
                ?: "群友${labels.size + 1}"
        }
        val sample = sampleSource.joinToString("\n") { "${speakerLabel(it.speaker)}: ${it.text.take(80)}" }
        val client = ReplyHttpClient()
        var limit = 3000
        repeat(2) { attempt ->
            val payload = JSONObject().put("model", current.model).put("stream", false)
                .put("max_tokens", limit).put("temperature", 0.35)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", summaryPrompt))
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
            val clean = TextClean.plain(raw).take(900)
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

    private fun filterPill(label: String, action: () -> Unit): TextView = kit.pill(label, action)

    private fun pickRange() {
        val values = StatsFilter.Range.values()
        kit.picker("分析时段", values.map { it.label }, values.indexOf(StatsFilter.range)) { which ->
            StatsFilter.range = values[which]
            dev.jev.wechatmood.core.MoodLog.i("STATS_FILTER range=${StatsFilter.range}")
            onFilterChanged?.invoke()
        }
    }

    private fun pickLimit() {
        val options = listOf(10, 30, 50, 100, 0)
        kit.picker("消息条数", options.map { if (it == 0) "该时段全部" else "最近 $it 条" },
            options.indexOf(StatsFilter.limit)) { which ->
            StatsFilter.limit = options[which]
            dev.jev.wechatmood.core.MoodLog.i("STATS_FILTER limit=${StatsFilter.limit}")
            onFilterChanged?.invoke()
        }
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    /** 数字块：排版固定，只把数字视图交出去，换筛选时改文字不重排。 */
    private fun number(label: String): Pair<LinearLayout, TextView> {
        val value = TextView(activity).apply {
            textSize = 20f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(0, theme.dp(6), 0, theme.dp(6))
            addView(value)
            addView(TextView(activity).apply {
                text = label
                textSize = ReplyMetrics.TEXT_CAPTION
                setTextColor(theme.muted)
            })
        }
        return box to value
    }

    private fun renderActiveHours(box: LinearLayout) {
        box.removeAllViews()
        if (stats.activeHours.isEmpty()) return
        box.addView(kit.sectionLabel("活跃时段"))
        box.addView(kit.caption(stats.activeHours.joinToString("、") { "${it} 点" }))
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
