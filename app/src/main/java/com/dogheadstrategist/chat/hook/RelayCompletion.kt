package com.dogheadstrategist.chat.hook

import com.dogheadstrategist.chat.sentry.WarReviewFingerprint

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.RelayPrefs
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * 续话：在微信输入框里把预测内容画成灰色内联文字，点一下填入。
 *
 * 关键取舍：**一个字节都不改输入框的文本**。
 * 之前的方案往 EditText 的 Editable 里 append 灰字，会和输入法 composing 区、微信自己的
 * TextWatcher（发送按钮、高度重算）打架。这里改为 hook onDraw，在文字末尾之后用
 * 灰色画笔补画一段，微信与输入法完全不知情，点中才通过 commitText 真正写入。
 *
 * 延迟控制（参考 Continue 等补全客户端的做法，避免逐键请求）：
 * 1. 防抖：停止输入 [RelayConfig.DEBOUNCE_MS] 毫秒后才请求，期间新输入直接取消旧任务；
 * 2. 前缀裁剪：用户接着打的字若正好是建议的开头，就裁掉已输入部分继续显示，**不请求**；
 * 3. 前缀缓存：按「会话 + 最近消息 id + 前缀」缓存建议，同一前缀第二轮输入直接命中；
 * 4. 光标不在文末、前缀过短、未配置模型都直接跳过，不发请求。
 */
internal object RelayCompletion {
    private const val FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    private val editors = Collections.synchronizedMap(WeakHashMap<EditText, RelayEditor>())

