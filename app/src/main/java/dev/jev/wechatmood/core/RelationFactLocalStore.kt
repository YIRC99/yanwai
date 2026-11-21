package dev.jev.wechatmood.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.jev.wechatmood.core.AnalysisCacheDatabase
import dev.jev.wechatmood.reply.RelationFactStore
import java.io.File

/**
 * 微信进程自己的关系档案库。
 *
 * 之前档案存在言外 App 的数据库里，微信进程要通过设置服务跨进程读；App 被 ColorOS
 * 冻结或杀掉时读取直接失败，界面就变成空档案。现在档案跟着微信进程走，
 * 路径在微信自己的 files 目录下，微信活着就能读写，不再依赖另一个进程。
 */
internal object RelationFactLocalStore {
    private var store: RelationFactStore? = null

    @Synchronized fun open(context: Context): RelationFactStore {
        store?.let { return it }
        val dir = File(context.filesDir, "yanwai").apply { mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(File(dir, "relation_facts_v1.db"), null)
        val adapter = object : AnalysisCacheDatabase {
            override fun execute(sql: String, args: List<String>) {
                if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
            }

            override fun query(sql: String, args: List<String>): String? =
                db.rawQuery(sql, args.toTypedArray()).use { if (it.moveToFirst()) it.getString(0) else null }

            override fun queryRows(sql: String, args: List<String>): List<String> =
                db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                    val rows = ArrayList<String>(cursor.count)
                    while (cursor.moveToNext()) cursor.getString(0)?.let(rows::add)
                    rows
                }

            override fun close() = db.close()
        }
        return RelationFactStore(adapter).also { store = it }
    }
}
