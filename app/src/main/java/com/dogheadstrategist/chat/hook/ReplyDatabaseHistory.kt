package com.dogheadstrategist.chat.hook

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.reply.ReplyContext
import com.dogheadstrategist.chat.reply.ReplyHistoryQuery
import com.dogheadstrategist.chat.reply.ReplyHistoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import com.dogheadstrategist.chat.voice.VoiceSource
import com.dogheadstrategist.chat.core.CacheKey
import com.dogheadstrategist.chat.core.AnalysisInput
import com.dogheadstrategist.chat.core.AnalysisAccountIdentity
import com.dogheadstrategist.chat.core.AnalysisAccountScopes

/** Reuses WeChat's open WCDB handle. Never opens files, obtains keys, writes, or closes its database. */
object ReplyDatabaseHistory {
    private val known = WeakHashMap<Any, Boolean>()
    private val handles = ArrayList<WeakReference<Any>>()
    private val moduleQuery = ThreadLocal<Boolean>()
    private val accountWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "yanwai-account") }
    private val accountScopes = AnalysisAccountScopes({ accountWorker.execute(it) }, ::findAnalysisAccount)
    fun accountScope(input: AnalysisInput): String = accountScopes.scope(input)
    fun pendingAccountScope(): String = accountScopes.pendingScope()
    fun resetAccountScope() = accountScopes.reset()
    private fun scope(db: Any): String? = (db.javaClass.getMethod("getPath").invoke(db) as? String)
        ?.replace('\\', '/')?.takeIf { it.endsWith("/EnMicroMsg.db") }?.let { CacheKey.digest(it) }

    fun install(loader: ClassLoader) {
        var hooks = 0
        for (name in listOf("com.tencent.wcdb.compat.SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase")) {
            runCatching {
                val clazz = Class.forName(name, false, loader)
                for (method in clazz.declaredMethods.filter { it.name == "rawQuery" || it.name == "rawQueryWithFactory" }) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.thisObject?.let { db -> runCatching { observe(db) } }
                        }
                    })
                    hooks++
                }
            }.onFailure { MoodLog.w("REPLY_HISTORY_HOOK_UNAVAILABLE ${it.javaClass.simpleName}") }
        }
        MoodLog.i("REPLY_HISTORY_HOOKS count=$hooks")
    }

    @Synchronized private fun observe(db: Any) {
        if (moduleQuery.get() == true) return
        val main = known.getOrPut(db) {
            val path = db.javaClass.getMethod("getPath").invoke(db) as? String
            path?.replace('\\', '/')?.substringAfterLast('/') == "EnMicroMsg.db"
        }
        if (!main) return
        val firstObservation = handles.none { it.get() === db }
        handles.removeAll { it.get() == null || it.get() === db }
        handles.add(0, WeakReference(db))
        while (handles.size > 4) handles.removeAt(handles.lastIndex)
        if (firstObservation) accountScopes.retryUnverified()
    }

    private fun query(db: Any, sql: String, args: Array<String>): Cursor {
        check(db.javaClass.getMethod("isOpen").invoke(db) == true)
        val method = db.javaClass.methods.first { it.name == "rawQuery" && it.parameterCount == 2 &&
            it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isAssignableFrom(Array<String>::class.java) }
        val previous = moduleQuery.get()
        moduleQuery.set(true)
        return try { method.invoke(db, sql, args) as? Cursor ?: error("Unsupported history cursor") }
        finally { moduleQuery.set(previous) }
    }

    /** Verify the visible target against its account before reusing or saving a durable result. */
    private fun findAnalysisAccount(input: AnalysisInput): String? {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        return databases.mapNotNull { db -> runCatching {
            val account = scope(db) ?: return@runCatching null
            query(db, "SELECT * FROM message WHERE talker = ? AND msgId = ? LIMIT 1",
                arrayOf(input.talker, input.messageId.toString())).use {
                account.takeIf { _ -> it.moveToFirst() && AnalysisAccountIdentity.matches(input, metadata(it)) }
            }
        }.getOrNull() }.distinct().singleOrNull()
    }
    /** Worker-thread only. A visible anchor must match exactly one account, including outgoing messages. */
    fun replyAccount(page: ReplyContext): String? {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        val sources = databases.mapNotNull { db -> runCatching {
            val account = scope(db) ?: return@runCatching null
            account to ReplyHistoryQuery { sql, args -> query(db, sql, args).use {
                if (it.moveToFirst()) listOf(metadata(it)) else emptyList()
            } }
        }.getOrNull() }
        return com.dogheadstrategist.chat.reply.ReplyAccountIdentity.resolve(page, sources)
    }

    /** 当前登录账号标识（数据库路径摘要），用于读取按账号存储的设置。 */
    fun account(): String? {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        return databases.firstNotNullOfOrNull { runCatching { scope(it) }.getOrNull() }
    }

    /**
     * 最近活跃的会话，带备注名/昵称，供跨进程同步用。
     *
     * 群聊同样包含。群聊在 rcontact 里的 username 就是 xxx@chatroom、nickname 就是群名，
     * talkerNick 取到的正是通知标题那个群名，两者对得上——群名反查 talker 本来就是靠这张表，
     * 能反查就说明能取名字。早先这里排除群聊，群消息因此永远拿不到 app 进程的记录副本，
     * 只能退回微信进程的队列路径生成，那条路受冻结影响、失败也不重试，
     * 表现就是「同一个群时而有卡片、时而没有」。
     */
    fun recentTalkers(limit: Int): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            val rows = runCatching {
                val list = mutableListOf<Pair<String, String>>()
                query(db, "SELECT talker, MAX(createTime) AS t FROM message " +
                    "WHERE talker != '' " +
                    "AND talker NOT LIKE 'gh_%' " +
                    "AND talker NOT IN ('filehelper','weixin','notifymessage','qqmail'," +
                    "'medianote','floatbottle','newsapp','fmessage','weibo','tmessage'," +
                    "'qmessage','mphelper','brandsessionholder','weixinpay','wxpay','weixincard') " +
                    "GROUP BY talker ORDER BY t DESC LIMIT ?",
                    arrayOf(limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val talker = cursor.getString(0).orEmpty()
                        if (talker.isNotBlank()) list += talker to talkerNick(db, talker)
                    }
                }
                list
            }.getOrNull()
            if (!rows.isNullOrEmpty()) return rows
        }
        return emptyList()
    }

    private val nickCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 群成员 wxid → 昵称/备注。
     *
     * 群消息正文的前缀存的是发送者 wxid，直接交给模型等于喂一串乱码，
     * 模型无法判断"这句话是谁说的"。换成昵称后，群聊里的多轮对话才真正可分。
     * 昵称基本不变，缓存到进程结束即可。
     */
    fun nickOfUsername(username: String): String? {
        if (username.isBlank()) return null
        val cached = nickCache[username]
        if (cached != null) return cached.ifBlank { null }
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            val nick = talkerNick(db, username)
            if (nick.isNotBlank()) {
                nickCache[username] = nick
                return nick
            }
        }
        nickCache[username] = ""
        return null
    }

    private fun talkerNick(db: Any, talker: String): String = runCatching {
        query(db, "SELECT conRemark, nickname, alias FROM rcontact WHERE username = ? LIMIT 1",
            arrayOf(talker)).use { cursor ->
            if (!cursor.moveToFirst()) "" else {
                (0..2).mapNotNull { runCatching { cursor.getString(it) }.getOrNull() }
                    .firstOrNull { !it.isNullOrBlank() }.orEmpty()
            }
        }
    }.getOrDefault("")

    /**
     * 昵称 → 会话标识。通知里只有昵称，要读聊天记录必须先换成 username。
     * 备注名优先于昵称（用户常用备注），并且只在联系人/群聊表里找。
     */
    /**
     * 通知标题反查会话 id。
     *
     * 通知里的昵称常带「[表情]」之类的标记（如「小倩倩[表情]」），而通讯录里是干净的
     * 名字，直接比较永远匹配不上。所以先按原样查，查不到再去掉标记重试。
     */
    /** 会话列表兜底匹配时扫多少条，够覆盖近期活跃会话即可。 */
    private val SESSION_LOOKUP_LIMIT = 120

    /**
     * 昵称归一化：把「同一个人在不同地方的不同写法」压成同一个字符串。
     *
     * 通知标题给的是占位写法（`小倩倩[表情]`），数据库里存的是真实 emoji（`小倩倩🙀`），
     * 两边直接比永远不相等。这里同时剥掉三种东西：
     *   1. 字面占位 `[表情]`、`[微笑]` 这类；2. 真实 emoji 字符；3. 空白。
     *
     * 只剥「表情」不剥汉字，所以不会把两个不同的人误判成同一个。
     */
    fun normalizeNick(raw: String): String = raw
        .replace(Regex("""\[[^\]]{1,10}]"""), "")
        .replace(Regex("""[\p{So}\p{Sk}\p{Cs}\uFE0F\u200D]"""), "")
        .replace(Regex("""\s+"""), "")
        .trim()

    fun resolveTalker(nickname: String): String? {
        if (nickname.isBlank()) return null
        val cleaned = normalizeNick(nickname)
        val candidates = if (cleaned.isNotEmpty() && cleaned != nickname) listOf(nickname, cleaned)
        else listOf(nickname)
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (name in candidates) {
            for (db in databases) {
                val found = runCatching {
                    query(db,
                        "SELECT username FROM rcontact WHERE (nickname = ? OR conRemark = ? OR alias = ?) " +
                            "AND username != '' LIMIT 1",
                        arrayOf(name, name, name)).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
                if (!found.isNullOrBlank()) {
                    return found
                }
            }
        }
        // 兜底：rcontact 里按 nickname / conRemark / alias 都查不到时，改查最近会话列表。
        // 通知标题常带表情占位（如「小倩倩[表情]」）或后缀，与 rcontact 里的记录对不齐；
        // 而会话列表的昵称是微信自己写的，两边清洗后往往能对上。
        runCatching {
            val targets = candidates.map { normalizeNick(it) }.filter { it.isNotEmpty() }
            for ((talker, nick) in recentTalkers(SESSION_LOOKUP_LIMIT)) {
                val clean = normalizeNick(nick)
                if (clean.isNotEmpty() && clean in targets) {
                    return talker
                }
            }
        }
        return null
    }

    /** 该会话最新一条消息（不限类型），用来判断用户是否已经回复。 */
    fun lastMessageMeta(talker: String): MessageMetadata? {
        if (talker.isBlank()) return null
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            val found = runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? ORDER BY createTime DESC LIMIT 1",
                    arrayOf(talker)).use { if (it.moveToFirst()) metadata(it) else null }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    /** 读某会话最近 [limit] 条文本消息，按时间正序返回。 */
    fun recentMessages(talker: String, limit: Int): List<MessageMetadata> {
        if (talker.isBlank() || limit <= 0) return emptyList()
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            val rows = runCatching {
                val list = mutableListOf<MessageMetadata>()
                query(db,
                    "SELECT * FROM message WHERE talker = ? AND (type = 1 OR type = 34) " +
                        "ORDER BY createTime DESC LIMIT ?",
                    arrayOf(talker, limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) list += metadata(cursor)
                }
                list
            }.getOrDefault(emptyList())
            if (rows.isNotEmpty()) return rows.reversed()
        }
        return emptyList()
    }


    /**
     * 当前库里的最大 msgId。
     * 「开启采集」时用它把游标推到当前位置——用户要的是「从现在起我怎么说话」，
     * 不是把过去几年的聊天记录翻出来重新学一遍。
     */
    fun maxMsgId(): Long {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        var max = 0L
        for (db in databases) {
            val value = runCatching {
                query(db, "SELECT MAX(msgId) FROM message", arrayOf()).use { cursor ->
                    if (cursor.moveToNext()) cursor.getLong(0) else 0L
                }
            }.getOrDefault(0L)
            if (value > max) max = value
        }
        return max
    }

    private fun metadata(cursor: Cursor): MessageMetadata {
        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)).orEmpty()
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return MessageMetadata(number("type").toInt(), number("isSend").toInt(), string("content"),
            string("talker"), number("msgId"), MessageMetadata.toMillis(number("createTime")), string("imgPath"), number("msgSvrId"))
    }

    /** A voice is tied to the exact open account database before reading text or invoking the host. */
    internal class VerifiedVoice(val db: Any, val source: VoiceSource, val message: Any?)

    internal fun voiceRecord(source: VoiceSource, messageClass: Class<*>?): VerifiedVoice? {
        if (source.id <= 0 || source.time <= 0 || source.fileToken.isBlank()) return null
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            val verified = runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND msgId = ? AND type = 34 LIMIT 1",
                    arrayOf(source.talker, source.id.toString())).use { cursor ->
                    if (!cursor.moveToFirst() || metadata(cursor).voiceSource() != source) return@use null
                    val instance = messageClass?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance()
                    instance?.let { messageClass.getMethod("convertFrom", Cursor::class.java).invoke(it, cursor) }
                    VerifiedVoice(db, source, instance)
                }
            }.getOrNull()
            if (verified != null) return verified
        }
        return null
    }

    internal fun voiceStillMatches(record: VerifiedVoice): Boolean = runCatching {
        query(record.db, "SELECT * FROM message WHERE talker = ? AND msgId = ? AND type = 34 LIMIT 1",
            arrayOf(record.source.talker, record.source.id.toString())).use {
            it.moveToFirst() && metadata(it).voiceSource() == record.source
        }
    }.getOrDefault(false)

    internal fun voiceText(record: VerifiedVoice): String? = runCatching {
        if (!voiceStillMatches(record)) return@runCatching null
        query(record.db, "SELECT content FROM VoiceTransText WHERE msgId = ? AND cmsgId = ? LIMIT 1",
            arrayOf(record.source.id.toString(), record.source.fileToken)).use {
            if (it.moveToFirst()) it.getString(0)?.trim()?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    suspend fun load(loaded: ReplyContext, limit: Int = ReplyContext.MAX_MESSAGES): ReplyContext = withContext(Dispatchers.IO) {
        require(limit in 1..ReplyContext.MAX_MESSAGES)
        val active = coroutineContext
        val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
        val sources = databases.map { db -> ReplyHistoryQuery { sql, args ->
            active.ensureActive()
            check(db.javaClass.getMethod("isOpen").invoke(db) == true)
            val cursor = query(db, sql, args)
            cursor.use {
                buildList {
                    while (size < limit + 1 && it.moveToNext()) {
                        active.ensureActive()
                        add(metadata(it))
                    }
                }
            }
        } }
        ReplyHistoryReader.read(loaded, sources, limit) { active.ensureActive() }.also {
            MoodLog.i("REPLY_HISTORY_RESULT requested=$limit handles=${databases.size} source=${it.source} count=${it.messages.size} trimmed=${it.trimmed}")
        }
    }
}
