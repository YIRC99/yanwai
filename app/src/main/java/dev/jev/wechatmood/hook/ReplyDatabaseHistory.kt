package dev.jev.wechatmood.hook

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplyHistoryQuery
import dev.jev.wechatmood.reply.ReplyHistoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.AnalysisAccountIdentity
import dev.jev.wechatmood.core.AnalysisAccountScopes

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
        ?.replace('\\', '/')?.takeIf { it.endsWith("/EnMicroMsg.db") }?.let { AnalysisCacheKey.digest(it) }

    /**
     * 只保留与当前账号匹配的库。
     * 切账号后旧库可能还开着，不过滤就会把另一个账号的聊天算进统计、或把别人的消息当依据。
     * 账号确认不了时返回空，调用方退回当前可见页面。
     */
    internal fun databasesForScope(databases: List<Any>, accountScope: String?): List<Any> {
        if (accountScope == null || !accountScope.matches(Regex("[0-9a-f]{64}"))) return emptyList()
        return databases.filter { runCatching { scope(it) }.getOrNull() == accountScope }
    }

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
    fun matchesAnalysisAccount(input: AnalysisInput): Boolean =
        input.accountScope.matches(Regex("[0-9a-f]{64}")) && findAnalysisAccount(input) == input.accountScope

    /** Worker-thread only. A visible anchor must match exactly one account, including outgoing messages. */
    fun replyAccount(page: ReplyContext): String? {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        val sources = databases.mapNotNull { db -> runCatching {
            val account = scope(db) ?: return@runCatching null
            account to ReplyHistoryQuery { sql, args -> query(db, sql, args).use {
                if (it.moveToFirst()) listOf(metadata(it)) else emptyList()
            } }
        }.getOrNull() }
        return dev.jev.wechatmood.reply.ReplyAccountIdentity.resolve(page, sources)
    }

    private fun metadata(cursor: Cursor): MessageMetadata {
        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)).orEmpty()
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return MessageMetadata(number("type").toInt(), number("isSend").toInt(), string("content"),
            string("talker"), number("msgId"), number("createTime"), string("imgPath"), number("msgSvrId"))
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
        voiceTextUnchecked(record)
    }.getOrNull()

    /** 调用方已经确认过语音仍然有效时用这个，避免每轮轮询多两次全表查询。 */
    internal fun voiceTextUnchecked(record: VerifiedVoice): String? = runCatching {
        query(record.db, "SELECT content FROM VoiceTransText WHERE msgId = ? AND cmsgId = ? LIMIT 1",
            arrayOf(record.source.id.toString(), record.source.fileToken)).use {
            if (it.moveToFirst()) it.getString(0)?.trim()?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    /** 按时间范围取统计用消息（不限已加载页），用于「今天 / 最近 N 小时」。 */
    suspend fun statMessages(talker: String, sinceMillis: Long, limit: Int,
        accountScope: String? = null): List<MessageMetadata> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MessageMetadata>()
        // 账号无法确认时这里为空，调用方改用当前可见页面的数据。
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        var since = sinceMillis
        for (db in databases) {
            // 微信不同版本 createTime 有秒也有毫秒，先看一条样本再决定。
            val sample = runCatching {
                var value = 0L
                query(db, "SELECT createTime FROM message WHERE talker = ? LIMIT 1", arrayOf(talker)).use { cursor ->
                    if (cursor.moveToFirst()) value = cursor.getLong(0)
                }
                value
            }.getOrDefault(0L)
            since = if (sample > 1_000_000_000_000L) sinceMillis else sinceMillis / 1000
            runCatching {
                query(db, "SELECT msgId, type, isSend, content, talker, createTime, imgPath, msgSvrId FROM message " +
                    "WHERE talker = ? AND createTime >= ? ORDER BY createTime DESC LIMIT ?",
                    arrayOf(talker, since.toString(), limit.coerceAtLeast(1).toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
        }
        dev.jev.wechatmood.core.MoodLog.i(
            "STATS_RANGE talker=${talker.hashCode()} since=$since count=${out.size} " +
                "first=${out.lastOrNull()?.createdAt} last=${out.firstOrNull()?.createdAt}")
        out.reversed()
    }

    /** 档案条目回指依据：按 localId 取一条消息，只读，不改游标以外的任何状态。 */
    suspend fun messageById(talker: String, msgId: Long,
        accountScope: String? = null): MessageMetadata? = withContext(Dispatchers.IO) {
        if (msgId <= 0) return@withContext null
        // 依据消息也只在当前账号的库里找，不能拿另一个账号的同 msgId 顶上。
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        for (db in databases) {
            val found = runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND msgId = ? LIMIT 1",
                    arrayOf(talker, msgId.toString())).use { if (it.moveToFirst()) metadata(it) else null }
            }.getOrNull()
            if (found != null) return@withContext found
        }
        null
    }

    /**
     * 依据消息前后的文字，给档案条目补上下文。
     * 只按本地 msgId 排序取最近几条，查不到就返回空，不猜。
     */
    suspend fun nearbyText(talker: String, msgId: Long, accountScope: String? = null,
        limit: Int = 2): List<MessageMetadata> = withContext(Dispatchers.IO) {
        if (msgId <= 0) return@withContext emptyList()
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        val out = mutableListOf<MessageMetadata>()
        for (db in databases) {
            runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND type = 1 AND msgId < ? " +
                    "ORDER BY msgId DESC LIMIT ?", arrayOf(talker, msgId.toString(), limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
            runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND type = 1 AND msgId > ? " +
                    "ORDER BY msgId ASC LIMIT ?", arrayOf(talker, msgId.toString(), limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
            if (out.isNotEmpty()) break
        }
        out.sortedBy { it.createdAt }
    }

    /** 诊断用：列出主库的表名，便于确认群昵称实际存在哪张表。 */
    suspend fun tableNames(accountScope: String? = null): List<String> = withContext(Dispatchers.IO) {
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        val names = mutableListOf<String>()
        for (db in databases) {
            runCatching {
                query(db, "SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { names += it }
                    }
                }
            }
        }
        dev.jev.wechatmood.core.MoodLog.i("STATS_TABLES_RAW count=${names.size} sample=${names.take(12)}")
        val related = names.filter {
            it.contains("contact", true) || it.contains("stranger", true) || it.contains("nick", true) ||
                it.contains("userinfo", true) || it.contains("friend", true)
        }
        dev.jev.wechatmood.core.MoodLog.i("STATS_TABLES count=${names.size} related=$related")
        names
    }

    /**
     * 群成员昵称：逐个 wxid 查 rcontact / stranger，查不到就返回空。
     *
     * accountScope 为空时 [databasesForScope] 一个库都不选，名字便永远是空的——提醒标题
     * 就是这样掉成「未获取备注名」的。名字只用于显示，不参与统计口径，[relaxed] 为 true
     * 时直接用当前已打开的库（最近打开的优先）；要分账号算数的地方仍传 scope。
     */
    suspend fun displayNames(usernames: List<String>,
        accountScope: String? = null, relaxed: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        val wanted = usernames.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return@withContext emptyMap()
        val opened = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
        val databases = if (relaxed && accountScope == null) opened else databasesForScope(opened, accountScope)
        val result = mutableMapOf<String, String>()
        var errors = 0
        for (name in wanted) {
            for (db in databases) {
                // 单条查询，避免 IN 参数或字段名差异导致整批失败。
                val found = runCatching {
                    var value: String? = null
                    // 群消息里的 speaker 有时是微信号而不是 wxid，username 和 alias 一起查，
                    // 备注、昵称、微信号依次回退，能多认出一批人。
                    query(db, "SELECT conRemark, nickname, alias FROM rcontact WHERE username = ? OR alias = ? LIMIT 1",
                        arrayOf(name, name)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            value = (0..2).asSequence()
                                .mapNotNull { index -> runCatching { cursor.getString(index) }.getOrNull() }
                                .map { dev.jev.wechatmood.core.TextClean.plain(it).trim() }
                                .firstOrNull { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }
                        }
                    }
                    value
                }.onFailure { errors++ }.getOrNull()
                if (found != null) { result[name] = found; break }
            }
            if (name !in result) {
                for (db in databases) {
                    val found = runCatching {
                        var value: String? = null
                        query(db, "SELECT nickname FROM stranger WHERE username = ?", arrayOf(name)).use { cursor ->
                            if (cursor.moveToFirst()) value = runCatching { cursor.getString(0) }.getOrNull()
                                ?.let { dev.jev.wechatmood.core.TextClean.plain(it).trim() }
                                ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }
                        }
                        value
                    }.onFailure { errors++ }.getOrNull()
                    if (found != null) { result[name] = found; break }
                }
            }
        }
        dev.jev.wechatmood.core.MoodLog.i("STATS_NAMES wanted=${wanted.size} found=${result.size} errors=$errors")
        result
    }

    /**
     * 把联系人的哈希键反解回聊天对象。
     *
     * contactKey 是「账号 + 聊天对象」的摘要，只有本机 rcontact 能对回去。提醒需要显示名字、
     * 点通知进聊天，都缺不了这一层；旧档案没有目录记录时，只能在这里补一次。
     * 好友几千条、摘要几十毫秒，只在确实缺目录时调用。
     */
    suspend fun resolveContacts(keys: Set<String>,
        evidence: Map<String, List<Long>> = emptyMap()): Map<String, Pair<String, String>> =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext emptyMap()
            val result = mutableMapOf<String, Pair<String, String>>()
            val opened = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }

            fun scan(db: Any, account: String, sql: String, keyColumns: IntArray, nameColumns: IntArray) {
                runCatching {
                    query(db, sql, arrayOf()).use { cursor ->
                        while (result.size < keys.size && cursor.moveToNext()) {
                            // username 和 alias 都可能是消息里的 talker：对方改过微信号时，
                            // 同一个 rcontact 行会有两个可用标识，只按 username 算会永远对不上。
                            val candidates = ArrayList<String>()
                            for (index in keyColumns) {
                                val value = runCatching { cursor.getString(index) }.getOrNull()?.trim()
                                if (!value.isNullOrEmpty() && value.length <= 256 &&
                                    value.matches(Regex("[A-Za-z0-9_@.\\-]{1,256}"))) candidates.add(value)
                            }
                            var matched: Pair<String, String>? = null
                            for (candidate in candidates) {
                                val key = ReplyContactKey.of(account, candidate)?.value ?: continue
                                if (key in keys && key !in result) {
                                    matched = key to candidate
                                    break
                                }
                            }
                            val hit = matched ?: continue
                            val name = nameColumns.toList().asSequence()
                                .mapNotNull { index -> runCatching { cursor.getString(index) }.getOrNull() }
                                .map { dev.jev.wechatmood.core.TextClean.plain(it).trim() }
                                .firstOrNull { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }
                                .orEmpty()
                            result[hit.first] = hit.second to name
                            if (hit.second != candidates.firstOrNull()) {
                                MoodLog.i("RELATION_CONTACT_ALIAS_MATCH")
                            }
                        }
                    }
                }.onFailure { MoodLog.w("RELATION_CONTACT_RESOLVE_FAILED " + it.javaClass.simpleName) }
            }

            for (db in opened) {
                val account = runCatching { scope(db) }.getOrNull() ?: continue
                scan(db, account, "SELECT username, conRemark, nickname, alias FROM rcontact",
                    intArrayOf(0, 3), intArrayOf(1, 2, 3))
            }
            // 还认不出来的，用档案里存过的「依据消息」反推：同一 msgId 只有一个会话，
            // 拿到的 talker 算出的哈希必须和待查 key 一致才认，认错的可能性为零。
            if (result.size < keys.size && evidence.isNotEmpty()) {
                val accounts = opened.mapNotNull { db -> runCatching { scope(db) }.getOrNull() }
                for ((key, ids) in evidence) {
                    if (key !in keys || key in result) continue
                    for (id in ids.take(8)) {
                        if (id <= 0) continue
                        val talkers = opened.mapNotNull { db ->
                            runCatching {
                                query(db, "SELECT talker FROM message WHERE msgId = ? LIMIT 4", arrayOf(id.toString()))
                                    .use { cursor ->
                                        val list = ArrayList<String>(4)
                                        while (cursor.moveToNext()) {
                                            runCatching { cursor.getString(0) }.getOrNull()?.let { list.add(it) }
                                        }
                                        list
                                    }
                            }.getOrNull()
                        }.flatten()
                        val candidate = talkers.firstOrNull { talker ->
                            accounts.any { account -> ReplyContactKey.of(account, talker)?.value == key }
                        }
                        if (candidate != null) {
                            val name = displayNames(listOf(candidate), relaxed = true)[candidate].orEmpty()
                            result[key] = candidate to name
                            MoodLog.i("RELATION_CONTACT_EVIDENCE_MATCH")
                            break
                        }
                    }
                }
            }
            MoodLog.i("RELATION_CONTACT_RESOLVE wanted=" + keys.size + " found=" + result.size)
            result
        }

    suspend fun load(loaded: ReplyContext, limit: Int = ReplyContext.MAX_MESSAGES): ReplyContext = withContext(Dispatchers.IO) {
        require(limit in 1..ReplyContext.MAX_MESSAGES)
        val startedAt = android.os.SystemClock.elapsedRealtime()
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
            MoodLog.i("REPLY_HISTORY_RESULT requested=$limit handles=${databases.size} source=${it.source} " +
                "count=${it.messages.size} trimmed=${it.trimmed} took=${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                "quoted=${it.messages.count { message -> message.text.startsWith("【引用") }}")
        }
    }
}
