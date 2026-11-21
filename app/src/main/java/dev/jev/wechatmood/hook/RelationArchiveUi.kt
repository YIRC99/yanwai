package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.RelationFacts
import dev.jev.wechatmood.reply.RelationMeta
import dev.jev.wechatmood.reply.ReplyContactKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关系档案窗口：这个联系人都有哪些有效事实，按类型分组。
 *
 * 和待办分开：待办只关心带时间的约定与重要日期，这里看全集。
 * 移出档案用 cancelled 保留可恢复；作废条目由合并逻辑维护，这里不碰。
 */
internal class RelationArchiveUi(private val activity: Activity, private val talker: String?) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val summaryText: TextView = theme.label("", ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
        setPadding(0, theme.dp(4), 0, 0)
    }
    private val statusText: TextView = theme.label("", ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
        setPadding(0, theme.dp(4), 0, 0)
        visibility = View.GONE
    }
    private var items: List<RelationFact> = emptyList()
    /** 打开档案时抓到的页面快照；整理与依据查询都只认这个账号，不再重读屏幕。 */
    private var snapshot: RelationFactRefresher.Snapshot? = null
    private var meta: RelationMeta = RelationMeta()
    private var contactKey: ReplyContactKey? = null
    private var refreshing = false
    private var removedExpanded = false
    private var supersededExpanded = false
    private var doneExpanded = false
    private var query = ""
    private val searchField: EditText = EditText(activity).apply {
        hint = "搜索档案内容"
        setSingleLine(true)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ReplyMetrics.TEXT_BODY)
        setTextColor(theme.ink)
        setHintTextColor(theme.muted)
        background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
        setPadding(theme.dp(10), theme.dp(6), theme.dp(10), theme.dp(6))
        visibility = View.GONE
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(editable: android.text.Editable?) {
                query = editable?.toString().orEmpty()
                render()
            }
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }
    private var dialog: Dialog? = null
    private var addDialog: Dialog? = null

    fun show() {
        val dialog = Dialog(activity).also { this.dialog = it }
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("关系档案"))
        column.addView(kit.caption("从聊天里整理出的事实；可以先补一条，也可以把不对的移出去").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        column.addView(searchField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        column.addView(summaryText.apply {
            isClickable = true; isFocusable = true
            contentDescription = "整理设置，点击修改"
            setOnClickListener { openSettings() }
        })

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, theme.dp(4))
        }
        actions.addView(theme.action("整理一次", primary = true) { refresh() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = theme.dp(8) })
        actions.addView(theme.action("添加一条") { pickKind() }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(actions)

        column.addView(statusText)
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()).apply {
            addView(listContainer)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener {
            addDialog?.dismiss()
            scope.cancel()
        }
        dialog.show()

        val selected = talker
        MoodLog.i("RELATION_ARCHIVE_OPEN talker=${selected != null} group=${selected?.endsWith("@chatroom") == true}")
        if (selected == null || selected.endsWith("@chatroom")) {
            showStatus("档案只针对私聊；群聊没有联系人档案", theme.warning)
            summaryText.text = "返回一个私聊再打开"
            return
        }
        showStatus("正在读取…", theme.muted)
        scope.launch {
            val key = resolveKey(selected)
            contactKey = key
            // 记下这条档案对应哪个聊天和备注名，到期通知点开才能进对会话。
            if (key != null) {
                val name = withContext(Dispatchers.IO) {
                    runCatching { ReplyDatabaseHistory.displayNames(listOf(selected), relaxed = true)[selected] }.getOrNull()
                }
                RelationFactBridge.rememberContact(activity, key, selected, name.orEmpty())
            }
            if (key == null) {
                showStatus("没能确认是哪个账号的聊天，先回到聊天页等一会儿", theme.warning)
                return@launch
            }
            load(key)
        }
    }

    /** 档案按账号与联系人哈希隔离，确认不了账号就不猜一个 key。 */
    private suspend fun resolveKey(selected: String): ReplyContactKey? {
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull() ?: return null
        if (live.talker != selected) return null
        val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(live) }
        // 之后切去别的聊天也不影响：整理和依据查询都用这份快照定位账号。
        snapshot = account?.let { RelationFactRefresher.Snapshot(it, selected, live.messages) }
        return ReplyContactKey.of(account, selected)
    }

    private fun reload() {
        val key = contactKey ?: return
        scope.launch { load(key) }
    }

    private suspend fun load(key: ReplyContactKey) {
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                RelationFactBridge.loadAll(activity, key) to RelationFactBridge.meta(activity, key)
            }
        }.getOrElse { error ->
            // 以前这里没有兜底：读库一抛异常，窗口就永远停在「正在读取…」。
            MoodLog.w("RELATION_ARCHIVE_LOAD_FAILED " + error.javaClass.simpleName)
            showStatus("读取档案失败，稍后重试", theme.error)
            return
        }
        items = loaded.first
        meta = loaded.second
        hideStatus()
        MoodLog.i("RELATION_ARCHIVE_LOAD total=${loaded.first.size} open=${loaded.first.count { it.isOpen }}")
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        searchField.visibility = if (items.size >= 6) View.VISIBLE else View.GONE
        // 搜索只影响列表；顶部「有效 N 条」始终是全部档案的数量。
        val activeAll = items.count { it.isOpen }
        val matched = if (query.isBlank()) items else items.filter {
            it.text.contains(query, true) || it.kind.contains(query, true)
        }
        val active = matched.filter { it.isOpen }
        val removed = matched.filter { it.status == RelationFact.STATUS_CANCELLED }
            .sortedByDescending { it.updatedAt }.take(REMOVED_LIMIT)
        val superseded = matched.filter { it.status == RelationFact.STATUS_SUPERSEDED }
            .sortedByDescending { it.updatedAt }.take(REMOVED_LIMIT)
        // 已完成也留一个入口：待办页的「已处理」只留最近 6 条，再早的以前哪儿都翻不到。
        val done = matched.filter { it.status == RelationFact.STATUS_DONE }
            .sortedByDescending { it.updatedAt }.take(REMOVED_LIMIT)
        val head = buildString {
            append(if (items.isEmpty()) "还没有整理过" else "有效 $activeAll 条")
            if (query.isNotBlank()) append(" · 匹配 ${matched.size} 条")
            if (meta.lastAt > 0) append(" · 最近整理 ").append(FactTimes.formatLocal(meta.lastAt, "MM-dd HH:mm"))
        }
        // 「采集最近 N 条」是设置入口；不加颜色和箭头，看不出来能点。
        val tail = buildString {
            append("整理：")
            append(if (ModulePrefs.relationFactIncremental()) "仅新增" else "全量重看")
            append(" · 最多 ").append(ModulePrefs.relationFactLimit()).append(" 条 ▾")
        }
        val summary = SpannableStringBuilder(if (items.isEmpty()) tail else head + " · " + tail)
        val start = summary.length - tail.length
        summary.setSpan(ForegroundColorSpan(theme.accent), start, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        summary.setSpan(StyleSpan(Typeface.BOLD), start, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        summaryText.text = summary

        if (active.isEmpty()) {
            // 只有已移出/已作废时，别说「还没有档案」，语义对不上。
            listContainer.addView(kit.body(
                if (items.isEmpty()) "这个联系人还没有档案" else "没有有效条目，已处理过的在下面"
            ))
            listContainer.addView(kit.caption("点「整理一次」读最近聊天自动整理，或「添加一条」自己写"))
        }
        for (kind in RelationFact.KINDS) {
            val group = active.filter { it.kind == kind }.sortedByDescending { it.updatedAt }
            if (group.isEmpty()) continue
            listContainer.addView(kit.sectionLabel("$kind · ${group.size} 条"))
            if (group.size > 1 && kind in RelationFacts.SINGLE_VALUE_KINDS) {
                listContainer.addView(kit.caption("同类有多条，可能有一条已经过时，点开可以移出旧的"))
            }
            group.forEach { listContainer.addView(factRow(it), rowParams()) }
        }
        if (removed.isNotEmpty()) {
            // 默认收起来，只占一行；移出去的东西不该继续占版面。
            val toggle = kit.sectionLabel(
                if (removedExpanded) "已移出 · ${removed.size} 条 ▴" else "已移出 · ${removed.size} 条 ▾"
            ).apply {
                isClickable = true; isFocusable = true
                contentDescription = "已移出 ${removed.size} 条，${if (removedExpanded) "点击收起" else "点击展开"}"
                setOnClickListener { removedExpanded = !removedExpanded; render() }
            }
            listContainer.addView(toggle)
            if (removedExpanded) removed.forEach { listContainer.addView(factRow(it), rowParams()) }
        }
        if (done.isNotEmpty()) {
            val toggle = kit.sectionLabel(
                if (doneExpanded) "已完成 · ${done.size} 条 ▴" else "已完成 · ${done.size} 条 ▾"
            ).apply {
                isClickable = true; isFocusable = true
                contentDescription = "已完成 ${done.size} 条，${if (doneExpanded) "点击收起" else "点击展开"}"
                setOnClickListener { doneExpanded = !doneExpanded; render() }
            }
            listContainer.addView(toggle)
            if (doneExpanded) done.forEach { listContainer.addView(factRow(it), rowParams()) }
        }
        if (superseded.isNotEmpty()) {
            // 改期、换工作留下的旧说法：默认不展开，但能翻出来还原。
            val toggle = kit.sectionLabel(
                if (supersededExpanded) "已作废 · ${superseded.size} 条 ▴" else "已作废 · ${superseded.size} 条 ▾"
            ).apply {
                isClickable = true; isFocusable = true
                contentDescription = "已作废 ${superseded.size} 条，${if (supersededExpanded) "点击收起" else "点击展开"}"
                setOnClickListener { supersededExpanded = !supersededExpanded; render() }
            }
            listContainer.addView(toggle)
            if (supersededExpanded) superseded.forEach { listContainer.addView(factRow(it), rowParams()) }
        }
    }

    /** 一条事实：正文在上，来源与时间在下；整行可点。 */
    private fun factRow(fact: RelationFact): View {
        val removed = fact.status == RelationFact.STATUS_CANCELLED
        val superseded = fact.status == RelationFact.STATUS_SUPERSEDED
        val settled = removed || superseded
        val note = buildString {
            when {
                removed -> append("已移出")
                superseded -> append("已作废 · 被后来的说法替换")
                else -> append(if (fact.source == "manual") "手动添加" else "自动整理")
            }
            if (!settled && ModulePrefs.relationShowUpdated() && fact.updatedAt > 0) {
                if (isNotEmpty()) append(" · ")
                append("整理于 ").append(FactTimes.formatLocal(fact.updatedAt, "MM-dd HH:mm"))
            }
            if (!settled && fact.source != "manual" && fact.confidence < 0.4) {
                if (isNotEmpty()) append(" · ")
                append("模型不确定")
            }
            if (fact.occurredAt > 0) {
                if (isNotEmpty()) append(" · ")
                append(FactTimes.label(fact.occurredAt))
            }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true; isFocusable = true
            contentDescription = "${fact.kind}，${fact.text}，$note，点击操作"
            setOnClickListener { ask(fact) }
        }
        card.addView(theme.label(fact.text, ReplyMetrics.TEXT_ITEM, if (settled) theme.muted else theme.ink))
        card.addView(theme.label(note, ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
            setPadding(0, theme.dp(2), 0, 0)
        })
        return card
    }

    /** 操作：看依据、移出／恢复，彻底删除单独二次确认。 */
    private fun ask(fact: RelationFact) {
        val hasEvidence = fact.sourceChatMessageId > 0
        val labels = buildList {
            if (hasEvidence) add("看依据消息")
            if (fact.isOpen) {
                add("移出档案（可恢复）")
                add("彻底删除")
            } else if (fact.status == RelationFact.STATUS_SUPERSEDED) {
                add("恢复为档案（现行那条会让位）")
                add("彻底删除")
            } else {
                add("恢复为档案")
                add("彻底删除")
            }
        }
        kit.picker(fact.text, labels) { index ->
            when (labels.getOrNull(index)) {
                "看依据消息" -> showEvidence(fact)
                "移出档案（可恢复）" -> update(fact, RelationFact.STATUS_CANCELLED)
                "恢复为档案" -> update(fact, RelationFact.STATUS_ACTIVE)
                "恢复为档案（现行那条会让位）" -> restore(fact)
                "彻底删除" -> confirmDelete(fact)
            }
        }
    }

    private fun confirmDelete(fact: RelationFact) {
        // 只放一个选项，取消交给 picker 自带的居中按钮，不再出现两个「取消」。
        kit.picker("彻底删除「${fact.text}」？", listOf("删除，以后整理也不再出现")) { index ->
            if (index == 0) remove(fact)
        }
    }

    /** 整理设置：范围、方式、是否显示整理时间。都只存在本机微信进程里。 */
    private fun openSettings() {
        val items = listOf(
            "采集范围 · 最多 ${ModulePrefs.relationFactLimit()} 条",
            "整理方式 · ${if (ModulePrefs.relationFactIncremental()) "仅新增" else "全量重看"}",
            "显示整理时间 · ${if (ModulePrefs.relationShowUpdated()) "开" else "关"}",
            "导出档案到剪贴板 · ${this.items.size} 条",
            "从剪贴板导入档案"
        )
        kit.picker("整理设置", items) { index ->
            when (index) {
                0 -> pickLimit()
                1 -> pickMode()
                2 -> toggleUpdated()
                3 -> exportFacts()
                4 -> importFacts()
            }
        }
    }

    private fun pickMode() {
        val options = listOf(
            "仅新增：只看上次之后的新消息",
            "全量重看：每次重读最近 ${ModulePrefs.relationFactLimit()} 条"
        )
        val current = if (ModulePrefs.relationFactIncremental()) 0 else 1
        kit.picker("整理方式", options, current) { index ->
            if (ModulePrefs.setRelationFactIncremental(index == 0)) {
                render()
                showStatus(if (index == 0) "之后只整理新消息" else "之后每次重看最近 ${ModulePrefs.relationFactLimit()} 条", theme.muted)
            } else {
                showStatus("设置没保存成功，重试一次", theme.error)
            }
        }
    }

    /** 导出全部条目（含已移出、已作废）：换机或清数据前留一份。 */
    private fun exportFacts() {
        if (items.isEmpty()) {
            showStatus("还没有可以导出的档案", theme.muted)
            return
        }
        val json = org.json.JSONArray(items.map { it.encode().toString() }).toString()
        val clipboard = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showStatus("这台手机没有可用的剪贴板", theme.error)
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("言外关系档案", json))
        MoodLog.i("RELATION_ARCHIVE_EXPORT count=" + items.size)
        showStatus("已复制 " + items.size + " 条档案到剪贴板", theme.success)
    }

    /** 从剪贴板导入：与自动整理同一套合并，同一句话不会变成两条。 */
    private fun importFacts() {
        val key = contactKey ?: return
        val clipboard = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = runCatching { clipboard?.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() }.getOrNull()
            .orEmpty()
        val array = runCatching { org.json.JSONArray(text) }.getOrNull()
        val facts = array?.let { payload -> (0 until payload.length()).mapNotNull { payload.optJSONObject(it)?.let(RelationFact::decode) } }
            .orEmpty()
        if (facts.isEmpty()) {
            showStatus("剪贴板里没有可导入的档案", theme.error)
            return
        }
        showStatus("正在导入…", theme.muted)
        scope.launch {
            val open = withContext(Dispatchers.IO) {
                runCatching { RelationFactBridge.importFacts(activity, key, facts) }.getOrDefault(-1)
            }
            if (open < 0) {
                showStatus("导入失败，剪贴板内容可能不是档案 JSON", theme.error)
                return@launch
            }
            showStatus("已导入，现在有效 " + open + " 条", theme.success)
            load(key)
        }
    }

    private fun toggleUpdated() {
        val next = !ModulePrefs.relationShowUpdated()
        if (ModulePrefs.setRelationShowUpdated(next)) {
            render()
            showStatus(if (next) "条目会显示整理时间" else "已隐藏整理时间", theme.muted)
        } else {
            showStatus("设置没保存成功，重试一次", theme.error)
        }
    }

    /** 采集范围：默认 50 条，30 条更快更省；再往上推理模型容易返回空。 */
    private fun pickLimit() {
        val values = ModulePrefs.RELATION_FACT_LIMIT_VALUES
        val current = ModulePrefs.relationFactLimit()
        kit.picker("单次最多读多少条历史", values.map { "最近 $it 条" }, values.indexOf(current)) { index ->
            val value = values.getOrNull(index) ?: return@picker
            if (ModulePrefs.setRelationFactLimit(value)) {
                render()
                showStatus("下次整理读取最近 $value 条", theme.muted)
            } else {
                showStatus("范围没保存成功，重试一次", theme.error)
            }
        }
    }

    private fun showEvidence(fact: RelationFact) {
        val selected = talker ?: return
        val messageId = fact.sourceChatMessageId
        if (messageId <= 0) return
        scope.launch {
            // 只查档案所属账号的库：切号后旧库还开着时，另一个账号也可能有同一个 msgId。
            val message = withContext(Dispatchers.IO) {
                ReplyDatabaseHistory.messageById(selected, messageId, snapshot?.account)
            }
            if (message == null) {
                kit.info("依据消息", "这条消息已经不在本机数据库里了（可能被清理过）")
                return@launch
            }
            val time = if (message.createdAt > 0) FactTimes.formatLocal(message.createdAt, "MM-dd HH:mm") else "时间未知"
            // 把读到的真实类型写进日志：用户说那条明明是文字，得能看出到底读成了什么。
            val extracted = message.analysisText() ?: message.evidenceBody()
            MoodLog.i("RELATION_EVIDENCE type=" + message.type + " send=" + message.isSend +
                " len=" + message.content.length + " shown=" + (extracted?.length ?: -1))
            val body = extracted
            val kind = message.evidenceKind()
            val nearby = withContext(Dispatchers.IO) {
                ReplyDatabaseHistory.nearbyText(selected, messageId, snapshot?.account)
            }
            val around = if (nearby.isEmpty()) "" else "\n\n上下文：\n" + nearby.joinToString("\n") { item ->
                val stamp = if (item.createdAt > 0) FactTimes.formatLocal(item.createdAt, "HH:mm") else "--:--"
                val text = item.plainText()?.replace("\n", " ")?.take(40) ?: "[${item.evidenceKind()}]"
                "$stamp ${item.speaker()}：$text"
            }
            kit.info("依据消息", "${message.speaker()} · $time · $kind${around}\n\n" +
                (body ?: "【这条被读成$kind，没有可显示的正文】"))
        }
    }

    private fun update(fact: RelationFact, status: String) {
        val key = contactKey ?: return
        showStatus("正在保存…", theme.muted)
        scope.launch {
            val saved = withContext(Dispatchers.IO) { RelationFactBridge.setStatus(activity, key, fact.factId, status) }
            MoodLog.i("RELATION_ARCHIVE_STATUS ok=$saved status=$status")
            if (!saved) {
                showStatus("没保存成功，档案可能刚被更新过，重开一次再试", theme.error)
                return@launch
            }
            load(key)
            RelationReminder.refresh(activity, key, talker)
        }
    }

    private fun restore(fact: RelationFact) {
        val key = contactKey ?: return
        showStatus("正在恢复…", theme.muted)
        scope.launch {
            val saved = withContext(Dispatchers.IO) { RelationFactBridge.restore(activity, key, fact.factId) }
            MoodLog.i("RELATION_ARCHIVE_RESTORE ok=$saved")
            if (!saved) {
                showStatus("没恢复成功，可能刚被重新整理过，重开一次再试", theme.error)
                return@launch
            }
            load(key)
            RelationReminder.refresh(activity, key, talker)
        }
    }

    private fun remove(fact: RelationFact) {
        val key = contactKey ?: return
        showStatus("正在删除…", theme.muted)
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { RelationFactBridge.delete(activity, key, fact.factId) }
            MoodLog.i("RELATION_ARCHIVE_DELETE ok=$deleted")
            if (!deleted) {
                showStatus("没删成功，可能刚被重新整理过，重开一次再试", theme.error)
                return@launch
            }
            load(key)
            RelationReminder.refresh(activity, key, talker)
            showStatus("已彻底删除，之后的自动整理不会再抽回这条", theme.success)
        }
    }

    private fun refresh() {
        val key = contactKey ?: return
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) {
            showStatus("请先在「回复」里配置模型，再整理档案", theme.warning)
            return
        }
        if (refreshing) return
        val limit = ModulePrefs.relationFactLimit()
        val incremental = ModulePrefs.relationFactIncremental()
        refreshing = true
        showStatus(if (incremental) "正在整理新消息…" else "正在整理最近 $limit 条…", theme.muted)
        // 独立 scope：关掉窗口或切去别的聊天，这次整理仍会跑完并落库。
        val started = RelationFactRefresher.launch(activity, key, settings, limit, incremental,
            snapshot) { result, error ->
            refreshing = false
            when {
                error != null -> {
                    MoodLog.w("RELATION_ARCHIVE_REFRESH_FAILED " + error.javaClass.simpleName)
                    showStatus("整理失败：" + (error.message ?: "请重试"), theme.error)
                }
                result == null -> Unit
                result.nothingNew -> showStatus("最近没有新消息，不用整理", theme.muted)
                result.facts.isEmpty() -> showStatus("这次没有提取到新的事实", theme.muted)
                else -> {
                    reload()
                    showStatus("已更新：有效 ${result.facts.size} 条 · 看了 ${result.scanned} 条", theme.success)
                }
            }
        }
        if (!started) showStatus("正在整理，稍等…", theme.muted)
    }

    /** 手动补一条：先选类型，再填正文；约定和重要日期可以带一个绝对时间。 */
    private fun pickKind() {
        kit.picker("要添加的类型", RelationFact.KINDS) { index -> addSheet(RelationFact.KINDS[index]) }
    }

    private fun addSheet(kind: String) {
        val sheet = kit.sheet()
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("添加一条${kind}"))
        column.addView(kit.caption("不超过 30 个字，别写推断出来的东西"))
        val text = input("例如：在游戏公司做运营")
        column.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        val timed = kind in RelationFact.TIMED_KINDS
        // 日期和时刻都用自绘刻度条滑选：系统日历+表盘又高又丑，底部还会被屏幕切掉。
        var occurredAt = 0L
        if (timed) {
            val today = FactTimes.startOfDay(System.currentTimeMillis())
            val soon = System.currentTimeMillis() + 3_600_000L
            var dayMillis = FactTimes.startOfDay(soon)
            var minuteOfDay = (((soon - dayMillis) / 60_000L).toInt()).let { it - it % 5 }
            var dayOnly = kind == "重要日期"
            fun picked(): Long = if (dayOnly) dayMillis else dayMillis + minuteOfDay * 60_000L

            val dayBack = 30
            val dayForward = 90
            fun dayAt(index: Int): Long = today + (index - dayBack) * 86_400_000L
            val dayOffset = ((dayMillis - today) / 86_400_000L).toInt()

            val dateRuler = RulerPicker(activity, theme, min = 0, max = dayBack + dayForward, step = 1,
                value = dayBack + dayOffset, spacing = theme.dp(78),
                label = { index -> FactTimes.label(dayAt(index)) }) { index ->
                dayMillis = dayAt(index)
                occurredAt = picked()
            }
            val timeRuler = RulerPicker(activity, theme, min = 0, max = 24 * 60 / 5 - 1, step = 1,
                value = minuteOfDay / 5, spacing = theme.dp(54),
                label = { index -> String.format(java.util.Locale.CHINA, "%02d:%02d", index * 5 / 60, index * 5 % 60) }
            ) { index ->
                minuteOfDay = index * 5
                occurredAt = picked()
            }
            occurredAt = picked()

            column.addView(kit.caption("日期 · 左右滑动").apply { setPadding(0, theme.dp(10), 0, 0) })
            column.addView(dateRuler, LinearLayout.LayoutParams(-1, -2))
            val timeCaption = kit.caption("时刻 · 左右滑动").apply { setPadding(0, theme.dp(6), 0, 0) }
            column.addView(timeCaption)
            column.addView(timeRuler, LinearLayout.LayoutParams(-1, -2))
            val dayOnlySwitch = android.widget.Switch(activity).apply {
                // 不能写 text = ...：外层有个同名 val（正文输入框），会被解析成它。
                setText("只到日期，不写几点")
                isChecked = dayOnly
                textSize = ReplyMetrics.TEXT_BODY
                setTextColor(theme.ink)
                minimumHeight = theme.dp(ReplyMetrics.TAP)
                setPadding(0, theme.dp(8), 0, 0)
                setOnCheckedChangeListener { _, value ->
                    dayOnly = value
                    val gone = if (value) android.view.View.GONE else android.view.View.VISIBLE
                    timeCaption.visibility = gone
                    timeRuler.visibility = gone
                    occurredAt = picked()
                }
            }
            column.addView(dayOnlySwitch, LinearLayout.LayoutParams(-1, -2))
            if (dayOnly) {
                timeCaption.visibility = android.view.View.GONE
                timeRuler.visibility = android.view.View.GONE
            }
        }
        val hint = kit.caption("")
        column.addView(hint)
        column.addView(theme.action("保存", primary = true) {
            val body = text.text.toString().trim()
            when {
                body.isEmpty() -> hint.text = "写一句再保存"
                body.length > RelationFact.MAX_TEXT -> hint.text = "超过 ${RelationFact.MAX_TEXT} 个字了"
                else -> {
                    sheet.dismiss()
                    addDialog = null
                    save(kind, body, if (timed) occurredAt else 0)
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        column.addView(theme.action("取消", quiet = true) {
            sheet.dismiss()
            addDialog = null
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })
        sheet.setContentView(root)
        sheet.setOnDismissListener { if (addDialog === sheet) addDialog = null }
        sheet.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addDialog = sheet
        sheet.show()
        text.requestFocus()
    }

    private fun save(kind: String, body: String, occurredAt: Long) {
        val key = contactKey ?: return
        showStatus("正在保存…", theme.muted)
        scope.launch {
            val active = withContext(Dispatchers.IO) {
                RelationFactBridge.addManual(activity, key, kind, body, occurredAt)
            }
            MoodLog.i("RELATION_ARCHIVE_ADD ok=${active != null} kind=$kind dated=${occurredAt > 0}")
            if (active == null) {
                showStatus("没保存成功，检查内容后重试", theme.error)
                return@launch
            }
            load(key)
            showStatus("已添加", theme.success)
        }
    }

    private fun input(hintText: String): EditText = EditText(activity).apply {
        textSize = ReplyMetrics.TEXT_BODY
        setTextColor(theme.ink); setHintTextColor(theme.muted)
        setBackground(theme.shape(theme.card, ReplyMetrics.RADIUS_BUTTON, theme.border))
        setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        hint = hintText
        minHeight = theme.dp(ReplyMetrics.TAP)
    }

    private fun showStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = View.GONE
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(8) }

    private companion object {
        const val REMOVED_LIMIT = 6
    }
}
