package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.widget.doAfterTextChanged
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
    private var talker: String? = null
    private var dialog: Dialog? = null
    private var snapshot: ReplyContext? = null
    private var pendingDraft: DraftReplacement? = null
    private var undoView = WeakReference<EditText>(null)
    private var newMessageNotice: TextView? = null
    private var currentSuggestion: ReplySuggestion? = null
    private var invalidateRequest: (() -> Unit)? = null

    fun update(currentTalker: String?) {
        if (talker != currentTalker) { hide(); talker = currentTalker }
        if (currentTalker == null) return
        runCatching { plusEntry.update(footer()) }.onFailure { MoodLog.w("REPLY_PLUS_UPDATE_FAILED ${it.javaClass.simpleName}") }
        if (dialog?.isShowing == true) {
            if ((!ModulePrefs.replyConsent || !ModulePrefs.replySettings().isConfigured) && job?.isActive == true) {
                job?.cancel(); session.cancel(); invalidateRequest?.invoke()
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
        if (remembered == null && !canGenerate()) { configure(); return true }
        // Reopening saved content must not depend on scrolling to the latest message or text input mode.
        val captured = remembered?.context ?: runCatching { MessageSniffer.replyContext() }.getOrElse {
            toast(it.message ?: "读取聊天失败"); return true
        }
        if (captured.talker != selectedTalker) return false
        val initialEditor = editor()
        if (remembered == null && initialEditor == null) { toast("请先切换到文字输入"); return true }
        var baselineDraft = initialEditor?.text?.toString().orEmpty()
        var draftView = WeakReference(initialEditor)
        val focusId = remembered?.focusMessageId ?: focusMessageId
        snapshot = captured; currentSuggestion = remembered?.suggestion
        initialEditor?.let { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(it.windowToken, 0) }
        val theme = ReplyTheme(activity)
        fun dp(value: Int) = theme.dp(value)
        fun action(text: String, primary: Boolean = false, quiet: Boolean = false, click: () -> Unit = {}) =
            theme.action(text, primary, quiet) {
                runCatching(click).onFailure { MoodLog.w("REPLY_UI_FAILED ${it.javaClass.simpleName}"); toast("操作失败，请重试") }
            }
        fun gap(size: Int) = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }
        fun line() = View(activity).apply { setBackgroundColor(theme.border) }
        val window = Dialog(activity)
        dialog = window
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(12))
            background = theme.shape(theme.surface, 24); elevation = dp(12).toFloat(); isFocusableInTouchMode = true
        }
        body.addView(View(activity).apply { background = theme.shape(theme.border, 2) },
            LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(theme.label("帮我回", 20f, bold = true))
            addView(theme.label("言外 ${BuildConfig.VERSION_NAME} · 狗头军师", 11f, theme.muted))
        }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("关闭", quiet = true) { window.dismiss() }, LinearLayout.LayoutParams(dp(56), dp(48)))
        body.addView(heading)
        val scroll = ScrollView(activity).apply { isFillViewport = false; clipToPadding = false }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val metadata = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val state = theme.label(if (remembered == null) "准备生成" else "上次建议", 12f, theme.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        metadata.addView(state, LinearLayout.LayoutParams(0, -2, 1f))
        val range = action(rangeText(captured), quiet = true) { snapshot?.let(::showEvidence) }
        metadata.addView(range); results.addView(metadata)
        val stale = action("有新消息 · 更新建议")
        stale.visibility = View.GONE; newMessageNotice = stale
        results.addView(stale, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE; indeterminateTintList = ColorStateList.valueOf(theme.accent)
        }
        results.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { bottomMargin = dp(6) })
        val replyCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = theme.shape(theme.card, 16, theme.border); elevation = dp(2).toFloat()
        }
        replyCard.addView(theme.label("建议回复", 12f, theme.accent, true))
        val answer = theme.label(remembered?.suggestion?.text ?: "正在想一句合适的回复…", 18f).apply {
            setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8))
        }
        replyCard.addView(answer)
        val reasonBlock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        reasonBlock.addView(line(), LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)).apply { topMargin = dp(4); bottomMargin = dp(10) })
        reasonBlock.addView(theme.label("为什么这样回", 12f, theme.muted, true))
        val reason = theme.label(remembered?.suggestion?.reason.orEmpty(), 13f, theme.muted).apply { setPadding(0, dp(4), 0, 0) }
        reasonBlock.addView(reason); reasonBlock.visibility = if (reason.text.isBlank()) View.GONE else View.VISIBLE
        replyCard.addView(reasonBlock)
        results.addView(replyCard, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(2); rightMargin = dp(2) })
        results.addView(gap(10))
        val another = action("换一句")
        val shorter = action("更简短")
        val rewrites = LinearLayout(activity)
        rewrites.addView(another, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        rewrites.addView(shorter, LinearLayout.LayoutParams(0, dp(48), 1f)); results.addView(rewrites)
        val instruction = EditText(activity).apply {
            hint = "例如：委婉拒绝，留个下次见面的机会"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            gravity = Gravity.TOP or Gravity.START; minLines = 2; maxLines = 4
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = theme.shape(theme.card, 12, theme.border)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(2000)); isSaveEnabled = false
            setText(remembered?.direction.orEmpty())
            setOnFocusChangeListener { _, focused -> background = theme.shape(theme.card, 12, if (focused) theme.accent else theme.border) }
        }
        val notesHeading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        notesHeading.addView(theme.label("补充想法", 13f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val supplement = action("按想法重写", quiet = true)
        notesHeading.addView(supplement)
        results.addView(gap(4)); results.addView(notesHeading); results.addView(instruction, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(results); body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(line(), LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
        val undo = action("撤销上次填入", quiet = true)
        undo.visibility = if (pendingDraft == null) View.GONE else View.VISIBLE; body.addView(undo)
        val use = action("填入输入框", primary = true)
        val copy = action("复制") {
            currentSuggestion?.let { suggestion ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("言外回复建议", suggestion.text))
                toast("已复制")
            }
        }
        val footerActions = LinearLayout(activity).apply { setPadding(0, dp(10), 0, 0) }
        footerActions.addView(copy, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
        footerActions.addView(use, LinearLayout.LayoutParams(0, dp(48), 2f)); body.addView(footerActions)
        body.addView(theme.label("填入后由你发送", 11f, theme.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) })
        var busy = false
        fun controls(generating: Boolean) {
            busy = generating; progress.visibility = if (generating) View.VISIBLE else View.GONE
            another.isEnabled = !generating; shorter.isEnabled = !generating && currentSuggestion != null
            supplement.isEnabled = !generating && instruction.text.toString().isNotBlank()
            instruction.isEnabled = !generating; stale.isEnabled = !generating
            copy.isEnabled = currentSuggestion != null; use.isEnabled = currentSuggestion != null
        }
        instruction.doAfterTextChanged { supplement.isEnabled = !busy && it.toString().isNotBlank() }
        invalidateRequest = { state.text = "请重新配置回复模型"; controls(false) }
        fun remember() {
            val successful = currentSuggestion ?: return
            val evidence = snapshot ?: return
            history.remember(RememberedReply(evidence, successful, instruction.text.toString(), focusId))
        }
        fun generate(direction: String = "") {
            if (MessageSniffer.currentReplyTalker() != selectedTalker) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            val current = runCatching { MessageSniffer.replyContext() }.getOrElse { toast(it.message ?: "读取聊天失败"); return }
            if (current.talker != selectedTalker) { window.dismiss(); return }
            val input = editor() ?: run { toast("请先切换到文字输入"); return }
            baselineDraft = input.text.toString(); draftView = WeakReference(input)
            job?.cancel()
            val config = ModulePrefs.replySettings()
            val ticket = session.begin(current.talker, current.fingerprint)
            val previous = currentSuggestion?.text.orEmpty()
            state.text = "正在生成…"; state.setTextColor(theme.muted)
            if (currentSuggestion == null) answer.text = "正在想一句合适的回复…"
            controls(true)
            job = scope.launch {
                try {
                    val knowledge = withContext(Dispatchers.IO) { ReplyKnowledge.load(activity) }
                    val suggestion = ReplyHttpClient().generate(config, current, baselineDraft, direction, knowledge, previous, focusId)
                    val activeConfig = ModulePrefs.replySettings()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || window !== dialog || !ModulePrefs.replyConsent) return@launch
                    if (activeConfig.endpoint != config.endpoint || activeConfig.model != config.model || activeConfig.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    currentSuggestion = suggestion; snapshot = current
                    answer.text = suggestion.text; reason.text = suggestion.reason
                    reasonBlock.visibility = if (suggestion.reason.isBlank()) View.GONE else View.VISIBLE
                    range.text = rangeText(current); state.text = "已生成"; another.text = "换一句"
                    remember(); updateNotice()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (session.accepts(ticket, MessageSniffer.currentReplyTalker()) && window === dialog) {
                        state.text = "生成失败"; state.setTextColor(theme.error); another.text = "重试"
                        if (currentSuggestion == null) answer.text = e.message ?: "暂时无法生成，请重试"
                        else toast(e.message ?: "生成失败，已保留上次建议")
                    }
                } finally {
                    if (session.accepts(ticket, talker) && window === dialog) controls(false)
                }
            }
        }
        another.setOnClickListener { generate(instruction.text.toString().ifBlank { "换一种自然表达，不要重复上一条建议" }) }
        shorter.setOnClickListener { generate(instruction.text.toString() + "\n保持原意，更简短一点") }
        supplement.setOnClickListener { if (instruction.text.toString().isNotBlank()) generate(instruction.text.toString()) }
        stale.setOnClickListener { generate(instruction.text.toString()) }
        undo.setOnClickListener {
            undoDraft()?.let { original -> baselineDraft = original; draftView = WeakReference(editor()) }
            undo.visibility = View.GONE
        }
        use.setOnClickListener {
            val suggestion = currentSuggestion ?: return@setOnClickListener
            val input = editor()
            if (MessageSniffer.currentReplyTalker() != selectedTalker || input == null || input !== draftView.get()) {
                toast("输入框已变化，请复制建议，或重新打开后填入"); return@setOnClickListener
            }
            if (input.text.toString() != baselineDraft) { toast("草稿已修改，请复制建议，避免覆盖新内容"); return@setOnClickListener }
            // Reopening and filling the same suggestion must not replace the original undo record.
            if (input.text.toString() == suggestion.text) { window.dismiss(); return@setOnClickListener }
            pendingDraft = DraftReplacement(baselineDraft, suggestion.text); undoView = WeakReference(input)
            input.setText(suggestion.text); input.setSelection(input.text.length)
            window.dismiss(); input.requestFocus()
        }
        controls(false)
        window.setContentView(body)
        window.setOnDismissListener {
            if (dialog !== window) return@setOnDismissListener
            remember(); job?.cancel(); session.cancel()
            if (dialog === window) { dialog = null; newMessageNotice = null; snapshot = null; currentSuggestion = null; invalidateRequest = null }
        }
        window.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.32f)
        }
        window.show()
        val available = activity.window.decorView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        window.window?.setLayout(-1, minOf(dp(600), (available * 0.78f).toInt()))
        body.requestFocus(); updateNotice()
        if (remembered == null) generate()
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
        pendingDraft = null; undoView.clear()
        return original
    }
    private fun rangeText(context: ReplyContext) = "${context.messages.size} 条上下文  ›"
    private fun showEvidence(context: ReplyContext) {
        val info = "页面已加载片段${if (context.trimmed) "（部分截取）" else ""} · 跳过 ${context.omittedMedia} 条非文字\n\n" +
            context.messages.joinToString("\n\n") { "${it.speaker} · ${ReplyProtocol.formatTime(it.time)}\n${it.text}" }
        AlertDialog.Builder(activity).setTitle("本次参考的聊天").setMessage(info).setPositiveButton("关闭", null).show()
    }
    private fun openSettings() = runCatching {
        activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity"))
            .putExtra("reply_tab", true))
    }.onFailure { toast("请从桌面打开言外，进入回复建议") }
    fun hide() {
        dialog?.dismiss(); job?.cancel(); session.cancel()
        plusEntry.clear(); pendingDraft = null; undoView.clear(); talker = null
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
