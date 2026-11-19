package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import org.json.JSONObject
import java.io.File

/**
 * 我的风格的持久化：自己写 JSON 文件，不走 SharedPreferences。
 *
 * 这台机器上 SharedPreferences 的 commit() 一直返回 true，但磁盘文件几个月不动
 * （goutoujunshi_config.xml 的 mtime 停在几天前，内容只剩一个 settings_generation）。
 * 表现就是采集到的样本、游标、指纹重启即丢。
 *
 * 这里改成写临时文件再 rename，写入结果可以立刻读文件验证，不依赖框架的缓存机制。
 */
object WarReviewStore {
    private const val FILE = "war_review.json"
    private val lock = Any()
    private var cache: JSONObject? = null

    /** 只读快照。读失败时返回空快照——调用方只读，用空数据不会破坏磁盘内容。 */
    fun read(context: Context): Snapshot = synchronized(lock) {
        val obj = runCatching { load(context) }.getOrElse {
            MoodLog.w("WAR_REVIEW_LOAD_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
            null
        }
        // obj == null 说明文件损坏且无备份可救：给调用方一个明确的失败信号
        Snapshot(obj ?: JSONObject(), ok = obj != null)
    }

    /**
     * 读改写，一次原子落盘。
     *
     * 读失败时必须中止：以前这里 load 失败会静默返回空对象，接着把空对象写回磁盘，
     * 于是「读一次失败」就变成「历史数据被永久清空」——白屏（进程冷启动）后待整理
     * 样本消失就是这个原因。宁可这一轮不写，也不能用空数据覆盖。
     */
    fun edit(context: Context, block: Editor.() -> Unit) = synchronized(lock) {
        // load 返回 null 表示「文件损坏且没有备份可救」。这时候必须放弃这一轮写入：
        // 继续用空对象写下去，就会把磁盘上的历史内容覆盖成「只有这次新增的几条」——
        // 表现就是「没点整理，攒着的样本却消失了」。
        val obj = runCatching { load(context) }.getOrElse {
            MoodLog.w("WAR_REVIEW_LOAD_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
            null
        } ?: return
        Editor(obj).block()
        write(context, obj)
    }

    private fun load(context: Context): JSONObject? {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val backup = File(context.filesDir, "$FILE.bak")
        // 文件不存在是正常的首次运行，返回空对象即可（但不写缓存，下次仍会检查磁盘）
        if (!file.exists()) return JSONObject()
        // IO 失败就让异常抛出去：上层据此放弃这次写入，避免覆盖
        val text = file.readText()
        val obj = runCatching { JSONObject(text) }.getOrElse { error ->
            MoodLog.w("WAR_REVIEW_CORRUPT " + error.javaClass.simpleName)
            // 主文件坏了先看备份：以前这里直接返回空对象，等于把历史数据判了死刑，
            // 冷启动（白屏）后就再也看不到「还没并进画像的话」。
            val recovered = runCatching {
                if (backup.exists()) JSONObject(backup.readText()) else null
            }.getOrNull()
            if (recovered != null) {
                MoodLog.w("WAR_REVIEW_RECOVERED_FROM_BAK keys=" + recovered.length())
                // 用备份把主文件修回来，避免下次再走一遍恢复
                runCatching { atomicWrite(file, recovered.toString()) }
                recovered
            } else {
                // 没有备份可救：留一份证据但**不删除原文件**，然后返回 null。
                // 以前这里返回空对象，调用方会拿它当基线继续写，等于亲手把旧数据覆盖掉。
                runCatching { file.copyTo(File(file.parentFile, "$FILE.corrupt"), overwrite = true) }
                return null
            }
        }
        cache = obj
        // 首次读到就补一份底：备份只在写入时生成的话，第一次写入之前是空窗期
        runCatching { if (!backup.exists()) atomicWrite(backup, obj.toString()) }
        return obj
    }

    private fun write(context: Context, obj: JSONObject) {
        val text = runCatching { obj.toString() }.getOrElse {
            MoodLog.w("WAR_REVIEW_WRITE_FAILED serialize " + it.javaClass.simpleName)
            return
        }
        // 主文件和备份都走「写临时文件 → 原子替换」。
        //
        // 以前是 tmp.renameTo(file)，失败就退回 file.writeText(text)。而 Java 的
        // File.renameTo 在目标已存在时并不保证成功，一旦退回直接写正式文件，
        // 进程在这期间被杀就会留下写了一半的 JSON。表现就是：MIUI 杀掉后台进程，
        // 下次冷启动（进 app 白屏一下）读到残缺文件 → 读空 → 下一条消息进来把
        // 「只有新消息」的内容覆盖上去 → 攒着的待整理样本消失。
        if (!atomicWrite(File(context.filesDir, FILE), text)) return
        runCatching { atomicWrite(File(context.filesDir, "$FILE.bak"), text) }
        cache = obj
    }

    /**
     * 写临时文件再整份替换。
     *
     * 失败返回 false，调用方放弃这一轮——宁可这次不写，也不能直接往正式文件里写，
     * 那是「写一半被杀 → 文件残缺」的唯一入口。
     */
    private fun atomicWrite(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            // 个别文件系统不支持原子替换：退回普通整份替换，
            // 仍然不会出现「正式文件被写一半」的状态。
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrElse {
        MoodLog.w("WAR_REVIEW_ATOMIC_WRITE_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
        false
    }

    /**
     * 只读快照。
     *
     * [ok] 表示这次读取是否成功。读失败时 obj 是空的，调用方如果拿它当基线
     * 去合并再写回，就会把磁盘上的真实内容覆盖成空——syncRemote 必须据此放弃。
     */
    class Snapshot(private val obj: JSONObject, val ok: Boolean = true) {
        fun getString(key: String, fallback: String = ""): String = obj.optString(key, fallback)
        fun getInt(key: String, fallback: Int = 0): Int = obj.optInt(key, fallback)
        fun getLong(key: String, fallback: Long = 0L): Long = obj.optLong(key, fallback)
        fun getBoolean(key: String, fallback: Boolean = false): Boolean = obj.optBoolean(key, fallback)
    }

    class Editor(private val obj: JSONObject) {
        fun putString(key: String, value: String?) {
            if (value == null) obj.remove(key) else obj.put(key, value)
        }

        fun putInt(key: String, value: Int) {
            obj.put(key, value)
        }

        fun putLong(key: String, value: Long) {
            obj.put(key, value)
        }

        fun putBoolean(key: String, value: Boolean) {
            obj.put(key, value)
        }

        fun remove(key: String) {
            obj.remove(key)
        }
    }
}