    fun install(loader: ClassLoader) = runCatching {
        val textView = XposedHelpers.findClass("android.widget.TextView", loader)
        XposedBridge.hookAllMethods(textView, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val box = param.thisObject as? EditText ?: return
                if (!isInFooter(box)) return
                attach(box)
            }
        })
        XposedBridge.hookAllMethods(textView, "onDetachedFromWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val box = param.thisObject as? EditText ?: return
                editors.remove(box)?.detach()
            }
        })
        // 绘制：EditText 自己画完文字后，紧接着补画灰色建议。
        XposedBridge.hookAllMethods(textView, "onDraw", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val box = param.thisObject as? EditText ?: return
                val editor = runCatching { editors[box] }.getOrNull() ?: return
                val canvas = param.args.firstOrNull() as? Canvas ?: return
                editor.draw(canvas)
            }
        })
        // 点击：命中建议区域才填入，其余触摸原样放过。
        val touchHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val box = param.thisObject as? EditText ?: return
                val editor = runCatching { editors[box] }.getOrNull() ?: return
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                if (editor.handleTouch(event)) param.result = true
            }
        }
        val seen = HashSet<java.lang.reflect.Method>()
        for (type in listOf(EditText::class.java, android.widget.TextView::class.java, View::class.java)) {
            runCatching {
                val method = XposedHelpers.findMethodExact(type, "onTouchEvent", MotionEvent::class.java)
                if (seen.add(method)) XposedBridge.hookMethod(method, touchHook)
            }
        }
        MoodLog.i("RELAY_HOOK_READY 输入框内联补全已连接")
    }.onFailure { MoodLog.e("RELAY_HOOK_FAILED", it) }

    private fun isInFooter(view: View): Boolean {
        var parent: ViewParent? = view.parent
        var depth = 0
        while (parent != null && depth++ < 24) {
            if (parent is View && parent.javaClass.name == FOOTER) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * 挂监听时不强依赖开关：设置快照可能还没同步到微信进程，
     * 真正确认开关放在每次预测前，这样用户中途打开开关也不用重启微信。
     */
    private fun attach(box: EditText) {
        if (editors.containsKey(box)) return
        val context = box.context ?: return
        editors[box] = RelayEditor(box).also { it.start() }
        MoodLog.i("RELAY_EDITOR_ATTACHED switch=${RelayPrefs.enabled(context)}")
    }
}

/** 单个输入框的补全状态：防抖、缓存、前缀裁剪、内联绘制、点击填入。 */
private class RelayEditor(private val box: EditText) {
    /** [base] 是生成建议时的完整前缀，[text] 是建议本身（可被裁剪）。 */
    private data class Suggestion(val base: String, val text: String)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RelayConfig.GRAY }
    private var pending: Job? = null
    private var suggestion: Suggestion? = null
    private var applying = false
    private var revision = 0
    private var loggedDisabled = false
    private var ghostLines = 0
    /** 正在等模型返回：此时不画建议，改画动态点，光标也会换成高亮色。 */
    private var generating = false
    /** 点的数量 1~3，循环切换。 */
    private var dotPhase = 1
    private var dotJob: Job? = null
    /** 候选池对应的输入前缀。 */
    private var poolBase = ""
    /** 已生成的候选（不含 poolBase 部分），供后续输入做本地前缀匹配。 */
    private var pool: List<String> = emptyList()
    /** 输入框原始最小高度，撑高后要能恢复。 */
    private var baseMinHeight = -1
    /** 建议区域内按下后，整段手势都由我们消费，EditText 收不到 DOWN 就不会启动长按选择。 */
    private var touching = false

    private fun dp(value: Int) = (value * box.resources.displayMetrics.density).toInt()

    private companion object {
        /** 点数量切换间隔：一个→两个→三个→一个。 */
        const val DOT_INTERVAL_MS = 420L
    }

    /** 按前缀缓存候选池。每个池子有几十条，容量必须很小，否则内存吃不住。 */
    private val cache = object : LinkedHashMap<String, List<String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 8
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (applying) return
            onUserInput()
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    fun start() {
        if (baseMinHeight < 0) baseMinHeight = box.minHeight
        box.addTextChangedListener(watcher)
    }

    fun detach() {
        touching = false
        resetGhostHeight()
        pending?.cancel()
        stopGenerating()   // 生成中被卸载：点要收、光标要还原，否则会停在彩色状态
        runCatching { box.removeTextChangedListener(watcher) }
        if (suggestion != null) {
            suggestion = null
            runCatching { box.invalidate() }
        }
        scope.cancel()
    }

    /**
     * 用户每敲一下都会走到这里。优先尝试"接着用旧建议"，不行才排一次防抖请求。
     */
    private fun onUserInput() {
        val text = box.text?.toString().orEmpty().trimEnd()
        // 先花 O(候选数) 做本地匹配：命中就直接画，不发请求、没有延迟。
        if (tryLocalMatch(text)) return
        if (suggestion != null) {
            suggestion = null
            resetGhostHeight()
            runCatching { box.invalidate() }
        }
        schedulePredict()
    }

    /** 用户还在同一个前缀上继续输入时，直接从候选池里挑一条接着显示。 */
    private fun tryLocalMatch(text: String): Boolean {
        if (poolBase.isEmpty() || text.length < poolBase.length || !text.startsWith(poolBase)) {
            if (poolBase.isNotEmpty()) { poolBase = ""; pool = emptyList() }
            return false
        }
        val typed = text.substring(poolBase.length)
        // 候选开头可能是与前缀衔接的标点（如「，估计得忙一阵」）。用户继续打字时
        // 未必会补上那个逗号，若直接比对会永远匹配不上、白白退回网络请求。
        // 因此比较时两边都去掉前导标点，取剩余部分时再按原候选的长度差回推，标点位置不受影响。
        val typedBare = typed.trimStart(*LEADING_PUNCT)
        val index = pool.indexOfFirst { candidate ->
            val bare = candidate.trimStart(*LEADING_PUNCT)
            bare.length > typedBare.length && bare.startsWith(typedBare)
        }
        if (index < 0) {
            if (typed.isNotEmpty()) { poolBase = ""; pool = emptyList() }
            return false
        }
        val candidate = pool[index]
        val bare = candidate.trimStart(*LEADING_PUNCT)
        val rest = candidate.substring(candidate.length - (bare.length - typedBare.length))
        if (rest.isEmpty()) return false
        suggestion = Suggestion(text, rest)
        runCatching { box.invalidate() }
        return true
    }

    private fun schedulePredict() {
        val token = ++revision
        pending?.cancel()
        pending = scope.launch {
            delay(RelayConfig.DEBOUNCE_MS)
            if (token != revision) return@launch
            predict(token)
        }
    }

    private suspend fun predict(token: Int) {
        if (!RelayPrefs.enabled(box.context)) {
            if (!loggedDisabled) {
                loggedDisabled = true
                MoodLog.i("RELAY_DISABLED 续话开关未开启，保持微信原生输入")
            }
            return
        }
        val editable = box.text ?: return
        val caret = box.selectionEnd.coerceIn(0, editable.length)
        // 只在文末预测：光标在中间时补全没有意义。
        if (caret != editable.length) return
        val prefix = editable.toString().trimEnd()
        val minPrefix = RelayPrefs.minPrefix(box.context)
        if (prefix.length < minPrefix) return
        val settings = ModulePrefs.settings()?.reply ?: return
        if (!settings.isConfigured) return
        val contextLimit = RelayPrefs.contextLimit(box.context)
        val context = withContext(Dispatchers.IO) {
            runCatching { MessageSniffer.replyContext(requireBottom = false, limit = contextLimit, cacheable = true) }.getOrNull()
        } ?: return
        if (token != revision) return

        val count = RelayPrefs.candidateCount(box.context)
        val key = "${context.talker}|${context.latestLoadedId}|${context.messages.size}|$count|$prefix"
        val cached = synchronized(cache) { cache[key] }
        if (!cached.isNullOrEmpty()) {
            if (token == revision) {
                applyCandidates(prefix, cached)
            }
            return
        }

        // 从这里开始要等网络了，先把「生成中」的动态点挂上。
        startGenerating()
        try {
            // 流式：每解析出一行就收进池子，第一条到达立刻显示，不必等整批生成完。
            val collected = java.util.Collections.synchronizedList(mutableListOf<String>())
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    ReplyHttpClient().suggestRelay(settings, context, prefix, count) { line ->
                        val text = clean(line, prefix)
                        if (text.isNotBlank() && !collected.contains(text)) {
                            collected += text
                            if (collected.size == 1) {
                                box.post {
                                    if (token == revision) {
                                        stopGenerating()   // 已经有第一条建议可看，点就不必再跳了
                                        applyCandidates(prefix, listOf(text))
                                    }
                                }
                            }
                        }
                    }
                }.getOrNull()
            }.orEmpty()
            if (token != revision) return
            val cleaned = if (collected.isEmpty())
                fetched.map { clean(it, prefix) }.filter { it.isNotBlank() }.distinct()
            else collected.toList()
            if (cleaned.isEmpty()) return
            synchronized(cache) { cache[key] = cleaned }
            applyCandidates(prefix, cleaned)
            MoodLog.i("RELAY_SUGGESTED pool=${cleaned.size} firstLen=${cleaned.first().length}")
        } finally {
            // 无论成功、失败、被新一轮输入取消，都要把点收掉。
            stopGenerating()
        }
    }

    /** 候选池就绪：第一条先显示，其余留着给后续输入做本地匹配。 */
    private fun applyCandidates(prefix: String, list: List<String>) {
        poolBase = prefix
        pool = list
        val first = list.firstOrNull() ?: return
        suggestion = Suggestion(prefix, first)
        runCatching { box.invalidate() }
    }

    /**
     * 模型给的是"完整一句话"，而这里只要"接下去的部分"，
     * 所以把与已输入内容重叠的开头裁掉（提示词已经要求不重复，但模型并不总听话）。
     */
    /** 候选开头可能出现的衔接标点。本地匹配时忽略它们，避免用户跳过标点就匹配不上。 */
    private val LEADING_PUNCT = charArrayOf('，', '。', '、', '；', '：', ',', '.', ';', ':', ' ', '\n')

    private fun clean(raw: String?, prefix: String): String {
        if (raw == null) return ""
        var text = raw.trim()
        listOf("\"", "“", "”", "「", "」").forEach { text = text.removePrefix(it).removeSuffix(it).trim() }
        text = text.replace("\n", " ").trim()
        // 流式逐行输出时模型常带「1. 」「- 」这类前缀，先剥掉再比对重叠。
        text = text.replace(Regex("""^\s*(\d{1,3}\s*[.、)）]|[-•*·])\s*"""), "")
        text = removeOverlap(prefix, text)
        // 只去空白，保留标点：裁掉重复前缀后留下的前导逗号/句号，
        // 正是它与 prefix 衔接所需的，删掉就会变成「开始冲销量了估计得忙一阵」这种没标点的连读。
        text = text.trimStart(' ', '\n')
        return text.take(RelayConfig.MAX_CHARS)
    }

    /**
     * 去掉建议开头与已输入内容重复的部分。
     *
     * 只做精确逐字比对是不够的：模型复述前缀时常有细微出入（多一个字、标点不同），
     * 一旦匹配失败就会原样拼到输入后面，变成「……更好的更好地的办法吗」这种重复。
     * 所以精确匹配失败后再退一步做模糊匹配。
     */
    private fun removeOverlap(prefix: String, suggestion: String): String {
        if (prefix.isEmpty() || suggestion.isEmpty()) return suggestion
        if (suggestion.startsWith(prefix)) return suggestion.substring(prefix.length)

        exactOverlap(prefix, suggestion).let { if (it > 0) return suggestion.substring(it) }
        fuzzyOverlap(prefix, suggestion).let { if (it > 0) return suggestion.substring(it) }
        return suggestion
    }

    /** 精确逐字：prefix 尾巴与 suggestion 开头的最长重合（至少 2 字才算）。 */
    private fun exactOverlap(prefix: String, suggestion: String): Int {
        val max = minOf(prefix.length, suggestion.length)
        for (len in max downTo 2) {
            if (prefix.regionMatches(prefix.length - len, suggestion, 0, len)) return len
        }
        return 0
    }

    /**
     * 模糊重叠：忽略标点空格，看 suggestion 开头是不是在复述 prefix 的结尾。
     * 命中时返回 suggestion 中应裁掉的字符数（含被跳过的标点）。
     */
    private fun fuzzyOverlap(prefix: String, suggestion: String): Int {
        val tail = prefix.takeLast(minOf(prefix.length, 14)).filterNot { isIgnorable(it) }
        if (tail.length < 2) return 0
        for (len in minOf(tail.length, 8) downTo 2) {
            val piece = tail.takeLast(len)
            val head = suggestion.take(len * 2 + 2).filterNot { isIgnorable(it) }
            if (!head.startsWith(piece)) continue
            // 把「去标点后命中」换算回 suggestion 的真实下标
            var consumed = 0
            var index = 0
            while (index < suggestion.length && consumed < len) {
                if (!isIgnorable(suggestion[index])) consumed++
                index++
            }
            return index
        }
        return 0
    }

    private fun isIgnorable(c: Char): Boolean =
        c.isWhitespace() || c in "，。、；：,.;:!?！？~～…—-\"'"

    /**
     * 把画布坐标系搬到输入框内容区，在文字末尾执行 [block]。
     * 光标不在文末、输入框为空或布局未就绪时直接跳过。
     * 灰字和等待中的点都用这套换算，所以合在一处。
     */
    private inline fun withGhostAnchor(canvas: Canvas, block: (startX: Float, baseline: Float) -> Unit) {
        val layout = box.layout ?: return
        val editable = box.text ?: return
        if (editable.isEmpty()) return
        if (box.selectionEnd != editable.length) return
        val line = runCatching { layout.getLineForOffset(editable.length) }.getOrDefault(-1)
        if (line < 0 || line >= layout.lineCount) return
        val startX = layout.getPrimaryHorizontal(editable.length)
        val baseline = layout.getLineBaseline(line)
        ghostPaint.textSize = box.textSize
        ghostPaint.typeface = box.typeface
        canvas.save()
        canvas.translate(
            (box.totalPaddingLeft - box.scrollX).toFloat(),
            (box.totalPaddingTop - box.scrollY).toFloat(),
        )
        try {
            block(startX, baseline.toFloat())
        } finally {
            canvas.restore()
        }
    }

    /** 在文字末尾之后补画灰色建议。所有坐标都换算到 layout 的内容坐标系。 */
    fun draw(canvas: Canvas) {
        if (generating) {
            drawGeneratingDots(canvas)
            return
        }
        val current = suggestion ?: return
        // 补位要等画布恢复之后再做，否则改布局会和这一帧的绘制打架
        var heightUsed = -1
        withGhostAnchor(canvas) { startX, baseline ->
            val contentWidth = (box.width - box.totalPaddingLeft - box.totalPaddingRight).toFloat()
            val firstWidth = contentWidth - startX
            val lineHeight = box.lineHeight.toFloat().takeIf { it > 0f } ?: ghostPaint.textSize * 1.35f
            val used = drawGhostLines(canvas, current.text, startX, baseline, lineHeight, contentWidth, firstWidth)
            if (used != ghostLines) heightUsed = used
        }
        if (heightUsed >= 0) {
            ghostLines = heightUsed
            applyGhostHeight(heightUsed)
        }
    }

    /**
     * 等待模型返回时，在光标后画「·」「··」「···」循环。
     * 复用灰字那套坐标换算：光标在文末时 startX 就是输入内容之后的位置。
     */
    private fun drawGeneratingDots(canvas: Canvas) {
        withGhostAnchor(canvas) { startX, baseline ->
            runCatching {
                canvas.drawText("·".repeat(dotPhase.coerceIn(1, 3)), startX, baseline, ghostPaint)
            }
        }
    }

    private fun startGenerating() {
        if (generating) return
        generating = true
        dotPhase = 1
        dotJob?.cancel()
        dotJob = scope.launch {
            while (isActive) {
                delay(DOT_INTERVAL_MS)
                dotPhase = if (dotPhase >= 3) 1 else dotPhase + 1
                runCatching { box.invalidate() }
            }
        }
        runCatching { box.invalidate() }
        MoodLog.i("RELAY_GENERATING_DOTS on")
    }

    private fun stopGenerating() {
        if (!generating) return
        generating = false
        dotJob?.cancel()
        dotJob = null
        runCatching { box.invalidate() }
        MoodLog.i("RELAY_GENERATING_DOTS off")
    }

    /** 从 startX 开始画灰字，越界就换行。返回实际占用的行数。 */
    private fun drawGhostLines(canvas: Canvas, text: String, startX: Float, firstBaseline: Float,
        lineHeight: Float, fullWidth: Float, firstWidth: Float): Int {
        if (text.isEmpty()) return 0
        var index = 0
        var used = 0
        var baseline = firstBaseline
        var x = startX
        var width = firstWidth
        while (index < text.length && used < RelayConfig.MAX_GHOST_LINES && width > 1f) {
            var end = text.length
            while (end > index && ghostPaint.measureText(text, index, end) > width) end--
            if (end <= index) break
            runCatching { canvas.drawText(text, index, end, x, baseline, ghostPaint) }
            index = end
            used++
            x = 0f
            width = fullWidth
            baseline += lineHeight
        }
        return used
    }

    /**
     * 灰字换行后输入框要跟着变高，否则第二行会被裁掉（灰字不参与 TextView 的测量）。
     * 只改 minHeight，微信原有的高度约束保持不变；灰字消失时恢复。
     */
    private fun applyGhostHeight(lines: Int) {
        if (baseMinHeight < 0) baseMinHeight = box.minHeight
        val extra = (lines - 1).coerceIn(0, RelayConfig.MAX_GHOST_LINES - 1)
        val target = if (extra == 0) baseMinHeight else baseMinHeight + extra * box.lineHeight
        if (box.minHeight == target) return
        box.post {
            if (box.minHeight != target) {
                box.minHeight = target
                box.requestLayout()
            }
        }
    }

    private fun resetGhostHeight() {
        ghostLines = 0
        if (baseMinHeight >= 0 && box.minHeight != baseMinHeight) {
            box.post {
                if (box.minHeight != baseMinHeight) {
                    box.minHeight = baseMinHeight
                    box.requestLayout()
                }
            }
        }
    }

    /**
     * 建议区域内按下就接管整段手势。
     *
     * 只在 UP 时消费是不够的：DOWN 会照常传给 EditText，它据此启动长按计时器，
     * 而我们又吞掉了 UP，它永远等不到"抬起取消"，于是弹出选择/复制菜单。
     */
    fun handleTouch(event: MotionEvent): Boolean {
        val current = suggestion
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (current == null || !hitTest(current, event)) return false
                touching = true
                return true
            }
            MotionEvent.ACTION_MOVE -> return touching
            MotionEvent.ACTION_UP -> {
                if (!touching) return false
                touching = false
                if (current != null && hitTest(current, event)) fill(current.text)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!touching) return false
                touching = false
                return true
            }
        }
        return false
    }

    /** 命中灰字的实际绘制矩形（上下左右都放宽几像素，方便手指点）。 */
    private fun hitTest(current: Suggestion, event: MotionEvent): Boolean {
        val layout = box.layout ?: return false
        val editable = box.text ?: return false
        if (editable.isEmpty()) return false
        val line = runCatching { layout.getLineForOffset(editable.length) }.getOrDefault(-1)
        if (line < 0 || line >= layout.lineCount) return false
        val startX = layout.getPrimaryHorizontal(editable.length)
        ghostPaint.textSize = box.textSize
        ghostPaint.typeface = box.typeface
        val contentWidth = (box.width - box.totalPaddingLeft - box.totalPaddingRight).toFloat()
        val lines = estimateGhostLines(current.text, contentWidth - startX, contentWidth)
        if (lines <= 0) return false
        val lineHeight = box.lineHeight.toFloat().takeIf { it > 0f } ?: ghostPaint.textSize * 1.35f
        val contentX = event.x - box.totalPaddingLeft + box.scrollX
        val contentY = event.y - box.totalPaddingTop + box.scrollY
        val top = layout.getLineTop(line).toFloat()
        val singleLineBottom = layout.getLineBottom(line).toFloat()
        val bottom = top + lines * lineHeight
        // 第一行只能点文字之后的空白；折行后的区域整行都属于建议。
        val minX = if (contentY <= singleLineBottom) startX - dp(4) else 0f
        return contentX >= minX && contentX <= contentWidth + dp(6) &&
            contentY >= top - dp(6) && contentY <= bottom + dp(6)
    }

    /** 只计算占用行数，不绘制（命中判定与高度撑高都要用）。 */
    private fun estimateGhostLines(text: String, firstWidth: Float, fullWidth: Float): Int {
        if (text.isEmpty() || fullWidth <= 1f) return 0
        var index = 0
        var used = 0
        var width = firstWidth
        while (index < text.length && used < RelayConfig.MAX_GHOST_LINES && width > 1f) {
            var end = text.length
            while (end > index && ghostPaint.measureText(text, index, end) > width) end--
            if (end <= index) break
            index = end
            used++
            width = fullWidth
        }
        return used
    }

    private fun fill(text: String) {
        // 先取走已输入部分，下面马上就清空 poolBase 了。
        // 指纹要记「用户打的 + AI 补的」整句，只记补的那半段，
        // 发送时整句和指纹对不上，就会被当成本人发言学进画像。
        val typedPrefix = poolBase
        touching = false
        suggestion = null
        resetGhostHeight()
        poolBase = ""
        pool = emptyList()
        pending?.cancel()
        revision++
        applying = true
        try {
            box.requestFocus()
            box.setSelection(box.text?.length ?: 0)
            val connection = runCatching {
                box.onCreateInputConnection(EditorInfo().apply { inputType = box.inputType })
            }.getOrNull()
            val committed = runCatching { connection?.commitText(text, 1) == true }.getOrDefault(false)
            if (!committed) {
                box.text?.append(text)
                box.setSelection(box.text?.length ?: 0)
            }
            runCatching { box.invalidate() }
            MoodLog.i("RELAY_FILLED chars=${text.length} via=${if (committed) "connection" else "append"}")
            WarReviewFingerprint.remember(box.context, typedPrefix + text)
        } catch (error: Exception) {
            MoodLog.w("RELAY_FILL_FAILED " + error.javaClass.simpleName)
        } finally {
            applying = false
        }
    }
}

/** 调参集中在这里，实机试手感时只改一处。 */
private object RelayConfig {
    /** 停手多久才请求。太短会请求密集，太长会显得迟钝。 */
    const val DEBOUNCE_MS = 400L
    /** 单次建议上限，配合前缀裁剪可以覆盖用户连续输入的一整句。 */
    const val MAX_CHARS = 32
    /** 灰字最多显示几行，超出部分不画（用换行+撑高的方式保证可读）。 */
    const val MAX_GHOST_LINES = 3
    val GRAY: Int = Color.parseColor("#9AA0A6")
}
