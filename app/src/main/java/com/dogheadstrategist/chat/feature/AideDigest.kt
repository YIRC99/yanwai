package com.dogheadstrategist.chat.feature

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.reply.AideProtocol
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知代办的定期提取。
 *
 * 三条约束决定了它的形状：
 *
 * 1. **不和通知摘要同时调用**。通知摘要那条通知是时间敏感的，用户在等；提取是后台活，
 *    晚几秒甚至几分钟都无所谓。所以这里只在通知到达时入队，等节流窗口过去才动。
 * 2. **批量**。一次请求送 1~8 条，模型按 index 回填，比逐条发请求省下大量往返。
 * 3. **失败不清空**。解析不出来的留在队列里等下一轮，宁可晚一点，也别把用户
 *    还没看见的消息直接丢掉。
 *
 * 队列放在 [AideInbox]，结果落在 [MatterStore]。
 */
object AideDigest {
    /**
     * 心跳间隔。每隔这么久醒一次，看看到没到投送时间。
     *
     * 不写成「delay(设定间隔)」的原因：那样用户改了设置要等一整个周期才生效，
     * 队列突然变长也没法提前处理。固定心跳 + 每次重新读设置，改动最多 30 秒生效。
     */
    private const val TICK_MS = 30_000L

    /** 攒够多少条就值得跑一次。只有 1 条也跑，不然单聊场景永远等不到。 */
    private const val MIN_BATCH = 1

    /** 一次最多送多少条。太多会把 prompt 撑长、模型漏项。 */
    private const val MAX_BATCH = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var lastRunAt = 0L

    /** 连续整批失败次数，用来算退避。单条内容问题不计入这里。 */
    @Volatile private var consecutiveFailures = 0

    /** 退避截止时间。到点之前心跳不打扰模型。 */
    @Volatile private var backoffUntil = 0L

    @Volatile private var tickerStarted = false

