package dev.jev.wechatmood.hook

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHistoryQuery
import dev.jev.wechatmood.reply.ReplyHistoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import dev.jev.wechatmood.voice.VoiceSource

/** Reuses WeChat's open WCDB handle. Never opens files, obtains keys, writes, or closes its database. */
object ReplyDatabaseHistory {
    private val known = WeakHashMap<Any, Boolean>()
    private val handles = ArrayList<WeakReference<Any>>()

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
        val main = known.getOrPut(db) {
            val path = db.javaClass.getMethod("getPath").invoke(db) as? String
            path?.replace('\\', '/')?.substringAfterLast('/') == "EnMicroMsg.db"
        }
        if (!main) return
        handles.removeAll { it.get() == null || it.get() === db }
        handles.add(0, WeakReference(db))
        while (handles.size > 4) handles.removeAt(handles.lastIndex)
    }

    private fun query(db: Any, sql: String, args: Array<String>): Cursor {
        check(db.javaClass.getMethod("isOpen").invoke(db) == true)
        val method = db.javaClass.methods.first { it.name == "rawQuery" && it.parameterCount == 2 &&
            it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isAssignableFrom(Array<String>::class.java) }
        return method.invoke(db, sql, args) as? Cursor ?: error("Unsupported history cursor")
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
            val rawQuery = db.javaClass.methods.first { method ->
                method.name == "rawQuery" && method.parameterCount == 2 &&
                    method.parameterTypes[0] == String::class.java && method.parameterTypes[1].isArray &&
                    method.parameterTypes[1].isAssignableFrom(Array<String>::class.java)
            }
            val cursor = rawQuery.invoke(db, sql, args) as? Cursor ?: error("Unsupported history cursor")
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
