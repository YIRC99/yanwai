package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/** Native widgets only: module resource IDs are not valid inside the host's resource table. */
class ReplyHostUi(private val activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = ReplySession()
    private val history = ReplyHistory.process
    private val plusEntry = ReplyPlusEntry(activity) { open() }
    private var job: Job? = null
    private var historyJob: Job? = null
    private var talker: String? = null
    private var dialog: Dialog? = null
    private var snapshot: ReplyContext? = null
    private var pendingDraft: DraftReplacement? = null
    private var pendingSelection: RememberedReply? = null
    private var undoView = WeakReference<EditText>(null)
    private var newMessageNotice: TextView? = null
    private var invalidateRequest: (() -> Unit)? = null

    fun update(currentTalker: String?) {
        if (talker != currentTalker) { hide(); talker = currentTalker }
        if (currentTalker == null) return
        runCatching { plusEntry.update(footer()) }.onFailure { MoodLog.w("REPLY_PLUS_UPDATE_FAILED ${it.javaClass.simpleName}") }
        if (dialog?.isShowing == true) {
            if ((!ModulePrefs.replyConsent || !ModulePrefs.replySettings().isConfigured) &&
                (job?.isActive == true || historyJob?.isActive == true)) {
                job?.cancel(); historyJob?.cancel(); session.cancel(); invalidateRequest?.invoke()
            }
            updateNotice()
        }
    }
    private fun updateNotice() {
        val captured = snapshot ?: return
        val latest = MessageSniffer.replyBoundary() ?: return
        newMessageNotice?.visibility = if (latest != captured.latestLoadedId && captured.messages.none { it.id == latest }) View.VISIBLE else View.GONE
    }
    private fun footer(): View? = views(activity.window.decorView).singleOrNull {
        it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && it.isShown
    }
    private fun editor(): EditText? = footer()?.let { root ->
        views(root).filterIsInstance<EditText>().filter { it.isShown && it.isEnabled }.singleOrNull()
    }

    fun open(focusMessageId: Long? = null): Boolean {
        val selectedTalker = talker ?: return false
        if (MessageSniffer.currentReplyTalker() != selectedTalker) return false
        if (dialog?.isShowing == true) return true
        val remembered = history.recall(selectedTalker, focusMessageId)
        val composition = ReplyComposition(remembered)
        val reference = ReplyHistorySelection(selectedTalker, remembered?.context)
        if (remembered == null && !canGenerate()) { configure(); return true }
        // Reopening saved content must not depend on scrolling to the latest message or text input mode.
        val captured = remembered?.context ?: runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrElse {
            toast(it.message ?: "读取聊天失败"); return true
        }
        if (captured.talker != selectedTalker) return false
        val initialEditor = editor()
        if (remembered == null && initialEditor == null) { toast("请先切换到文字输入"); return true }
        var baselineDraft = initialEditor?.text?.toString().orEmpty()
        var draftView = WeakReference(initialEditor)
        val focusId = remembered?.focusMessageId ?: focusMessageId
        snapshot = remembered?.context
        initialEditor?.let { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(it.windowToken, 0) }
        val theme = ReplyTheme(activity)
        fun dp(value: Int) = theme.dp(value)
        fun action(text: String, primary: Boolean = false, quiet: Boolean = false, click: () -> Unit = {}) =
            theme.action(text, primary, quiet) {
                runCatching(click).onFailure { MoodLog.w("REPLY_UI_FAILED ${it.javaClass.simpleName}"); toast("操作失败，请重试") }
            }
        fun line() = View(activity).apply { setBackgroundColor(theme.border) }
        val window = Dialog(activity)
        dialog = window
        fun fitWindow() {
            val available = activity.window.decorView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            window.window?.setLayout(-1, minOf(dp(if (composition.result == null) 360 else 560), (available * 0.78f).toInt()))
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10))
            background = theme.shape(theme.surface, 24); elevation = dp(12).toFloat(); isFocusableInTouchMode = true
        }
        body.addView(View(activity).apply { background = theme.shape(theme.border, 2) },
            LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(theme.label("帮我回", 18f, bold = true))
            addView(theme.label("言外 ${BuildConfig.VERSION_NAME} · 狗头军师", 11f, theme.muted))
        }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("关闭", quiet = true) { window.dismiss() }, LinearLayout.LayoutParams(dp(56), dp(48)))
        body.addView(heading)
        val progress = ReplyLoadingView(activity, theme)
        body.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val scroll = ScrollView(activity).apply { isFillViewport = false; clipToPadding = false }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        val roleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fun roleLabel() = if (composition.relationship == ReplyRelationship.UNSPECIFIED) "选择对方身份 ▾" else "${composition.relationship.label} ▾"
        val rolePicker = action(roleLabel())
        rolePicker.contentDescription = "选择对方身份，当前${composition.relationship.label}"
        val historyPicker = action("参考最近 ${composition.historyLimit} 条 ▾")
        listOf(rolePicker, historyPicker).forEach { it.textSize = 13f; it.setPadding(dp(8), dp(6), dp(8), dp(6)) }
        roleRow.addView(rolePicker, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        roleRow.addView(historyPicker, LinearLayout.LayoutParams(0, -2, 1.2f))
        results.addView(roleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val instruction = EditText(activity).apply {
            hint = "补充想法（可选）\n回复：如“想委婉拒绝，别太正式”\n找话题：如“她喜欢摄影，10月3日公历生日，最近在准备考试”"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            gravity = Gravity.TOP or Gravity.START; minLines = 2; maxLines = 4; minHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = theme.shape(theme.card, 12, theme.border)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(2000)); isSaveEnabled = false
            setText(remembered?.direction.orEmpty())
            setOnFocusChangeListener { _, focused -> background = theme.shape(theme.card, 12, if (focused) theme.accent else theme.border) }
        }
        results.addView(instruction, LinearLayout.LayoutParams(-1, -2))
        val generateButton = action("生成回复", primary = true)
        val topicButton = action("找找话题")
        val shorter = action("更简短", quiet = true)
        val generationActions = LinearLayout(activity)
        generationActions.addView(generateButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        generationActions.addView(topicButton, LinearLayout.LayoutParams(0, -2, 1f))
        results.addView(generationActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val state = theme.label("", 12f, theme.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(6), 0, dp(6))
        }
        results.addView(state)
        val stale = action("有新消息 · 重新读取", quiet = true)
        stale.visibility = View.GONE; newMessageNotice = stale
        results.addView(stale, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        val replyTitle = action("", quiet = true) { composition.result?.context?.let(::showEvidence) }.apply {
            textSize = 12f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
        }
        results.addView(replyTitle)
        val parts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        results.addView(parts)
        val topicHint = theme.label("", 12f, theme.muted).apply { setPadding(0, dp(6), 0, dp(4)) }
        results.addView(topicHint)
        val reasonToggle = action("为什么这样回 ▾", quiet = true)
        val reason = theme.label("", 12f, theme.muted).apply { visibility = View.GONE }
        reasonToggle.setOnClickListener {
            val expanded = reason.visibility != View.VISIBLE
            reason.visibility = if (expanded) View.VISIBLE else View.GONE
            reasonToggle.text = if (expanded) "收起理由 ▴" else if (composition.result?.topics != null) "为什么聊这个 ▾" else "为什么这样回 ▾"
        }
        results.addView(reasonToggle); results.addView(reason); results.addView(shorter)
        scroll.addView(results); body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(line(), LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
        val undo = action("撤销上次填入", quiet = true)
        undo.visibility = if (pendingDraft == null) View.GONE else View.VISIBLE; body.addView(undo)
        val use = action("填入这条")
        val copy = action("复制这条", quiet = true) {
            composition.selectedText?.let { text ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("言外回复建议", text))
                toast("已复制第 ${composition.selectedPart + 1} 条")
            }
        }
        val footerActions = LinearLayout(activity).apply { setPadding(0, dp(10), 0, 0) }
        footerActions.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        footerActions.addView(use, LinearLayout.LayoutParams(0, -2, 2f)); body.addView(footerActions)
        val footerHint = theme.label("每次填入一条，由你发送", 11f, theme.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        body.addView(footerHint)
        var busy = false
        var failed = false
        var findingTopics = false
        fun topicKey(current: ReplyContext, draft: String, date: String) = TopicKey(current.fingerprint, current.requestedMessages,
            composition.relationship, instruction.text.toString(), draft, date)
        fun controls(generating: Boolean) {
            val reading = reference.loading
            val ready = reference.context
            busy = generating
            progress.showLoading(when {
                generating && findingTopics -> "正在准备 5 个新话题…"
                generating -> "正在根据 ${ready?.messages?.size ?: 0} 条消息生成回复…"
                reading -> "正在读取最近 ${composition.historyLimit} 条消息…"
                else -> null
            })
            generateButton.isEnabled = !generating && !reading
            generateButton.text = when {
                generating -> if (findingTopics) "生成回复" else "正在生成…"
                reading -> "正在读取消息…"
                ready == null -> "重新读取消息"
                failed -> "重试生成"
                ready.source == ReplyContextSource.LOADED_PAGE -> "按已读取 ${ready.messages.size} 条生成"
                composition.result == null || composition.result?.topics != null -> "生成回复"
                !composition.canUse -> "按新选择生成"
                else -> "重新生成"
            }
            shorter.isEnabled = !generating && !reading && ready != null && composition.canUse
            shorter.visibility = if (composition.result == null || composition.result?.topics != null) View.GONE else View.VISIBLE
            val batch = composition.result?.topics
            val currentTopicKey = ready?.let { topicKey(it, editor()?.text?.toString().orEmpty(), java.time.LocalDate.now().toString()) }
            val sameTopicInputs = composition.canUse && batch != null && batch.key == currentTopicKey
            topicButton.isEnabled = !generating && !reading
            topicButton.text = when {
                generating && findingTopics -> "正在找话题…"
                sameTopicInputs -> "换个话题"
                else -> "找找话题"
            }
            topicHint.visibility = if (batch == null) View.GONE else View.VISIBLE
            topicHint.text = when {
                !sameTopicInputs -> "参考内容或补充背景已变化，点「找找话题」生成新的一组"
                batch?.hasNext == true -> "本组还有 ${batch.items.size - batch.shownCount} 个，点「换个话题」立即切换"
                else -> "本组已看完，再点「换个话题」生成新的一组"
            }
            instruction.isEnabled = !generating; rolePicker.isEnabled = !generating; stale.isEnabled = !generating
            historyPicker.isEnabled = !generating
            historyPicker.text = "参考最近 ${composition.historyLimit} 条 ▾"
            historyPicker.contentDescription = "选择参考聊天消息条数，当前最近 ${composition.historyLimit} 条"
            copy.isEnabled = !generating && !reading && composition.canUse; use.isEnabled = !generating && !reading && composition.canUse
            use.text = if (batch != null) "填入这条" else "填入第 ${composition.selectedPart + 1} 条"
            copy.text = "复制这条"
            footerActions.visibility = if (composition.result == null) View.GONE else View.VISIBLE
            footerHint.visibility = footerActions.visibility
            footerHint.text = if (!composition.canUse) "身份或参考范围已改变，请重新生成"
                else if (batch != null) "只填入当前开场白，由你决定何时发送" else "每次填入一条，由你发送；再打开可继续下一条"
            state.visibility = if (state.text.isBlank()) View.GONE else View.VISIBLE
            if (window.isShowing) fitWindow()
        }
        fun renderParts() {
            parts.removeAllViews()
            val result = composition.result
            replyTitle.visibility = if (result == null) View.GONE else View.VISIBLE
            val batch = result?.topics
            val kind = if (batch == null) "${result?.suggestion?.parts?.size ?: 0} 条建议" else "话题 ${batch.shownCount} / ${batch.items.size}"
            replyTitle.text = result?.let { "${it.relationship.label} · $kind${if (!composition.canUse) "（上次结果）" else ""} · 查看依据 ›" }.orEmpty()
            result?.suggestion?.parts?.forEachIndexed { index, text ->
                val selected = composition.selectedPart == index
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(48)
                    background = theme.shape(if (selected) theme.soft else theme.card, 12, if (selected) theme.accent else theme.border)
                    addView(theme.label(batch?.current?.title ?: "${index + 1} / ${result.suggestion.parts.size}${if (selected) " · 已选" else " · 点选"}", 12f, theme.muted))
                    addView(theme.label(text, 16f).apply { setPadding(0, dp(3), 0, 0) })
                    isClickable = true; isFocusable = true
                    contentDescription = "第 ${index + 1} 条，共 ${result.suggestion.parts.size} 条${if (selected) "，已选" else ""}，$text"
                    setOnClickListener { composition.select(index); renderParts(); controls(busy) }
                }
                parts.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            reason.text = result?.suggestion?.reason.orEmpty()
            reasonToggle.visibility = if (reason.text.isBlank()) View.GONE else View.VISIBLE
            if (reason.text.isBlank()) reason.visibility = View.GONE
            reasonToggle.text = if (reason.visibility == View.VISIBLE) "收起理由 ▴" else if (batch != null) "为什么聊这个 ▾" else "为什么这样回 ▾"
        }
        rolePicker.setOnClickListener {
            PopupMenu(activity, rolePicker).apply {
                ReplyRelationship.entries.forEachIndexed { index, relationship ->
                    menu.add(0, index, index, relationship.label).isChecked = relationship == composition.relationship
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    if (!busy) {
                        composition.relationship = ReplyRelationship.entries[item.itemId]
                        rolePicker.text = roleLabel()
                        rolePicker.contentDescription = "选择对方身份，当前${composition.relationship.label}"
                        failed = false
                        renderParts(); controls(false)
                    }
                    true
                }
            }.show()
        }
        invalidateRequest = { reference.cancel(); state.text = "请重新配置回复模型"; failed = true; controls(false) }
        fun remember() {
            composition.result?.let(history::remember)
        }
        fun prepareHistory(limit: Int) {
            if (busy) return
            if (MessageSniffer.currentReplyTalker() != selectedTalker) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            historyJob?.cancel()
            composition.historyLimit = limit
            val ticket = reference.begin(limit)
            snapshot = null; stale.visibility = View.GONE
            failed = false
            state.text = ""; state.setTextColor(theme.muted)
            renderParts(); controls(false)
            val loaded = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrElse {
                reference.fail(ticket); state.text = it.message ?: "读取聊天失败，请重试"; controls(false); return
            }
            if (loaded.talker != selectedTalker) { reference.cancel(); window.dismiss(); return }
            historyJob = scope.launch {
                try {
                    val current = ReplyDatabaseHistory.load(loaded, limit)
                    ensureActive()
                    if (window !== dialog || !ModulePrefs.replyConsent) return@launch
                    if (current.messages.isEmpty()) {
                        reference.fail(ticket)
                        state.text = "尚未读取到文字消息，请在条数菜单中重试"
                        return@launch
                    }
                    if (!reference.complete(ticket, current, MessageSniffer.currentReplyTalker())) return@launch
                    snapshot = current
                    state.text = when {
                        current.historyFailure != null -> "仅读取到页面 ${current.messages.size} 条。历史暂不可用，可切换条数重试。"
                        current.messages.size < limit -> "本次读取到 ${current.messages.size} 条文字，将按实际内容回复。"
                        else -> ""
                    }
                    updateNotice()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (reference.isCurrent(ticket) && window === dialog) state.text = "历史读取失败，请重试"
                } finally {
                    if (reference.isCurrent(ticket) && window === dialog) { reference.fail(ticket); controls(busy) }
                }
            }
        }
        historyPicker.setOnClickListener {
            PopupMenu(activity, historyPicker).apply {
                ReplyHistorySelection.OPTIONS.forEach { limit ->
                    menu.add(0, limit, limit, "最近 $limit 条消息").isChecked = limit == composition.historyLimit
                }
                menu.setGroupCheckable(0, true, true)
                reference.context?.let { menu.add(1, 1, 101, "查看已读取的 ${it.messages.size} 条消息") }
                menu.add(1, 2, 102, "重新读取")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> reference.context?.let(::showEvidence)
                        2 -> prepareHistory(composition.historyLimit)
                        else -> prepareHistory(item.itemId)
                    }
                    true
                }
            }.show()
        }
        fun generate(direction: String = "", findTopics: Boolean = false) {
            if (busy || reference.loading) return
            if (MessageSniffer.currentReplyTalker() != selectedTalker) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            val current = reference.context ?: run { prepareHistory(composition.historyLimit); return }
            val latest = MessageSniffer.replyBoundary()
            if (latest != null && latest != current.latestLoadedId && current.messages.none { it.id == latest }) {
                prepareHistory(composition.historyLimit); toast("聊天有更新，读取完成后再点生成"); return
            }
            val input = editor() ?: run { toast("请先切换到文字输入"); return }
            baselineDraft = input.text.toString(); draftView = WeakReference(input)
            val relationship = composition.relationship
            val notes = instruction.text.toString()
            val time = if (findTopics) TopicCalendar.current() else null
            val requestedTopicKey = time?.let { topicKey(current, baselineDraft, it.date) }
            if (requestedTopicKey != null && composition.nextTopic(requestedTopicKey)) {
                failed = false; state.text = ""; reason.visibility = View.GONE
                reasonToggle.text = "为什么这样回 ▾"
                renderParts(); controls(false); remember(); return
            }
            job?.cancel()
            val config = ModulePrefs.replySettings()
            val ticket = session.begin(current.talker, current.fingerprint)
            val previous = if (composition.result?.topics == null) composition.previousText else ""
            val previousTopics = composition.result?.topics?.items.orEmpty()
            state.text = ""; state.setTextColor(theme.muted)
            failed = false; findingTopics = findTopics
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(instruction.windowToken, 0)
            body.requestFocus()
            controls(true)
            job = scope.launch {
                try {
                    val knowledge = withContext(Dispatchers.IO) { ReplyKnowledge.load(activity, relationship) }
                    // Upload only the prepared evidence, after rechecking consent, conversation and settings.
                    ensureActive()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || window !== dialog || !ModulePrefs.replyConsent) return@launch
                    val beforeSend = ModulePrefs.replySettings()
                    if (beforeSend.endpoint != config.endpoint || beforeSend.model != config.model || beforeSend.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    val client = ReplyHttpClient()
                    val topics = if (findTopics) client.findTopics(config, current, baselineDraft, notes, knowledge, relationship,
                        requireNotNull(time), previousTopics) else null
                    val suggestion = if (findTopics) null else client.generate(config, current, baselineDraft, direction, knowledge, previous, focusId, relationship)
                    val activeConfig = ModulePrefs.replySettings()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || window !== dialog || !ModulePrefs.replyConsent) return@launch
                    if (activeConfig.endpoint != config.endpoint || activeConfig.model != config.model || activeConfig.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    val accepted = if (topics != null) composition.acceptTopics(current, topics, requireNotNull(requestedTopicKey), focusId)
                        else composition.accept(current, requireNotNull(suggestion), notes, focusId, relationship)
                    if (!accepted) return@launch
                    snapshot = current
                    reason.visibility = View.GONE; reasonToggle.text = "为什么这样回 ▾"; renderParts()
                    state.text = ""
                    remember(); updateNotice()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (session.accepts(ticket, MessageSniffer.currentReplyTalker()) && window === dialog) {
                        failed = true
                        state.text = "${if (findTopics) "找话题" else "生成"}失败${if (composition.result == null) "" else " · 保留上次建议"}"
                        state.setTextColor(theme.error)
                        toast(e.message ?: "暂时无法生成，请重试")
                    }
                } finally {
                    if (session.accepts(ticket, talker) && window === dialog) controls(false)
                }
            }
        }
        generateButton.setOnClickListener {
            generate(instruction.text.toString().ifBlank { if (composition.canUse) "换一种自然表达，不要重复上一组建议" else "" })
        }
        topicButton.setOnClickListener { generate(findTopics = true) }
        instruction.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { controls(busy) }
        })
        shorter.setOnClickListener { generate(instruction.text.toString() + "\n保持原意，更简短一点") }
        stale.setOnClickListener { prepareHistory(composition.historyLimit) }
        undo.setOnClickListener {
            val filled = pendingSelection
            undoDraft()?.let { original ->
                baselineDraft = original; draftView = WeakReference(editor())
                filled?.let(composition::restoreSelection)
                renderParts(); controls(busy); remember()
            }
            undo.visibility = View.GONE
        }
        use.setOnClickListener {
            val text = composition.selectedText ?: return@setOnClickListener
            val input = editor()
            if (MessageSniffer.currentReplyTalker() != selectedTalker || input == null || input !== draftView.get()) {
                toast("输入框已变化，请复制建议，或重新打开后填入"); return@setOnClickListener
            }
            if (input.text.toString() != baselineDraft) { toast("草稿已修改，请复制建议，避免覆盖新内容"); return@setOnClickListener }
            // Reopening and filling the same suggestion must not replace the original undo record.
            if (input.text.toString() == text) { window.dismiss(); return@setOnClickListener }
            if (input === undoView.get() && pendingDraft?.blocksReplacement(input.text.toString(), text) == true) {
                toast("上一条还在输入框，请先发送或清空，再填下一条"); return@setOnClickListener
            }
            pendingDraft = DraftReplacement(baselineDraft, text); undoView = WeakReference(input)
            input.setText(text); input.setSelection(input.text.length)
            pendingSelection = composition.result
            composition.afterFill(); remember()
            window.dismiss(); input.requestFocus()
        }
        renderParts(); controls(false)
        window.setContentView(body)
        window.setOnDismissListener {
            if (dialog !== window) return@setOnDismissListener
            remember(); job?.cancel(); historyJob?.cancel(); reference.cancel(); session.cancel()
            progress.showLoading(null)
            if (dialog === window) { dialog = null; newMessageNotice = null; snapshot = null; invalidateRequest = null }
        }
        window.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.32f)
        }
        window.show()
        fitWindow()
        body.requestFocus(); updateNotice()
        if (remembered == null) prepareHistory(composition.historyLimit)
        return true
    }
    private fun canGenerate() = ModulePrefs.replyConsent && ModulePrefs.replySettings().isConfigured
    private fun configure() {
        AlertDialog.Builder(activity).setTitle("先连接回复模型")
            .setMessage("打开言外「回复建议」，配置模型并允许手动生成。")
            .setPositiveButton("去配置") { _, _ -> openSettings() }.setNegativeButton("稍后", null).show()
    }
    private fun undoDraft(): String? {
        val input = editor()
        if (input == null || input !== undoView.get() || MessageSniffer.currentReplyTalker() != talker) return null
        val original = pendingDraft?.undo(input.text.toString())
        if (original == null) toast("内容已被修改或发送，不再覆盖")
        else { input.setText(original); input.setSelection(input.text.length); toast("已恢复原稿") }
        pendingDraft = null; pendingSelection = null; undoView.clear()
        return original
    }
    private fun contextSummary(context: ReplyContext): String {
        val first = context.messages.firstOrNull()?.time ?: 0
        val last = context.messages.lastOrNull()?.time ?: 0
        fun time(value: Long) = if (value <= 0) "时间未知" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(value))
        return "${time(first)} — ${time(last)} · 仅文字${if (context.trimmed) " · 已截取" else ""}" +
            (context.historyFailure?.let { "\n$it" } ?: "")
    }
    private fun showEvidence(context: ReplyContext) {
        val source = if (context.source == ReplyContextSource.LOCAL_HISTORY) "本机聊天历史 · 选择最近 ${context.requestedMessages} 条，实际 ${context.messages.size} 条文字（不包含图片、语音等）"
            else "仅页面已加载片段 · 跳过 ${context.omittedMedia} 条非文字"
        val info = "$source\n${contextSummary(context)}\n\n" +
            context.messages.joinToString("\n\n") { "${it.speaker} · ${ReplyProtocol.formatTime(it.time)}\n${it.text}" }
        AlertDialog.Builder(activity).setTitle("本次参考的聊天").setMessage(info).setPositiveButton("关闭", null).show()
    }
    private fun openSettings() = runCatching {
        activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity"))
            .putExtra("reply_tab", true))
    }.onFailure { toast("请从桌面打开言外，进入回复建议") }
    fun hide() {
        dialog?.dismiss(); job?.cancel(); historyJob?.cancel(); session.cancel()
        plusEntry.clear(); pendingDraft = null; pendingSelection = null; undoView.clear(); talker = null
    }
    fun dispose() { hide(); scope.cancel() }
    private fun views(root: View): List<View> = buildList {
        fun visit(v: View, depth: Int) {
            if (depth > 40 || size > 4000 || v.visibility != View.VISIBLE) return
            add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), depth + 1)
        }
        visit(root, 0)
    }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