    /**
     * 启动常驻心跳。幂等，重复调用只生效一次。
     *
     * 没有这个心跳，「定期投送」其实是假的：原来的判断只挂在通知到达那一瞬间，
     * 微信半小时没新消息，队列里剩的条目就干躺着发不出去。
     *
     * 注意传进来的一律转 applicationContext，别让 object 单例持住 Activity/Service。
     */
    fun ensureTicker(context: Context) {
        if (tickerStarted) return
        tickerStarted = true
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching { maybeRun(app) }
            }
        }
    }

    /**
     * 通知到达时调用。只做入队和「要不要现在跑」的判断，立刻返回。
     *
     * 入队不做内容筛选——该不该记由模型判断，本地关键词规则只会漏掉措辞不常见的
     * 要事。队列体量靠上限和单会话配额控制，不靠过滤内容。
     */
    fun onNotice(context: Context, sender: String, text: String) {
        // 不再做本地关键词预筛：判断该不该记是模型的活，本地规则只会漏掉
        // 措辞不常见的要事（「周五那事儿别忘」命中不了任何关键词，但确实是待办）。
        // 队列靠上限和单会话配额控制体量，不靠内容过滤。
        val talker = runCatching { SentryStore.findTalker(context, sender) }.getOrDefault("")
        AideInbox.push(context, sender, text, talker)
        refreshWidget(context)
        maybeRun(context)
    }

    /**
     * 通知监听服务连上时调用。
     *
     * 除了启动心跳，还要补跑一次：进程可能被杀过又重启，队列里积压的条目
     * 不该再干等一个完整间隔。
     */
    fun onServiceConnected(context: Context) {
        ensureTicker(context)
        maybeRun(context)
    }

    /** 用户手动点「立即提取」时用，跳过节流。 */
    fun trigger(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { run(app, force = true) }.onFailure {
                MoodLog.w("AIDE_DIGEST_FAILED " + it.javaClass.simpleName)
            }
        }
    }

    /**
     * 心跳和通知到达都走这里。
     *
     * 只做最便宜的两道预判（退避中、队列空），时间间隔的判断统一放在 run() 的锁内——
     * 两处都判的话，改一处漏一处很容易出「改完反而不投送」这种事。
     */
    /** 两次真正刷小组件之间至少隔这么久。 */
    private const val WIDGET_REFRESH_MIN_INTERVAL_MS = 5_000L

    /** 上次刷小组件的时间，用来节流。 */
    @Volatile private var lastWidgetRefreshAt = 0L

    /** 节流窗口里被合并掉的那次请求：窗口结束后要补刷一次。 */
    @Volatile private var widgetRefreshPending = false

    private val widgetHandler = Handler(Looper.getMainLooper())

    /**
     * 队列变了就把桌面小组件上的数字跟着改。
     *
     * 必须节流：群聊刷屏时一分钟可能进几十条，每条都发一次 update 广播会让
     * 系统反复重建 RemoteViews，得不偿失。5 秒一次，人眼已经看不出延迟。
     *
     * 但节流不能把窗口内的请求直接丢掉——丢掉的很可能正是最后一次（队列刚被
     * 消费到 0 的那一下），数字就一直停在上一个值，直到用户下次打开 App 才纠正。
     * 所以窗口内只记标记，等到窗口结束补刷一次；期间来多少次都合并成这一次。
     * 补刷走的是 [AideWidget.refresh]，它内部会重新读队列，拿到的是最新值。
     */
    private fun refreshWidget(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val elapsed = now - lastWidgetRefreshAt
        if (elapsed < WIDGET_REFRESH_MIN_INTERVAL_MS) {
            if (!widgetRefreshPending) {
                widgetRefreshPending = true
                widgetHandler.postDelayed(
                    {
                        widgetRefreshPending = false
                        lastWidgetRefreshAt = System.currentTimeMillis()
                        runCatching { AideWidget.refresh(app) }
                            .onFailure { MoodLog.w("AIDE_WIDGET_SYNC_FAILED " + it.javaClass.simpleName) }
                    },
                    WIDGET_REFRESH_MIN_INTERVAL_MS - elapsed,
                )
            }
            return
        }
        lastWidgetRefreshAt = now
        widgetRefreshPending = false
        runCatching { AideWidget.refresh(app) }
            .onFailure { MoodLog.w("AIDE_WIDGET_SYNC_FAILED " + it.javaClass.simpleName) }
    }

    private fun maybeRun(context: Context) {
        val app = context.applicationContext
        if (System.currentTimeMillis() < backoffUntil) return
        if (AideInbox.count(app) < MIN_BATCH) return
        scope.launch {
            runCatching { run(app, force = false) }.onFailure {
                MoodLog.w("AIDE_DIGEST_FAILED " + it.javaClass.simpleName)
            }
        }
    }

    /**
     * 取一批跑一次。
     *
     * 只处理模型确实回填了的条目；没回填的记一次失败留在队列里，下一轮再试。
     * 同一条连续失败到一定次数会被 [AideInbox.markFailed] 放弃——不放弃的话
     * 它每轮都占一个批量位，后面的条目永远轮不到。
     */
    private suspend fun run(context: Context, force: Boolean) = mutex.withLock {
        // 拿锁之后再判一次时间：心跳和通知回调可能同时通过 maybeRun 的检查，
        // 两个都进来就会把同一批处理两遍
        val now = System.currentTimeMillis()
        if (!force) {
            val intervalMs = AidePrefs.digestIntervalMin(context) * 60_000L
            if (now - lastRunAt < intervalMs) return@withLock
            if (now < backoffUntil) return@withLock
        }
        // 前置条件不满足时直接退出，不消耗间隔。
        // 否则「进程刚起 + 队列恰好空」会把 lastRunAt 推进，
        // 用户接下来一整个间隔内发的通知全都躺着不动
        val settings = ReplySettings.load(context) ?: return@withLock
        if (!settings.isConfigured) return@withLock
        val batch = AideInbox.pending(context).take(MAX_BATCH)
        if (batch.isEmpty()) return@withLock
        val types = MatterStore.types(context)
        // 到这里才真的要跑，才消耗这一次间隔
        lastRunAt = now
        val timeoutMs = (CommunicatePrefs.aiTimeoutSec(context) * 1000L).coerceAtLeast(15_000L)

        val outcomes = withTimeoutOrNull(timeoutMs) {
            runCatching {
                ReplyHttpClient().extractBatch(settings, batch, types, AidePrefs.keyUsers(context))
            }.getOrElse { error ->
                MoodLog.w("AIDE_EXTRACT_FAILED " + error.javaClass.simpleName)
                emptyList()
            }
        }.orEmpty()

        if (outcomes.isEmpty()) {
            // 整批没结果，绝大多数是环境问题：key 过期、断网、超时、限流。
            // 这种绝对不能记到单条头上——间隔设成 1 分钟的话，三次网络抖动
            // 就能把用户攒的整批消息永久烧掉，而他甚至没机会看到队列非空。
            // 改成全局指数退避：这批留在队列里，等环境恢复再试
            consecutiveFailures++
            val intervalMs = AidePrefs.digestIntervalMin(context) * 60_000L
            val factor = 1L shl consecutiveFailures.coerceIn(1, 4)
            val waitMs = (intervalMs * factor).coerceAtMost(30 * 60_000L)
            backoffUntil = System.currentTimeMillis() + waitMs
            MoodLog.w(
                "AIDE_DIGEST_BACKOFF batch=" + batch.size + " fails=" + consecutiveFailures +
                    " waitMs=" + waitMs,
            )
            return@withLock
        }

        // 这一批拿到结果了，环境是通的，退避清零
        consecutiveFailures = 0
        backoffUntil = 0L

        val doneIds = HashSet<Long>()
        val items = ArrayList<MatterItem>()
        // 门槛按 key 查，查不到的（模型自创的类型）按 0 处理：
        // 那种情况多半是模型没守住类型清单，宁可先记下来让用户看到，也别悄悄吞掉
        val thresholdOf = types.associate { it.key to it.minImportance }
        for (index in batch.indices) {
            val notice = batch[index]
            val draft = outcomes.getOrNull(index)
            if (draft == null) {
                // 模型漏回填这条。单独记一次失败，攒够次数就放弃，
                // 否则它会永远留在队列里，每轮都占一个批量位
                AideInbox.markFailed(context, hashSetOf(notice.id))
                continue
            }
            if (draft.typeKey.isEmpty() || draft.content.isEmpty()) {
                // 模型明确判断「没有可记的事」，这条就处理完了，不用再试
                doneIds.add(notice.id)
                continue
            }
            // 模型自创的类型（不在清单里）降级到 other：既保住内容，又不会在列表里
            // 出现认不出的分组。配置里连 other 都被删掉时保持原样，宁可显示异常也不丢。
            val typeKey = if (draft.typeKey in thresholdOf) draft.typeKey
                else "other".takeIf { it in thresholdOf } ?: draft.typeKey
            val threshold = thresholdOf[typeKey] ?: 0
            // importance < 0 表示模型没给分：放行，别用门槛把没分数的消息全挡掉
            if (threshold > 0 && draft.importance >= 0 && draft.importance < threshold) {
                // 分数不够，不收录。但这条通知仍然算处理完——否则它会一直卡在
                // 队列里被反复重试，每轮都白跑一次模型，还永远出不来
                doneIds.add(notice.id)
                MoodLog.i(
                    "AIDE_SKIP_LOW_IMPORTANCE type=" + typeKey +
                        " score=" + draft.importance + " min=" + threshold
                )
                continue
            }
            items.add(
                MatterItem(
                    id = notice.id * 10 + (index % 10),
                    typeKey = typeKey,
                    content = draft.content,
                    emoji = draft.emoji,
                    source = notice.sender,
                    talker = notice.talker,
                    rawText = notice.text,
                    createdAt = System.currentTimeMillis(),
                )
            )
            doneIds.add(notice.id)
        }

        if (items.isNotEmpty()) {
            val array = org.json.JSONArray()
            for (item in items) array.put(item.toJson())
            MatterStore.putRemote(context, array.toString())
            // 新记录刚落地，顺手维护一次：过期项和各类型的时效全靠 prune，
            // 而它原来只挂在「打开通知代办面板」那条路上（全项目仅一处调用），
            // 用户不点进来就永远不清，记录只增不减。放在这里等于只要还有
            // 消息在提取，存储就会自己收敛。
            runCatching { MatterStore.prune(context) }
                .onFailure { MoodLog.w("AIDE_PRUNE_FAILED " + it.javaClass.simpleName) }
        }
        AideInbox.remove(context, doneIds)
        refreshWidget(context)
        MoodLog.i("AIDE_DIGEST_DONE batch=${batch.size} extracted=${items.size} done=${doneIds.size}")
    }
}
