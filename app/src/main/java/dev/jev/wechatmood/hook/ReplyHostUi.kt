package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private var job: Job? = null
    private var talker: String? = null
    private var toolbar: LinearLayout? = null
    private var undoButton: TextView? = null
    private var dialog: Dialog? = null
    private var snapshot: ReplyContext? = null
    private var pendingDraft: DraftReplacement? = null
    private var draftView = WeakReference<EditText>(null)
    private var paddedList: View? = null
    private var originalPadding: IntArray? = null
    private var newMessageNotice: TextView? = null
    private var currentSuggestion: ReplySuggestion? = null
    private var focusId: Long? = null
    private val dark get() = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface get() = Color.parseColor(if (dark) "#17201E" else "#F3F6F4")
    private val ink get() = Color.parseColor(if (dark) "#E1EEE6" else "#182B24")
    private val muted get() = Color.parseColor(if (dark) "#A7BBB4" else "#53675F")
    private val accent get() = Color.parseColor(if (dark) "#8CD7C0" else "#20685C")

    fun update(currentTalker: String?) {
        if (talker != currentTalker) { hide(); talker = currentTalker }
        if (currentTalker == null) return
        ensureToolbar()
        if (dialog?.isShowing == true) {
            if (!ModulePrefs.replyConsent || !ModulePrefs.replySettings().isConfigured) { dialog?.dismiss(); return }
            val latest = MessageSniffer.replyBoundary()
            val captured = snapshot
            if (captured != null && latest != null && latest != captured.latestLoadedId && latest !in captured.messages.map { it.id }) {
                newMessageNotice?.visibility = View.VISIBLE
            }
        }
    }

    private fun footer(): View? = views(activity.window.decorView).singleOrNull {
        it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && it.isShown
    }
    private fun editor(): EditText? = footer()?.let { root ->
        views(root).filterIsInstance<EditText>().filter { it.isShown && it.isEnabled }.singleOrNull()
    }

    private fun ensureToolbar() {
        val footer = footer() ?: return
        val root = activity.window.decorView as? FrameLayout ?: return
        val rect = Rect()
        if (!footer.getGlobalVisibleRect(rect)) return
        val rootPosition = IntArray(2).also(root::getLocationOnScreen)
        val footerPosition = IntArray(2).also(footer::getLocationOnScreen)
        val bar = toolbar ?: LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0); setBackgroundColor(surface)
            addView(label("言外", 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            undoButton = action("撤销填入") { undoDraft() }.also {
                it.visibility = View.GONE; addView(it, LinearLayout.LayoutParams(-2, dp(48)))
            }
            addView(action("帮我回") { open() }, LinearLayout.LayoutParams(-2, dp(48)))
            root.addView(this, FrameLayout.LayoutParams(-1, dp(48)))
            toolbar = this
        }
        bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply {
            width = rect.width(); height = dp(48); gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = footerPosition[0] - rootPosition[0]
            topMargin = (footerPosition[1] - rootPosition[1] - dp(48)).coerceAtLeast(0)
        }
        val chatRoot = generateSequence(footer.parent as? View) { it.parent as? View }
            .firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") } ?: return
        val list = views(chatRoot).firstOrNull { it is ListView || isRecycler(it) }
        if (list != null && paddedList !== list) {
            restorePadding()
            val atBottom = !list.canScrollVertically(1)
            paddedList = list
            originalPadding = intArrayOf(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom)
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom + dp(48))
            if (atBottom) list.post {
                if (paddedList === list && MessageSniffer.currentReplyTalker() == talker) {
                    if (list is ListView) list.setSelection((list.count - 1).coerceAtLeast(0))
                    else runCatching { list.javaClass.getMethod("scrollBy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(list, 0, dp(48)) }
                }
            }
        }
    }

    fun open(focusMessageId: Long? = null): Boolean {
        if (MessageSniffer.currentReplyTalker() != talker || talker == null) return false
        if (dialog?.isShowing == true) return true
        if (!ModulePrefs.replyConsent || !ModulePrefs.replySettings().isConfigured) {
            AlertDialog.Builder(activity).setTitle("先连接回复模型")
                .setMessage("在言外「回复建议」中填写 OpenAI 兼容接口，并开启「允许手动生成回复建议」。所选聊天、发送人、时间和草稿会发给你配置的模型。")
                .setPositiveButton("去配置") { _, _ -> openSettings() }.setNegativeButton("稍后", null).show()
            return true
        }
        val captured = runCatching { MessageSniffer.replyContext() }.getOrElse { toast(it.message ?: "读取聊天失败"); return true }
        if (captured.talker != talker) return false
        val input = editor() ?: run { toast("请切换到文字输入，再点帮我回"); return true }
        focusId = focusMessageId
        snapshot = captured
        currentSuggestion = null
        val draft = input.text.toString()
        draftView = WeakReference(input)
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        val window = Dialog(activity)
        dialog = window
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(surface)
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("这次怎么回", 21f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("关闭") { window.dismiss() })
        body.addView(heading)
        body.addView(label("言外 ${BuildConfig.VERSION_NAME} · 回复逻辑来自狗头军师", 12f, muted))
        val range = action(rangeText(captured)) { showEvidence(snapshot ?: captured) }
        body.addView(range)
        val stale = action("有新消息，点此更新建议") { }
        stale.visibility = View.GONE
        newMessageNotice = stale
        body.addView(stale)
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val state = label("正在结合聊天和知识资料想一句合适的回复…", 14f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val answer = label("", 20f).apply { setTextIsSelectable(true); setPadding(0, dp(16), 0, dp(12)) }
        val reason = label("", 14f, muted).apply { visibility = View.GONE }
        val explanation = action("为什么这样回") { reason.visibility = if (reason.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        explanation.visibility = View.GONE
        val instruction = EditText(activity).apply {
            hint = "补充想法，例如：想拒绝，但别太冷淡"; textSize = 14f; setTextColor(ink); setHintTextColor(muted)
            maxLines = 3; minHeight = dp(48); setPadding(dp(8), dp(8), dp(8), dp(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000)); isSaveEnabled = false
        }
        listOf(state, answer, explanation, reason, instruction).forEach(results::addView)
        val actions = LinearLayout(activity)
        val another = action("换一句") { }
        val shorter = action("更简短") { }
        val supplement = action("按想法重写") { }
        listOf(another, shorter, supplement).forEach { actions.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        results.addView(actions)
        scroll.addView(results)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val use = action("填入输入框") { }
        use.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(accent) }
        use.setTextColor(if (dark) Color.parseColor("#12362D") else Color.parseColor("#F3F6F4"))
        val copy = action("复制回复") {
            currentSuggestion?.let { suggestion ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("言外回复建议", suggestion.text))
                toast("已复制，你可以修改后再发送")
            }
        }
        body.addView(use, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(copy)
        fun generate(direction: String = "") {
            if (!ModulePrefs.replyConsent || MessageSniffer.currentReplyTalker() != captured.talker) {
                window.dismiss(); return
            }
            job?.cancel()
            val current = snapshot ?: return
            val config = ModulePrefs.replySettings()
            val ticket = session.begin(current.talker, current.fingerprint)
            val previous = currentSuggestion?.text.orEmpty()
            currentSuggestion = null
            answer.text = ""; reason.visibility = View.GONE; explanation.visibility = View.GONE
            state.text = "正在生成，可随时关闭。不会自动发送。"
            use.isEnabled = false; copy.isEnabled = false
            listOf(another, shorter, supplement).forEach { it.isEnabled = false }
            job = scope.launch {
                try {
                    val knowledge = withContext(Dispatchers.IO) { ReplyKnowledge.load(activity) }
                    val result = ReplyHttpClient().generate(config, current, draft, direction, knowledge, previous, focusId)
                    val activeConfig = ModulePrefs.replySettings()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || window !== dialog || !ModulePrefs.replyConsent) return@launch
                    if (activeConfig.endpoint != config.endpoint || activeConfig.model != config.model || activeConfig.apiKey != config.apiKey) {
                        state.text = "回复配置已变化，请重新生成"; return@launch
                    }
                    currentSuggestion = result
                    answer.text = result.text; reason.text = result.reason
                    explanation.visibility = if (result.reason.isBlank()) View.GONE else View.VISIBLE
                    state.text = "建议回复 · 可修改后发送"
                    use.isEnabled = true; copy.isEnabled = true
                    another.text = "换一句"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (session.accepts(ticket, MessageSniffer.currentReplyTalker()) && window === dialog) {
                        state.text = e.message ?: "生成失败，请重试"; another.text = "重试"
                    }
                } finally {
                    if (session.accepts(ticket, talker)) listOf(another, shorter, supplement).forEach { it.isEnabled = true }
                }
            }
        }
        another.setOnClickListener { generate(instruction.text.toString().ifBlank { "换一种自然表达，不要重复上一条建议" }) }
        shorter.setOnClickListener { generate(instruction.text.toString() + "\n保持原意，更简短一点") }
        supplement.setOnClickListener { generate(instruction.text.toString()) }
        stale.setOnClickListener {
            runCatching { MessageSniffer.replyContext() }.onSuccess {
                if (it.talker == talker) {
                    snapshot = it; range.text = rangeText(it); stale.visibility = View.GONE
                    generate(instruction.text.toString())
                }
            }.onFailure { toast(it.message ?: "请回到聊天底部重试") }
        }
        use.setOnClickListener {
            val suggestion = currentSuggestion ?: return@setOnClickListener
            val editor = editor()
            if (MessageSniffer.currentReplyTalker() != captured.talker || editor == null || editor !== draftView.get()) {
                toast("聊天或输入框已变化，请重新生成"); window.dismiss(); return@setOnClickListener
            }
            if (editor.text.toString() != draft) {
                toast("草稿已经修改，请复制建议或重新打开，避免覆盖新内容"); return@setOnClickListener
            }
            pendingDraft = DraftReplacement(draft, suggestion.text)
            editor.setText(suggestion.text); editor.setSelection(editor.text.length)
            undoButton?.visibility = View.VISIBLE
            window.dismiss(); editor.requestFocus()
        }
        window.setContentView(body)
        window.setOnDismissListener {
            job?.cancel(); session.cancel()
            if (dialog === window) { dialog = null; newMessageNotice = null; snapshot = null; currentSuggestion = null }
        }
        window.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        window.show()
        window.window?.setLayout(-1, (activity.resources.displayMetrics.heightPixels * 0.66).toInt())
        generate()
        return true
    }

    private fun undoDraft() {
        val input = editor()
        if (input == null || input !== draftView.get() || MessageSniffer.currentReplyTalker() != talker) return
        val original = pendingDraft?.undo(input.text.toString())
        if (original == null) toast("内容已被修改或发送，不再覆盖")
        else { input.setText(original); input.setSelection(input.text.length); toast("已恢复原稿") }
        pendingDraft = null; undoButton?.visibility = View.GONE
    }
    private fun rangeText(context: ReplyContext): String = "参考 ${context.messages.size} 条文字 · 查看范围" + if (context.trimmed) "（部分截取）" else ""
    private fun showEvidence(context: ReplyContext) {
        val info = "仅含页面已加载片段；跳过 ${context.omittedMedia} 条非文字消息。\n\n" +
            context.messages.joinToString("\n\n") { "${it.speaker} · ${ReplyProtocol.formatTime(it.time)}\n${it.text}" }
        AlertDialog.Builder(activity).setTitle("本次参考的聊天").setMessage(info).setPositiveButton("关闭", null).show()
    }
    private fun openSettings() = runCatching {
        activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity"))
            .putExtra("reply_tab", true))
    }.onFailure { toast("请从桌面打开言外，进入回复建议") }
    fun hide() {
        dialog?.dismiss(); job?.cancel(); session.cancel()
        toolbar?.let { (it.parent as? ViewGroup)?.removeView(it) }; toolbar = null
        restorePadding(); pendingDraft = null; draftView.clear(); undoButton = null; talker = null
    }
    fun dispose() { hide(); scope.cancel() }
    private fun restorePadding() {
        originalPadding?.let { p -> paddedList?.setPadding(p[0], p[1], p[2], p[3]) }
        paddedList = null; originalPadding = null
    }
    private fun label(value: String, size: Float, color: Int = ink) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun action(value: String, onClick: () -> Unit) = label(value, 14f, accent).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(8), dp(4), dp(8), dp(4))
        isClickable = true; isFocusable = true; contentDescription = value
        setOnClickListener { runCatching(onClick).onFailure { MoodLog.w("REPLY_UI_FAILED ${it.javaClass.simpleName}"); toast("操作失败，请重新打开重试") } }
    }
    private fun views(root: View): List<View> = buildList {
        fun visit(v: View, depth: Int) {
            if (depth > 40 || size > 4000 || v.visibility != View.VISIBLE) return
            add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), depth + 1)
        }
        visit(root, 0)
    }
    private fun isRecycler(view: View) = generateSequence(view.javaClass as Class<*>) { it.superclass }.any { it.name.endsWith(".RecyclerView") }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
