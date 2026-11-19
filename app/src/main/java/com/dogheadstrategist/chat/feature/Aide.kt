package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplySettings
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.random.Random

/**
 * 通知代办：从微信通知里挑出该记的事。
 *
 * 和通知摘要的分工：通知摘要管「现在要不要回」，通知代办管「这条之后要不要记得」。
 * 两条路径完全分开——通知摘要那条通知是时间敏感的，通知代办是后台活，
 * 各跑各的请求，互不占用。真正的触发点在
 * [com.dogheadstrategist.chat.sentry.SentryNotificationService]，由 [AideDigest] 调度。
 *
 * 这里只提供界面需要的查询；真正的提取由 [AideDigest] 在后台调度。
 */
object Aide : StrategistModule {
    override val feature = StrategistFeature.AIDE

    /** 历史列表，新的在前。 */
    fun items(context: Context): List<MatterItem> = MatterStore.items(context)

    /** 桌面小组件要的最近几条。 */
    fun recent(context: Context, limit: Int = 3): List<MatterItem> = MatterStore.recentItems(context, limit)

    fun delete(context: Context, id: Long) = MatterStore.deleteItem(context, id)


    fun clear(context: Context) = MatterStore.clear(context)

    /** 打开列表前清一次过期记录，省得用户看到一堆早就没用的东西。 */
    fun refresh(context: Context) = MatterStore.prune(context)

}
