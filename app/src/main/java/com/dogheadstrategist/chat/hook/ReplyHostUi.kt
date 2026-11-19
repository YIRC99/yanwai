package com.dogheadstrategist.chat.hook

import com.dogheadstrategist.chat.sentry.WarReviewFingerprint

import com.dogheadstrategist.chat.voice.VoicePreparation
import com.dogheadstrategist.chat.voice.VoiceState

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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.dogheadstrategist.chat.BuildConfig
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.ReplyIdentityBridge
import com.dogheadstrategist.chat.core.ReplyLimitBridge
import com.dogheadstrategist.chat.reply.*
import kotlinx.coroutines.*

/** Native widgets only: module resource IDs are not valid inside the host's resource table. */
class ReplyHostUi(private val activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = ReplySession()
    private val history = ReplyHistory.process
    private val plusEntry = ReplyPlusRow(activity, { open() }, { open(autoGenerate = true) })
    private var job: Job? = null
    private var historyJob: Job? = null
    private var openingJob: Job? = null
    private var ownerEpoch: String? = null
    private var drawerRolesRevision: Long? = null
    private var savingRoleWindow: Dialog? = null
    private var talker: String? = null
    private var dialog: Dialog? = null
    private var snapshot: ReplyContext? = null
    private var newMessageNotice: TextView? = null
    private var invalidateRequest: (() -> Unit)? = null

    fun update(currentTalker: String?) {
        if (dialog?.isShowing == true && savingRoleWindow !== dialog && drawerRolesRevision != ModulePrefs.settings()?.rolesRevision) hide()
        if (talker != currentTalker || ownerEpoch?.let { it != ReplyDatabaseHistory.pendingAccountScope() } == true) {
            hide(); talker = currentTalker
        }
        if (currentTalker == null) return
        plusEntry.update(footer())
        if (dialog?.isShowing == true) {
            if (!ModulePrefs.canGenerateReply &&
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
        views(root).filterIsInstance<EditText>().filter { it.isShown && it.isEnabled }
            .let { candidates -> candidates.singleOrNull() ?: candidates.firstOrNull() }
    }

    /**
     * 把建议直接写进微信聊天输入框。
     * 优先走 InputConnection.commitText：微信把它当作用户输入，发送按钮会正常点亮；
     * 输入框未接入输入法时退回 setText。返回 false 表示当前页面取不到输入框。
     */
    private fun fillHostEditor(text: String): Boolean {
        val box = editor() ?: return false
        val filled = runCatching {
            box.requestFocus()
            box.selectAll()
            val connection = box.onCreateInputConnection(EditorInfo().apply { inputType = box.inputType })
            if (connection?.commitText(text, 1) != true) {
                box.setText(text)
                box.setSelection(box.text.length)
            }
            true
        }.getOrDefault(false)
        // 记账：这条建议进了输入框。之后用户发出去时，我的风格靠它认出「这是 AI 帮我写的」
        if (filled) WarReviewFingerprint.remember(activity, text)
        return filled
    }

    /** 抽屉关闭后把焦点和键盘交还聊天输入框，用户可直接点发送。 */
    private fun focusHostEditor(delayMillis: Long = 180L) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val box = runCatching { editor() }.getOrNull() ?: return@postDelayed
            runCatching {
                box.requestFocus()
                box.setSelection(box.text.length)
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
            }
        }, delayMillis)
    }

    fun open(focusMessageId: Long? = null, autoGenerate: Boolean = false): Boolean {
        val selectedTalker = talker ?: return false
        if (MessageSniffer.currentReplyTalker() != selectedTalker) return false
        if (dialog?.isShowing == true || openingJob?.isActive == true) return true
        val epoch = ReplyDatabaseHistory.pendingAccountScope()
        ownerEpoch = epoch
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrElse {
            toast(it.message ?: "读取聊天失败"); return true
        }
        if (live.talker != selectedTalker) return false
        openingJob = scope.launch {
            try {
                // A cold host may not have finished connecting yet. Load before capturing ownership.
                if (!ModulePrefs.bridgeAvailable) withContext(Dispatchers.IO) { ModulePrefs.reload(force = true) }
                ensureActive()
                val generation = ModulePrefs.settings()?.generation
                val rolesRevision = ModulePrefs.settings()?.rolesRevision
                check(generation != null) { "设置尚未连接" }
                val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(live) }
                val owner = ReplyIdentityOwner(epoch, account, selectedTalker)
                val identity = owner.key?.let { ReplyIdentityBridge.load(activity, it) } ?: ReplyIdentitySetting()
                val background = owner.key?.let { ReplyIdentityBridge.loadBackground(activity, it) } ?: ContactBackground()
                val preferredLimit = ReplyLimitBridge.load(activity, account)
                ensureActive()
                if (generation != ModulePrefs.settings()?.generation || rolesRevision != ModulePrefs.settings()?.rolesRevision) return@launch
                if (!owner.isCurrent(ReplyDatabaseHistory.pendingAccountScope(), MessageSniffer.currentReplyTalker())) return@launch
                val latestPage = MessageSniffer.replyContext(requireBottom = false)
                val latestAccount = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(latestPage) }
                if (generation != ModulePrefs.settings()?.generation || rolesRevision != ModulePrefs.settings()?.rolesRevision ||
                    !owner.isCurrent(ReplyDatabaseHistory.pendingAccountScope(), MessageSniffer.currentReplyTalker()) ||
                    !owner.acceptsAccount(latestAccount)) return@launch
                // All reads finish before the editor is created, so a late load cannot erase typed text.
                openResolved(focusMessageId, live, owner, identity, preferredLimit, background, autoGenerate)
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) {
                MoodLog.e("REPLY_SETTINGS_READ_FAILED", error)
                if (epoch == ReplyDatabaseHistory.pendingAccountScope() && talker == selectedTalker) {
                    if (error is com.dogheadstrategist.chat.core.SettingsConnectionUnavailableException || !ModulePrefs.bridgeAvailable)
                        com.dogheadstrategist.chat.core.Diagnostics.showSettingsConnectionHelp(activity)
                    else toast("回复设置读取失败，请重试；可在狗头军师设置中导出日志")
                }
            }
        }
        return true
    }

    private fun openResolved(focusMessageId: Long?, live: ReplyContext, owner: ReplyIdentityOwner,
        identity: ReplyIdentitySetting, preferredLimit: Int, contactBackground: ContactBackground,
        autoGenerate: Boolean = false): Boolean {
        val selectedTalker = owner.talker
        val generation = ModulePrefs.settings()?.generation
        var rolesRevision = ModulePrefs.settings()?.rolesRevision
        val remembered = history.recall(selectedTalker, focusMessageId, owner.historyScope)
        val group = selectedTalker.endsWith("@chatroom")
        val composition = ReplyComposition(remembered, if (group) ReplyIdentitySetting(
            remembered?.relationship ?: ReplyRelationship.UNSPECIFIED, remembered?.customRelationship.orEmpty()) else identity,
            preferredLimit, contactBackground)
        var selectedIdentity = identity
        val reference = ReplyHistorySelection(selectedTalker, remembered?.context?.takeIf { it.requestedMessages == preferredLimit })
        // Reopening saved content must not depend on scrolling to the latest message or text input mode.
        val captured = remembered?.context ?: live
        if (captured.talker != selectedTalker) return false
        val initialEditor = editor()
        if (remembered == null && initialEditor == null) { toast("请先切换到文字输入"); return true }
        val focusId = remembered?.focusMessageId ?: focusMessageId
        snapshot = reference.context
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
        drawerRolesRevision = rolesRevision
        fun ownsDrawer() = window === dialog && generation == ModulePrefs.settings()?.generation &&
            rolesRevision == ModulePrefs.settings()?.rolesRevision &&
            owner.isCurrent(ReplyDatabaseHistory.pendingAccountScope(), MessageSniffer.currentReplyTalker())
        suspend fun verifyAccount(): Boolean {
            if (!ownsDrawer()) return false
            val currentPage = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull() ?: return false
            val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(currentPage) }
            return ownsDrawer() && owner.acceptsAccount(account)
        }
        fun fitWindow() {
            window.window?.setLayout(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val body = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = activity.window.decorView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
                val windowLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    available else View.MeasureSpec.getSize(heightMeasureSpec)
                // AT_MOST lets short content shrink. A wrap-content weighted scroll region absorbs
                // overflow while the heading and copy action remain outside the scrolling area.
                val limit = ReplyDrawerSizing.height(available, windowLimit)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10))
            background = theme.shape(theme.surface, 24); elevation = dp(12).toFloat(); isFocusableInTouchMode = true
        }
        body.addView(View(activity).apply { background = theme.shape(theme.border, 2) },
            LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(theme.label("帮我回复", 18f, bold = true))
            addView(theme.label("狗头军师 ${BuildConfig.VERSION_NAME} · 狗头军师", 11f, theme.muted))
        }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("关闭", quiet = true) { window.dismiss() }, LinearLayout.LayoutParams(dp(56), dp(48)))
        body.addView(heading)
        val progress = ReplyLoadingView(activity, theme)
        body.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val scroll = ScrollView(activity).apply { isFillViewport = false; clipToPadding = false }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        val roleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fun roleLabel() = if (composition.relationship == ReplyRelationship.UNSPECIFIED) "选择对方身份 ▾"
            else "${composition.relationship.displayLabel(composition.customRelationship)} ▾"
        val rolePicker = action(roleLabel()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        rolePicker.contentDescription = "选择对方身份，当前${composition.relationship.displayLabel(composition.customRelationship)}"
        val historyPicker = action("参考最近 ${composition.historyLimit} 条 ▾")
        listOf(rolePicker, historyPicker).forEach { it.textSize = 13f; it.setPadding(dp(8), dp(6), dp(8), dp(6)) }
        roleRow.addView(rolePicker, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        roleRow.addView(historyPicker, LinearLayout.LayoutParams(0, -2, 1.2f))
        results.addView(roleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        var roleExpanded = composition.relationship == ReplyRelationship.OTHER && composition.customRelationship.isBlank()
        var instructionExpanded = false
        val roleToggle = action("角色背景 ▾", quiet = true)
        val instructionToggle = action("本次补充 ▾", quiet = true)
        val disclosureRow = LinearLayout(activity)
        listOf(roleToggle, instructionToggle).forEachIndexed { index, toggle ->
            toggle.textSize = 12f; toggle.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            toggle.maxLines = 2; toggle.ellipsize = android.text.TextUtils.TruncateAt.END
            toggle.setPadding(dp(4), dp(4), dp(4), dp(4))
            disclosureRow.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) rightMargin = dp(8) })
        }
        results.addView(disclosureRow)
        val roleEditor = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        results.addView(roleEditor, LinearLayout.LayoutParams(-1, -2))
        val customRole = EditText(activity).apply {
            hint = "填写对方名称或身份，如：小林、前同事"
            contentDescription = "自定义对方身份，最多 ${ReplyRelationship.MAX_CUSTOM_LENGTH} 字"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true); minHeight = dp(48)
            filters = arrayOf(InputFilter.LengthFilter(ReplyRelationship.MAX_CUSTOM_LENGTH)); isSaveEnabled = false
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = theme.shape(theme.card, 12, theme.border)
            setText(composition.customRelationship)
            visibility = if (composition.relationship == ReplyRelationship.OTHER) View.VISIBLE else View.GONE
            setOnFocusChangeListener { _, focused -> background = theme.shape(theme.card, 12, if (focused) theme.accent else theme.border) }
        }
        roleEditor.addView(customRole, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val identityBackground = EditText(activity).apply {
            hint = "这个角色的经历、偏好或相处方式（选填）"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            gravity = Gravity.TOP or Gravity.START; minLines = 2; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(2000)); isSaveEnabled = false
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = theme.shape(theme.card, 12, theme.border)
            setText(composition.background.text)
        }
        roleEditor.addView(identityBackground, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        // 人物卡：读当前聊天记录让模型概括对方，一键填进名称与背景。只有微信进程能读到聊天记录，所以放在这里。
        var cardGenerating = false
        val cardButton = action("✨ 按名称与聊天记录生成背景", quiet = true)
        cardButton.textSize = 13f
        roleEditor.addView(cardButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val saveIdentityButton = action("保存角色")
        val instruction = EditText(activity).apply {
            hint = "这次想怎么回？如：委婉拒绝、别太正式（选填）"
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
        val topicButton = action("找话题")
        val shorter = action("更简短", quiet = true)
        val generationActions = LinearLayout(activity)
        listOf(saveIdentityButton, generateButton, topicButton).forEachIndexed { index, button ->
            button.textSize = 13f; button.setPadding(dp(4), dp(6), dp(4), dp(6))
            // Let large system fonts wrap instead of clipping an action or shrinking the touch target.
            generationActions.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index < 2) rightMargin = dp(6) })
        }
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
        val topicTitle = theme.label("", 14f, bold = true)
        results.addView(topicTitle)
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
        val resultActions = LinearLayout(activity)
        resultActions.addView(reasonToggle, LinearLayout.LayoutParams(0, -2, 1f))
        resultActions.addView(shorter, LinearLayout.LayoutParams(0, -2, 1f))
        results.addView(resultActions); results.addView(reason)
        scroll.addView(results); body.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        body.addView(line(), LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
        fun copyToClipboard(text: String) {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("狗头军师回复建议", text))
        }
        val copy = action("填入聊天框", primary = true) {
            composition.selectedText?.let { text ->
                if (fillHostEditor(text)) {
                    hide()
                    focusHostEditor()
                } else {
                    copyToClipboard(text)
                    toast("未找到聊天输入框，已复制第 ${composition.selectedPart + 1} 条，请手动粘贴")
                }
            }
        }
        copy.contentDescription = "把选中的建议填入聊天输入框，长按复制到剪贴板"
        copy.setOnLongClickListener {
            composition.selectedText?.let { text ->
                copyToClipboard(text)
                toast("已复制第 ${composition.selectedPart + 1} 条")
            }
            true
        }
        val footerActions = LinearLayout(activity).apply { setPadding(0, dp(10), 0, 0) }
        footerActions.addView(copy, LinearLayout.LayoutParams(-1, -2)); body.addView(footerActions)
        val footerHint = theme.label("点击后直接填入聊天框，长按可复制", 11f, theme.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        body.addView(footerHint)
        var busy = false
        var failed = false
        var findingTopics = false
        var identitySaving = false
        fun hasUnsavedIdentityBackground() = !group && composition.relationship != ReplyRelationship.UNSPECIFIED &&
            (identityBackground.text.toString() != composition.background.text ||
                composition.relationship != selectedIdentity.relationship ||
                composition.activeCustomRelationship != selectedIdentity.relationship.customValue(selectedIdentity.customText))
        fun topicKey(current: ReplyContext, draft: String, date: String) = TopicKey(current.fingerprint, current.requestedMessages,
            composition.relationship, instruction.text.toString(), draft, date, composition.activeCustomRelationship)
        fun preview(text: CharSequence, empty: String) = text.toString().trim().replace(Regex("\\s+"), " ").take(22).ifBlank { empty }
        fun collapseEditors() {
            roleExpanded = false; instructionExpanded = false
            if (customRole.hasFocus() || identityBackground.hasFocus() || instruction.hasFocus()) {
                body.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(body.windowToken, 0)
            }
        }
        fun controls(generatingRequest: Boolean) {
            val generating = generatingRequest || identitySaving
            val reading = reference.loading
            val ready = reference.context
            busy = generating
            progress.showLoading(when {
                identitySaving -> "正在保存角色…"
                generating && findingTopics -> "正在准备 5 个新话题…"
                generating -> "正在根据 ${ready?.messages?.size ?: 0} 条消息生成回复…"
                reading -> "正在读取最近 ${composition.historyLimit} 条消息…"
                else -> null
            })
            generateButton.isEnabled = !generating && !reading && composition.hasValidRelationship && !hasUnsavedIdentityBackground()
            generateButton.text = when {
                hasUnsavedIdentityBackground() && !generating -> "先保存"
                generating -> if (findingTopics) "生成回复" else "生成中…"
                reading -> "读取中…"
                !composition.hasValidRelationship -> "先填身份"
                ready == null -> "重新读取"
                failed -> "重试生成"
                ready.source == ReplyContextSource.LOADED_PAGE -> "生成回复"
                composition.result == null || composition.result?.topics != null -> "生成回复"
                !composition.canUse -> "重新生成"
                else -> "重新生成"
            }
            shorter.isEnabled = !generating && !reading && ready != null && composition.canUse && !hasUnsavedIdentityBackground()
            shorter.visibility = if (composition.result == null || composition.result?.topics != null) View.GONE else View.VISIBLE
            val batch = composition.result?.topics
            val currentTopicKey = ready?.let { topicKey(it.copy(background = composition.background), editor()?.text?.toString().orEmpty(), java.time.LocalDate.now().toString()) }
            val sameTopicInputs = composition.canUse && batch != null && batch.key == currentTopicKey
            topicButton.isEnabled = !generating && !reading && composition.hasValidRelationship && !hasUnsavedIdentityBackground()
            topicButton.text = when {
                generating && findingTopics -> "寻找中…"
                sameTopicInputs -> "换个话题"
                else -> "找话题"
            }
            topicHint.visibility = if (batch == null) View.GONE else View.VISIBLE
            topicHint.text = when {
                !sameTopicInputs -> "参考内容、对方背景或本次要求已变化，请生成新的一组"
                batch?.hasNext == true -> "本组还有 ${batch.items.size - batch.shownCount} 个，点「换个话题」立即切换"
                else -> "本组已看完，再点「换个话题」生成新的一组"
            }
            instruction.isEnabled = !generating; rolePicker.isEnabled = !generating; stale.isEnabled = !generating
            customRole.isEnabled = !generating
            customRole.visibility = if (composition.relationship == ReplyRelationship.OTHER) View.VISIBLE else View.GONE
            val editingIdentity = composition.relationship != ReplyRelationship.UNSPECIFIED && !group
            val hasRoleDetails = editingIdentity || composition.relationship == ReplyRelationship.OTHER
            roleToggle.visibility = if (hasRoleDetails) View.VISIBLE else View.GONE
            roleEditor.visibility = if (hasRoleDetails && roleExpanded) View.VISIBLE else View.GONE
            instruction.visibility = if (instructionExpanded) View.VISIBLE else View.GONE
            roleToggle.isEnabled = !generating; instructionToggle.isEnabled = !generating
            val roleSectionTitle = if (group) "角色名称" else "角色背景"
            roleToggle.text = "$roleSectionTitle${if (hasUnsavedIdentityBackground()) " · 未保存" else ""} ${if (roleExpanded) "▴" else "▾"}\n" +
                preview(if (group) customRole.text else identityBackground.text, "未填写，点此编辑")
            instructionToggle.text = "本次补充 ${if (instructionExpanded) "▴" else "▾"}\n" + preview(instruction.text, "可选，点此填写")
            roleToggle.contentDescription = "$roleSectionTitle，${if (roleExpanded) "已展开，点击收起" else "已收起，点击展开"}"
            instructionToggle.contentDescription = "本次补充，${if (instructionExpanded) "已展开，点击收起" else "已收起，点击展开"}"
            generateButton.contentDescription = "${generateButton.text}，已读取 ${ready?.messages?.size ?: 0} 条消息"
            identityBackground.visibility = if (editingIdentity) View.VISIBLE else View.GONE
            saveIdentityButton.visibility = if (!group && composition.relationship != ReplyRelationship.UNSPECIFIED) View.VISIBLE else View.GONE
            identityBackground.isEnabled = !generating
            saveIdentityButton.isEnabled = !generating && composition.hasValidRelationship
            saveIdentityButton.text = if (identitySaving) "保存中…" else "保存角色"
            rolePicker.text = roleLabel()
            rolePicker.contentDescription = "选择对方身份，当前${composition.relationship.displayLabel(composition.customRelationship)}"
            historyPicker.isEnabled = !generating
            historyPicker.text = "参考最近 ${composition.historyLimit} 条 ▾"
            historyPicker.contentDescription = "选择参考聊天消息条数，当前最近 ${composition.historyLimit} 条"
            copy.isEnabled = !generating && !reading && composition.canUse
            copy.text = "填入聊天框"
            footerActions.visibility = if (composition.result == null) View.GONE else View.VISIBLE
            footerHint.visibility = footerActions.visibility
            footerHint.text = if (!composition.canUse) "身份、背景或参考范围已改变，请重新生成"
                else "点击后直接填入聊天框，长按可复制"
            state.visibility = if (state.text.isBlank()) View.GONE else View.VISIBLE
            if (window.isShowing) fitWindow()
        }
        roleToggle.setOnClickListener {
            if (!ownsDrawer() || busy) return@setOnClickListener
            val expand = !roleExpanded
            collapseEditors(); roleExpanded = expand; controls(false)
        }
        instructionToggle.setOnClickListener {
            if (!ownsDrawer() || busy) return@setOnClickListener
            val expand = !instructionExpanded
            collapseEditors(); instructionExpanded = expand; controls(false)
        }
        fun revealReply() {
            collapseEditors(); controls(false)
            scroll.post { if (ownsDrawer()) scroll.smoothScrollTo(0, replyTitle.top) }
        }
        fun renderParts() {
            parts.removeAllViews()
            val result = composition.result
            replyTitle.visibility = if (result == null) View.GONE else View.VISIBLE
            val batch = result?.topics
            topicTitle.text = batch?.current?.title.orEmpty()
            topicTitle.visibility = if (batch == null) View.GONE else View.VISIBLE
            val kind = if (batch == null) "${result?.suggestion?.parts?.size ?: 0} 条建议" else "话题 ${batch.shownCount} / ${batch.items.size}"
            replyTitle.text = result?.let { "${it.relationship.displayLabel(it.customRelationship)} · $kind${if (!composition.canUse) "（上次结果）" else ""} · 查看依据 ›" }.orEmpty()
            result?.suggestion?.parts?.forEachIndexed { index, text ->
                val selected = composition.selectedPart == index
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(48)
                    background = theme.shape(if (selected) theme.soft else theme.card, 12, if (selected) theme.accent else theme.border)
                    addView(theme.label("${index + 1} / ${result.suggestion.parts.size}${if (selected) " · 已选" else " · 点选"}", 12f, theme.muted))
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
        var saveRevision = 0L
        fun saveIdentity() {
            if (group || !ownsDrawer()) return
            if (owner.key == null) { toast("账号或联系人尚未确认，本次身份不会保存"); return }
            val revision = ++saveRevision
            val value = ReplyIdentitySetting(composition.relationship, composition.customRelationship)
            // The immutable owner and original verified page remain attached even after closing/switching.
            ReplyIdentityBridge.save(activity, owner, value, { ReplyDatabaseHistory.replyAccount(live) }) { saved ->
                activity.runOnUiThread {
                    if (ownsDrawer() && revision == saveRevision && !saved) toast("身份保存失败，请重新选择后重试")
                }
            }
        }
        saveIdentityButton.setOnClickListener {
            if (!ownsDrawer() || busy || group) return@setOnClickListener
            if (!composition.hasValidRelationship) { customRole.requestFocus(); toast("请先填写对方身份"); return@setOnClickListener }
            if (owner.key == null) { toast("账号或联系人尚未确认，请重新打开后保存"); return@setOnClickListener }
            // 角色库已取消，不再带 roleId：带上它只会存一个指向不存在模板的悬空引用
            val identityValue = ReplyIdentitySetting(composition.relationship, composition.customRelationship)
            val backgroundText = identityBackground.text.toString()
            ++saveRevision
            savingRoleWindow = window
            identitySaving = true; controls(false)
            scope.launch {
                try {
                    check(verifyAccount()) { "当前聊天或账号已变化" }
                    val saved = ReplyIdentityBridge.saveRole(activity, owner, identityValue, backgroundText,
                        { ReplyDatabaseHistory.replyAccount(live) })
                    if (ownsDrawer()) {
                        selectedIdentity = saved.identity
                        composition.background = saved.background
                        collapseEditors()
                        renderParts(); toast("角色和背景已保存")
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (ownsDrawer()) toast("身份或背景保存失败，请重试") }
                finally {
                    if (savingRoleWindow === window) savingRoleWindow = null
                    identitySaving = false
                    if (ownsDrawer()) controls(false) else if (window === dialog) hide()
                }
            }
        }
        identityBackground.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (ownsDrawer()) controls(busy) }
        })
        customRole.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!ownsDrawer()) return
                composition.customRelationship = s?.toString().orEmpty()
                failed = false; renderParts(); controls(busy)
            }
        })
        val MENU_NEW = 0x1001
        val MENU_CLEAR = 0x1002
        val MENU_SKIP_PROFILE = 0x1003
        // 本次生成是否跳过画像。只在当前面板有效：关了对话框就回到默认的「参考画像」。
        var skipProfile = false

        fun showRoleMenu() {
            PopupMenu(activity, rolePicker).apply {
                // 只有两个状态：没卡就新建，有卡就清除。没有中间的「换一个模板」，
                // 因为角色卡和这个人是绑死的，画像也挂在同一条线上。
                val bound = composition.relationship != ReplyRelationship.UNSPECIFIED
                if (bound) menu.add(0, MENU_CLEAR, 0, "清除角色卡")
                else menu.add(0, MENU_NEW, 0, "新建角色卡")
                menu.add(1, MENU_SKIP_PROFILE, 1, "这次不用画像").apply {
                    isCheckable = true
                    isChecked = skipProfile
                }
                setOnMenuItemClickListener { item ->
                    if (busy || !ownsDrawer()) return@setOnMenuItemClickListener true
                    when (item.itemId) {
                        MENU_NEW -> {
                            composition.relationship = ReplyRelationship.OTHER
                            selectedIdentity = ReplyIdentitySetting(composition.relationship, composition.customRelationship)
                            roleExpanded = true
                            instructionExpanded = false
                            failed = false
                            renderParts(); controls(false)
                            customRole.requestFocus(); customRole.setSelection(customRole.text.length)
                            val keyboard = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            customRole.post {
                                if (window === dialog && customRole.isShown && customRole.hasFocus())
                                    keyboard?.showSoftInput(customRole, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                        MENU_CLEAR -> {
                            scope.launch {
                                try {
                                    if (!verifyAccount()) return@launch
                                    ReplyIdentityBridge.clearRole(activity, owner, { ReplyDatabaseHistory.replyAccount(live) })
                                    if (!ownsDrawer()) return@launch
                                    composition.relationship = ReplyRelationship.UNSPECIFIED
                                    composition.customRelationship = ""
                                    composition.background = ContactBackground()
                                    selectedIdentity = ReplyIdentitySetting()
                                    customRole.setText(""); identityBackground.setText("")
                                    roleExpanded = false; instructionExpanded = false
                                    failed = false
                                    renderParts(); controls(false)
                                    toast("角色卡已清除")
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { if (ownsDrawer()) toast("清除失败，请重试") }
                            }
                        }
                        MENU_SKIP_PROFILE -> {
                            skipProfile = !skipProfile
                            toast(if (skipProfile) "这次生成不参考画像" else "已恢复参考画像")
                        }
                    }
                    true
                }
            }.show()
        }
        rolePicker.setOnClickListener {
            if (!busy && ownsDrawer()) showRoleMenu()
        }
        invalidateRequest = { reference.cancel(); state.text = "请重新配置回复模型"; failed = true; controls(false) }
        fun remember() {
            if (ownsDrawer()) composition.result?.let { history.remember(it, owner.historyScope) }
        }
        fun prepareHistory(limit: Int, then: (() -> Unit)? = null) {
            if (busy) return
            if (!ownsDrawer()) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            historyJob?.cancel()
            NativeVoiceBridge.retryFailures()
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
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    val pending = ReplyDatabaseHistory.load(loaded, limit)
                    val voiceCount = pending.messages.count { it.voice != null }
                    var voiceIndex = 0
                    val current = VoicePreparation.reply(pending) { source ->
                        ensureActive()
                        state.text = "正在转写语音 ${++voiceIndex}/$voiceCount…"
                        NativeVoiceBridge.transcribe(source) {
                            ownsDrawer() && ModulePrefs.canGenerateReply && reference.isCurrent(ticket)
                        }
                    }
                    ensureActive()
                    if (!ownsDrawer() || !ModulePrefs.canGenerateReply) return@launch
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    if (current.messages.isEmpty() || current.messages.all { it.voiceState == VoiceState.FAILED }) {
                        reference.fail(ticket)
                        state.text = "尚无可用正文，语音可能未能转写，请在条数菜单中重试"
                        return@launch
                    }
                    if (!reference.complete(ticket, current, MessageSniffer.currentReplyTalker())) return@launch
                    snapshot = current
                    state.text = when {
                        current.messages.any { it.voiceState == VoiceState.FAILED } ->
                            "${current.messages.count { it.voiceState == VoiceState.FAILED }} 条语音未能转写，将明确标注缺失；可在条数菜单中重试。"
                        current.historyFailure != null -> "仅读取到页面 ${current.messages.size} 条。历史暂不可用，可切换条数重试。"
                        current.messages.size < limit -> "本次读取到 ${current.messages.size} 条消息（含语音转写），将按实际内容回复。"
                        else -> ""
                    }
                    updateNotice()
                    then?.invoke()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (reference.isCurrent(ticket) && ownsDrawer()) state.text = "历史读取失败，请重试"
                } finally {
                    if (reference.isCurrent(ticket) && ownsDrawer()) { reference.fail(ticket); controls(busy) }
                }
            }
        }
        var limitSaveRevision = 0L
        var limitDialog: AlertDialog? = null
        fun selectHistory(limit: Int) {
            if (busy || !ownsDrawer()) return
            composition.historyLimit = limit
            historyJob?.cancel()
            reference.cancel(); snapshot = null; stale.visibility = View.GONE
            failed = false; state.text = ""; renderParts(); controls(false)
            val revision = ++limitSaveRevision
            if (owner.account == null) toast("账号尚未确认，本次条数不会保存")
            else ReplyLimitBridge.save(activity, owner, limit, { ReplyDatabaseHistory.replyAccount(live) }) { saved ->
                activity.runOnUiThread {
                    if (ownsDrawer() && revision == limitSaveRevision && !saved) toast("条数保存失败，请重新选择后重试")
                }
            }
            prepareHistory(limit)
        }
        fun customHistory() {
            if (busy || !ownsDrawer()) return
            val input = EditText(activity).apply {
                // Numeric keyboard, but preserve pasted decimals/signs so validation rejects them,
                // rather than silently turning e.g. 1.5 into 15 through DigitsKeyListener.
                inputType = InputType.TYPE_CLASS_TEXT
                setRawInputType(InputType.TYPE_CLASS_NUMBER)
                setSingleLine(true); isSaveEnabled = false
                hint = "1～100"; contentDescription = "自定义参考消息条数，1 到 100"
                setText(composition.historyLimit.toString()); selectAll()
            }
            limitDialog?.dismiss()
            val chooser = AlertDialog.Builder(activity).setTitle("自定义参考条数")
                .setView(input).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
            limitDialog = chooser
            chooser.setOnDismissListener { if (limitDialog === chooser) limitDialog = null }
            chooser.show()
            chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!ownsDrawer() || busy) { chooser.dismiss(); return@setOnClickListener }
                val value = try { requireNotNull(ReplyHistoryLimit.parse(input.text.toString())) }
                    catch (error: IllegalArgumentException) { input.error = error.message; return@setOnClickListener }
                selectHistory(value)
                chooser.dismiss()
            }
            input.requestFocus()
            chooser.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        historyPicker.setOnClickListener {
            PopupMenu(activity, historyPicker).apply {
                ReplyHistorySelection.OPTIONS.forEach { limit ->
                    menu.add(0, limit, limit, "最近 $limit 条消息").isChecked = limit == composition.historyLimit
                }
                menu.add(0, -1, 101, "自定义…").isChecked = composition.historyLimit !in ReplyHistorySelection.OPTIONS
                menu.setGroupCheckable(0, true, true)
                reference.context?.let { menu.add(1, 1, 102, "查看已读取的 ${it.messages.size} 条消息") }
                menu.add(1, 2, 103, "重新读取")
                setOnMenuItemClickListener { item ->
                    if (ownsDrawer() && !busy) when {
                        item.groupId == 1 && item.itemId == 1 -> reference.context?.let(::showEvidence)
                        item.groupId == 1 && item.itemId == 2 -> prepareHistory(composition.historyLimit)
                        item.itemId == -1 -> customHistory()
                        else -> selectHistory(item.itemId)
                    }
                    true
                }
            }.show()
        }
        fun generate(direction: String = "", findTopics: Boolean = false, afterRefresh: Boolean = false) {
            if (busy || reference.loading) return
            if (hasUnsavedIdentityBackground()) { toast("请先保存身份和背景"); return }
            if (!composition.hasValidRelationship) { customRole.requestFocus(); toast("请先填写对方身份"); return }
            if (!ownsDrawer()) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            val current = reference.context?.copy(background = composition.background) ?: run { prepareHistory(composition.historyLimit); return }
            val latest = MessageSniffer.replyBoundary()
            if (latest != null && latest != current.latestLoadedId && current.messages.none { it.id == latest }) {
                // 聊天来了新消息：先把最新消息读进来，读完后自动继续生成，不需要用户再点一次。
                if (afterRefresh) { state.text = "聊天仍在更新，请稍后再试"; controls(false); return }
                prepareHistory(composition.historyLimit) { generate(direction, findTopics, afterRefresh = true) }
                return
            }
            val input = editor() ?: run { toast("请先切换到文字输入"); return }
            // Read existing draft for context only. Never write to the host's chat editor.
            val baselineDraft = input.text.toString()
            val relationship = composition.relationship
            val customRelationship = composition.activeCustomRelationship
            val notes = instruction.text.toString()
            val time = if (findTopics) TopicCalendar.current() else null
            val requestedTopicKey = time?.let { topicKey(current, baselineDraft, it.date) }
            if (requestedTopicKey != null && composition.nextTopic(requestedTopicKey)) {
                failed = false; state.text = ""; reason.visibility = View.GONE
                reasonToggle.text = "为什么这样回 ▾"
                renderParts(); revealReply(); remember(); return
            }
            job?.cancel()
            val config = ModulePrefs.replySettings()
            val ticket = session.begin(current.talker, current.fingerprint)
            val previous = if (composition.result?.topics == null) composition.previousText else ""
            val previousTopics = composition.result?.takeIf { composition.canUse }?.topics?.items.orEmpty()
            state.text = ""; state.setTextColor(theme.muted)
            failed = false; findingTopics = findTopics
            collapseEditors()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(instruction.windowToken, 0)
            body.requestFocus()
            controls(true)
            job = scope.launch {
                try {
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    val knowledge = withContext(Dispatchers.IO) { ReplyKnowledge.load(activity, relationship) }
                    // Upload only the prepared evidence, after rechecking configuration, conversation and settings.
                    ensureActive()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || !ownsDrawer() || !ModulePrefs.canGenerateReply || current.background != composition.background) return@launch
                    val beforeSend = ModulePrefs.replySettings()
                    if (beforeSend.endpoint != config.endpoint || beforeSend.model != config.model || beforeSend.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    // 模型该读画像：角色卡背景只是它的第一版，之后人物档案会持续补充。
                    // 单独取一份而不动 composition.background——那个值还负责「未保存」判断。
                    val profile = withContext(Dispatchers.IO) {
                        ReplyIdentityBridge.loadProfile(activity, current.talker)
                    }
                    // 用户勾了「本次不用画像」就按没画像走：只拿聊天记录，不注入人物背景。
                    val enriched = if (skipProfile || profile.isBlank()) current
                        else current.copy(background = ContactBackground(
                            profile.take(ContactBackground.MAX_LENGTH), current.background.revision))
                    val client = ReplyHttpClient()
                    val topics = if (findTopics) client.findTopics(config, enriched, baselineDraft, notes, knowledge, relationship,
                        requireNotNull(time), previousTopics, customRelationship) else null
                    val suggestion = if (findTopics) null else client.generate(config, enriched, baselineDraft, direction, knowledge, previous, focusId, relationship, customRelationship)
                    val activeConfig = ModulePrefs.replySettings()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || !ownsDrawer() || !ModulePrefs.canGenerateReply) return@launch
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    if (activeConfig.endpoint != config.endpoint || activeConfig.model != config.model || activeConfig.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    val accepted = if (topics != null) composition.acceptTopics(current, topics, requireNotNull(requestedTopicKey), focusId)
                        else composition.accept(current, requireNotNull(suggestion), notes, focusId, relationship, customRelationship)
                    if (!accepted) return@launch
                    snapshot = current
                    reason.visibility = View.GONE; reasonToggle.text = "为什么这样回 ▾"; renderParts()
                    state.text = ""
                    revealReply()
                    remember(); updateNotice()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (session.accepts(ticket, MessageSniffer.currentReplyTalker()) && ownsDrawer()) {
                        failed = true
                        state.text = "${if (findTopics) "找话题" else "生成"}失败${if (composition.result == null) "" else " · 保留上次建议"}"
                        state.setTextColor(theme.error)
                        toast(e.message ?: "暂时无法生成，请重试")
                    }
                } finally {
                    if (session.accepts(ticket, talker) && ownsDrawer()) controls(false)
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
        cardButton.setOnClickListener {
            if (cardGenerating || busy || !ownsDrawer()) return@setOnClickListener
            // 名称由用户先定，模型只补背景，所以这两步必须用户先完成。
            if (composition.relationship != ReplyRelationship.OTHER) {
                toast("请先选择「新建身份」并填写角色名称")
                return@setOnClickListener
            }
            val roleName = customRole.text.toString().trim()
            if (roleName.isBlank()) {
                customRole.requestFocus()
                toast("请先填写角色名称，再生成背景")
                return@setOnClickListener
            }
            val cardConfig = ModulePrefs.replySettings()
            if (!cardConfig.isConfigured) { toast("请先在狗头军师里配置回复模型"); return@setOnClickListener }
            cardGenerating = true
            cardButton.isEnabled = false
            cardButton.text = "正在读取聊天记录…"
            scope.launch {
                try {
                    val current = withContext(Dispatchers.IO) { MessageSniffer.replyContext(requireBottom = false) }
                        ?: run { toast("读不到当前聊天记录，请稍后再试"); return@launch }
                    if (current.messages.count { it.text.isNotBlank() } < 3) {
                        toast("这个聊天还没有足够的文字记录"); return@launch
                    }
                    cardButton.text = "正在生成角色背景…"
                    val background = withContext(Dispatchers.IO) {
                        ReplyHttpClient().describeContact(cardConfig, current, roleName)
                    }
                    if (!ownsDrawer()) return@launch
                    identityBackground.setText(background)
                    roleExpanded = true
                    controls(false)
                    toast("背景已填入，可修改后点「保存角色」")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(e.message ?: "生成失败，请重试") }
                finally {
                    cardGenerating = false
                    cardButton.isEnabled = true
                    cardButton.text = "✨ 按名称与聊天记录生成背景"
                }
            }
        }
        stale.setOnClickListener { prepareHistory(composition.historyLimit) }
        renderParts(); controls(false)
        window.setContentView(body)
        window.setOnDismissListener {
            limitDialog?.dismiss()
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
        val readyToGenerate = autoGenerate && canGenerate()
        if (reference.context == null && canGenerate()) prepareHistory(composition.historyLimit) { if (readyToGenerate) generate() }
        else if (readyToGenerate) generate()
        return true
    }
    private fun canGenerate() = ModulePrefs.canGenerateReply
    private fun configure() {
        AlertDialog.Builder(activity).setTitle("先连接回复模型")
            .setMessage("打开狗头军师「回复」，进入模型配置，保存回复模型后即可手动生成。")
            .setPositiveButton("去配置") { _, _ -> openSettings() }.setNegativeButton("稍后", null).show()
    }
    private fun contextSummary(context: ReplyContext): String {
        val first = context.messages.firstOrNull()?.time ?: 0
        val last = context.messages.lastOrNull()?.time ?: 0
        fun time(value: Long) = if (value <= 0) "时间未知" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(value))
        val voices = context.messages.count { it.voiceState == VoiceState.READY }
        val missing = context.messages.count { it.voiceState == VoiceState.FAILED }
        return "${time(first)} — ${time(last)} · 含 $voices 条语音转写${if (missing > 0) " · $missing 条转写失败" else ""}${if (context.trimmed) " · 已截取" else ""}" +
            (context.historyFailure?.let { "\n$it" } ?: "")
    }
    private fun showEvidence(context: ReplyContext) {
        val source = if (context.source == ReplyContextSource.LOCAL_HISTORY) "本机聊天历史 · 选择最近 ${context.requestedMessages} 条，实际 ${context.messages.size} 条文字或语音"
            else "仅页面已加载片段 · 跳过 ${context.omittedMedia} 条其他媒体"
        val info = "$source\n${contextSummary(context)}\n\n" +
            context.messages.joinToString("\n\n") { "${it.speaker} · ${ReplyProtocol.formatTime(it.time)}${if (it.voice != null) " · 语音转写" else ""}\n${it.text}" }
        AlertDialog.Builder(activity).setTitle("本次参考的聊天").setMessage(info).setPositiveButton("关闭", null).show()
    }
    private fun openSettings() = runCatching {
        activity.startActivity(Intent().setComponent(ComponentName("com.dogheadstrategist.chat", "com.dogheadstrategist.chat.MainActivity"))
            .putExtra("reply_tab", true))
    }.onFailure { toast("请从桌面打开狗头军师，进入回复建议") }
    fun hide() {
        plusEntry.clear()
        openingJob?.cancel(); ownerEpoch = null
        dialog?.dismiss(); job?.cancel(); historyJob?.cancel(); session.cancel()
        talker = null
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
