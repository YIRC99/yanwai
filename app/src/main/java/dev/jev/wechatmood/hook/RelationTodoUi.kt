package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.ReplyContactKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 待办窗口：对方说过的、带绝对时间的约定与重要日期。
 *
 * 只读关系档案里已有的条目，不额外请求模型；标记完成、否掉都是本地一次状态改写。
 * 没有时间的说法（过两天、有空）不进来，宁可少一条，也不猜出一个假日期。
 */
internal class RelationTodoUi(private val activity: Activity, private val talker: String?) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val statusText: TextView = theme.label("", ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
        setPadding(0, theme.dp(4), 0, 0)
        visibility = View.GONE
    }
    private var items: List<RelationFact> = emptyList()
    private var contactKey: ReplyContactKey? = null
    private var dialog: Dialog? = null

    fun show() {
        val dialog = Dialog(activity).also { this.dialog = it }
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("待办"))
        column.addView(kit.caption("对方说过的约定和重要日期，只列有明确时间的").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        column.addView(statusText)
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()).apply {
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
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()

        val selected = talker
        MoodLog.i("RELATION_TODO_OPEN talker=${selected != null} group=${selected?.endsWith("@chatroom") == true}")
        if (selected == null || selected.endsWith("@chatroom")) {
            showStatus("待办只针对私聊；群聊没有联系人档案", theme.warning)
            listContainer.addView(kit.body("返回一个私聊再打开"))
            return
        }
        showStatus("正在读取…", theme.muted)
        scope.launch {
            val key = resolveKey(selected)
            contactKey = key
            // 记下这条档案对应哪个聊天和备注名，到期通知点开才能进对会话。
            if (key != null) {
                val name = withContext(Dispatchers.IO) {
                    runCatching { ReplyDatabaseHistory.displayNames(listOf(selected))[selected] }.getOrNull()
                }
                RelationFactBridge.rememberContact(activity, key, selected, name.orEmpty())
            }
            if (key == null) {
                showStatus("没能确认是哪个账号的聊天，先回到聊天页等一会儿", theme.warning)
                listContainer.addView(kit.body("暂时读不到这个联系人的档案"))
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
        return ReplyContactKey.of(account, selected)
    }

    private fun reload() {
        val key = contactKey ?: return
        scope.launch { load(key) }
    }

    private suspend fun load(key: ReplyContactKey) {
        val loaded = withContext(Dispatchers.IO) { RelationFactBridge.loadAll(activity, key) }
        items = loaded
        hideStatus()
        MoodLog.i("RELATION_TODO_LOAD total=${loaded.size} open=${loaded.count { it.isOpen }} " +
            "dated=${loaded.count { it.isOpen && it.occurredAt > 0 }}")
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val now = System.currentTimeMillis()
        val todos = items.filter { it.isOpen && it.occurredAt > 0 }.sortedBy { it.occurredAt }
        val overdue = todos.filter { FactTimes.overdue(it.occurredAt, now) }
        val upcoming = todos.filter { !FactTimes.overdue(it.occurredAt, now) }
        val handled = items.filter { it.status == RelationFact.STATUS_DONE || it.status == RelationFact.STATUS_CANCELLED }
            .sortedByDescending { it.updatedAt }.take(HANDLED_LIMIT)

        if (todos.isEmpty()) {
            listContainer.addView(kit.body("这个联系人还没有带时间的约定"))
            listContainer.addView(kit.caption("打开一次「帮我回」会自动整理；也可以在抽屉里点「更新关系档案」"))
        }
        if (overdue.isNotEmpty()) {
            listContainer.addView(kit.sectionLabel("已过期 ${overdue.size} 条"))
            overdue.forEach { listContainer.addView(todoRow(it, overdue = true), rowParams()) }
        }
        if (upcoming.isNotEmpty()) {
            listContainer.addView(kit.sectionLabel("接下来 ${upcoming.size} 条"))
            upcoming.forEach { listContainer.addView(todoRow(it, overdue = false), rowParams()) }
        }
        if (handled.isNotEmpty()) {
            listContainer.addView(kit.sectionLabel("已处理"))
            handled.forEach { listContainer.addView(todoRow(it, overdue = false), rowParams()) }
        }
    }

    /** 一条待办：时间与分类在上，陈述句在下；整行可点，弹出完成／否掉。 */
    private fun todoRow(fact: RelationFact, overdue: Boolean): View {
        val done = fact.status == RelationFact.STATUS_DONE
        val cancelled = fact.status == RelationFact.STATUS_CANCELLED
        val closed = done || cancelled
        val tone = when {
            closed -> theme.muted
            overdue -> theme.warning
            else -> theme.accent
        }
        val head = buildString {
            append(fact.kind)
            if (fact.occurredAt > 0) append(" · ").append(FactTimes.label(fact.occurredAt))
            when {
                done -> append(" · 已完成")
                cancelled -> append(" · 已移除")
                overdue -> append(" · 已过期")
            }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD,
                if (overdue && !closed) theme.warning else theme.border)
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true; isFocusable = true
            contentDescription = "$head，${fact.text}，点击更改状态"
            setOnClickListener { ask(fact) }
        }
        card.addView(theme.label(head, ReplyMetrics.TEXT_CAPTION, tone, bold = !closed))
        card.addView(theme.label(fact.text, ReplyMetrics.TEXT_ITEM, if (closed) theme.muted else theme.ink).apply {
            setPadding(0, theme.dp(2), 0, 0)
        })
        return card
    }

    /** 出口：加到日历、完成、否掉；已处理的只留恢复。 */
    private fun ask(fact: RelationFact) {
        val labels = if (fact.isOpen) listOf("加到系统日历", "标记为已完成", "不是这件事，移出待办")
            else listOf("恢复为待办")
        kit.picker(fact.text, labels) { index ->
            when (labels.getOrNull(index)) {
                "加到系统日历" -> addToCalendar(fact)
                "标记为已完成" -> update(fact, RelationFact.STATUS_DONE)
                "不是这件事，移出待办" -> update(fact, RelationFact.STATUS_CANCELLED)
                "恢复为待办" -> update(fact, RelationFact.STATUS_ACTIVE)
            }
        }
    }

    /** 走系统日历的插入界面：不申请日历权限，写不写由用户在日历里确认。 */
    private fun addToCalendar(fact: RelationFact) {
        val clock = FactTimes.hasClock(fact.occurredAt)
        val begin = if (clock) fact.occurredAt else FactTimes.startOfDay(fact.occurredAt)
        val end = if (clock) begin + 3_600_000L else begin + 86_400_000L
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, fact.text)
            .putExtra(CalendarContract.Events.DESCRIPTION, "言外关系档案 · ${fact.kind}")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, !clock)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { activity.startActivity(intent); true }.getOrDefault(false)
        if (!opened) {
            MoodLog.w("RELATION_TODO_CALENDAR_FAILED")
            kit.info("没找到系统日历", "这台手机上打不开日历的插入界面，可以手动新建一条。")
        }
    }

    private fun update(fact: RelationFact, status: String) {
        val key = contactKey ?: return
        showStatus("正在保存…", theme.muted)
        scope.launch {
            val saved = withContext(Dispatchers.IO) { RelationFactBridge.setStatus(activity, key, fact.factId, status) }
            MoodLog.i("RELATION_TODO_STATUS ok=$saved status=$status")
            if (!saved) {
                showStatus("没保存成功，档案可能刚被更新过，重开一次再试", theme.error)
                return@launch
            }
            load(key)
            // 标完成的不该继续挂在通知栏里：重算一次，没了就撤，还有别的就重发。
            RelationReminder.refresh(activity, key, talker)
        }
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
        const val HANDLED_LIMIT = 6
    }
}
