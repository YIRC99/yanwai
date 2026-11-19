package com.dogheadstrategist.chat.feature

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.dogheadstrategist.chat.MainActivity
import com.dogheadstrategist.chat.R
import com.dogheadstrategist.chat.core.MoodLog

/**
 * 通知代办的桌面小组件：显示最近三条未完成的要事。
 *
 * 为什么手写 RemoteViews 而不是上 Glance：这个环境里 Glance 要牵
 * compose-runtime + 编译器插件一整条依赖链，而小组件只需要三行文字。
 * RemoteViews 零依赖，API 1 就有，稳定性在这里比写法优雅重要。
 *
 * **刷新时机**：要事在 app 进程里产生，widget 也属于 app，进程内刷新没问题；
 * 但如果哪天要在微信进程里推——`AppWidgetManager.updateAppWidget` 是按调用者包校验的，
 * 别的 uid 更新会被系统拒掉。所以刷新点有三个：系统 onUpdate、打开 App 之后主动调
 * [refresh]、以及用户点小部件上的 ⟳。
 */
class AideWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { render(context, manager, id) }
            .onFailure { MoodLog.w("AIDE_WIDGET_RENDER_FAILED " + it.javaClass.simpleName) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        runCatching { render(context, manager, appWidgetId) }
    }

    /**
     * RemoteViews 里点一下只能发广播，真正的处理都在这里。
     *
     * 两个自定义动作：⟳ 重画一遍，✓ 把那条直接删掉再重画。
     *
     * 为什么是删不是标记：原来只把 done 置 true，记录其实一直躺在
     * matter_items.json 里，靠列表过滤藏起来。用户看不到这个文件，
     * 看到的是「勾了怎么还越积越多」。小部件上点一下本来就是终态操作，
     * 没有二次确认的机会，与其留一堆看不见的死数据，不如当场清掉。
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_DONE) {
            if (intent.action == ACTION_DONE) {
                val id = intent.getLongExtra(EXTRA_ITEM_ID, 0L)
                if (id > 0L) runCatching { MatterStore.deleteItem(context, id) }
                    .onFailure { MoodLog.w("AIDE_WIDGET_DONE_FAILED " + it.javaClass.simpleName) }
            }
            val manager = AppWidgetManager.getInstance(context)
            if (manager != null) {
                val ids = manager.getAppWidgetIds(ComponentName(context, AideWidget::class.java))
                onUpdate(context, manager, ids)
            }
            return
        }
        super.onReceive(context, intent)
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_aide)
        val items = MatterStore.recentItems(context, 3)
        val types = MatterStore.types(context).associateBy { it.key }

        // 胶囊显示的是「还有多少条在排队等 AI 提取」，不是这几行的条数。
        // 原来写 items.size，而 recentItems 只 take(3)，所以永远停在 3 以内，
        // 真正积压多少根本看不出来。
        val queue = runCatching { AideInbox.count(context) }.getOrDefault(0)
        views.setTextViewText(
            R.id.widgetAideCount,
            if (queue > 99) "待 99+" else "待 $queue",
        )
        // 有积压时换成暖黄，扫一眼就知道该点进来了；清零后回到灰绿，不抢注意力
        views.setTextColor(
            R.id.widgetAideCount,
            ContextCompat.getColor(
                context,
                if (queue > 0) R.color.status_warning else R.color.status_neutral,
            ),
        )
        views.setInt(
            R.id.widgetAideCount,
            "setBackgroundResource",
            if (queue > 0) R.drawable.widget_aide_pill_warning else R.drawable.widget_aide_pill_neutral,
        )
        views.setViewVisibility(R.id.widgetAideEmpty, if (items.isEmpty()) View.VISIBLE else View.GONE)
        // 有积压但还没提取出来时，说「还没有记下的事」是错的——它们已经在路上了
        if (items.isEmpty()) {
            views.setTextViewText(
                R.id.widgetAideEmpty,
                if (queue > 0) "还有 $queue 条在排队\n提取完会出现在这里"
                else "还没有记下的事\n有新消息时会自动出现",
            )
        }

        val rowIds = intArrayOf(R.id.widgetAideRow1, R.id.widgetAideRow2, R.id.widgetAideRow3)
        val barIds = intArrayOf(R.id.widgetAideBar1, R.id.widgetAideBar2, R.id.widgetAideBar3)
        val titleIds = intArrayOf(R.id.widgetAideTitle1, R.id.widgetAideTitle2, R.id.widgetAideTitle3)
        val contentIds = intArrayOf(R.id.widgetAideContent1, R.id.widgetAideContent2, R.id.widgetAideContent3)
        val sourceIds = intArrayOf(R.id.widgetAideSource1, R.id.widgetAideSource2, R.id.widgetAideSource3)
        val doneIds = intArrayOf(R.id.widgetAideDone1, R.id.widgetAideDone2, R.id.widgetAideDone3)

        for (index in rowIds.indices) {
            val item = items.getOrNull(index)
            if (item == null) {
                views.setViewVisibility(rowIds[index], View.GONE)
                continue
            }
            views.setViewVisibility(rowIds[index], View.VISIBLE)
            val type = types[item.typeKey]
            // 左侧色条沿用类型在 App 里的颜色，扫一眼就知道是待办还是会议
            views.setInt(barIds[index], "setBackgroundColor", typeColor(type?.color))
            views.setTextViewText(titleIds[index], item.emoji + " " + (type?.title ?: item.typeKey))
            views.setTextViewText(contentIds[index], item.content)
            views.setTextViewText(sourceIds[index], listOf(item.source, relativeTime(item.createdAt))
                .filter { it.isNotBlank() }.joinToString(" · "))
            views.setOnClickPendingIntent(doneIds[index], tick(context, widgetId, index, item.id))
        }

        views.setOnClickPendingIntent(R.id.widgetAideRefresh, broadcast(context, widgetId, ACTION_REFRESH, 0L, REQUEST_REFRESH))

        // 点整块打开 App 并直接落到通知代办面板
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_AIDE, true)
        }
        val pending = PendingIntent.getActivity(
            context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widgetAideRoot, pending)

        manager.updateAppWidget(widgetId, views)
    }

    private fun typeColor(color: String?): Int =
        runCatching { android.graphics.Color.parseColor(color ?: DEFAULT_TYPE_COLOR) }
            .getOrDefault(android.graphics.Color.parseColor(DEFAULT_TYPE_COLOR))

    /** 小部件位置有限，时间只说到「几小时前」这一级，不显示具体时分。 */
    private fun relativeTime(millis: Long): String {
        if (millis <= 0L) return ""
        val diff = System.currentTimeMillis() - millis
        if (diff < 0L) return ""
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 172_800_000L -> "昨天"
            diff < 2_592_000_000L -> "${diff / 86_400_000L} 天前"
            else -> ""
        }
    }

    /**
     * 勾选按钮的 PendingIntent。
     *
     * requestCode 必须区分开：同一个 widget 上三行共用一个 code 的话，系统会把它们
     * 认成同一个 PendingIntent，点哪一行都是同一条被勾掉。
     */
    private fun tick(context: Context, widgetId: Int, index: Int, itemId: Long): PendingIntent =
        broadcast(context, widgetId, ACTION_DONE, itemId, REQUEST_DONE_BASE + index)

    private fun broadcast(
        context: Context,
        widgetId: Int,
        action: String,
        itemId: Long,
        requestOffset: Int,
    ): PendingIntent {
        val intent = Intent(context, AideWidget::class.java).apply {
            this.action = action
            if (itemId > 0L) putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context, widgetId * 10 + requestOffset, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val ACTION_REFRESH = "com.dogheadstrategist.chat.AIDE_REFRESH"
        private const val ACTION_DONE = "com.dogheadstrategist.chat.AIDE_DONE"
        private const val EXTRA_ITEM_ID = "aide_item_id"
        private const val DEFAULT_TYPE_COLOR = "#607D8B"
        private const val REQUEST_REFRESH = 0
        private const val REQUEST_DONE_BASE = 1

        /** 数据变了之后主动刷一次。只在 app 进程调用——跨进程会被系统拒。 */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, AideWidget::class.java))
                if (ids.isEmpty()) return
                // 走 onUpdate 那套逻辑：发一个 update 广播给所有实例最省事
                val intent = Intent(context, AideWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }.onFailure { MoodLog.w("AIDE_WIDGET_REFRESH_FAILED " + it.javaClass.simpleName) }
        }
    }
}
