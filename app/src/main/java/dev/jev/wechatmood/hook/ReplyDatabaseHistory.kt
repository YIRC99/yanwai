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

    suspend fun load(loaded: ReplyContext): ReplyContext = withContext(Dispatchers.IO) {
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
                val id = it.getColumnIndexOrThrow("msgId")
                val type = it.getColumnIndexOrThrow("type")
                val sent = it.getColumnIndexOrThrow("isSend")
                val content = it.getColumnIndexOrThrow("content")
                val talker = it.getColumnIndexOrThrow("talker")
                val time = it.getColumnIndexOrThrow("createTime")
                buildList {
                    while (size < ReplyContext.MAX_MESSAGES + 1 && it.moveToNext()) {
                        active.ensureActive()
                        add(MessageMetadata(it.getInt(type), it.getInt(sent), it.getString(content).orEmpty(),
                            it.getString(talker).orEmpty(), it.getLong(id), it.getLong(time)))
                    }
                }
            }
        } }
        ReplyHistoryReader.read(loaded, sources) { active.ensureActive() }.also {
            MoodLog.i("REPLY_HISTORY_RESULT source=${it.source} count=${it.messages.size} trimmed=${it.trimmed}")
        }
    }
}
