package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import org.json.JSONObject
import java.io.File

/**
 * 通知摘要的配置。
 *
 * 和我的风格一样自己写 JSON 文件：这台机器上 SharedPreferences 的 commit() 会返回成功
 * 但磁盘文件不动，设置重启就丢。
 */
object CommunicatePrefs {
    private const val FILE = "communicate.json"

    private const val KEY_MAX_CHARS = "summary_max_chars"
    private const val KEY_MIN_TRIGGER_CHARS = "min_trigger_chars"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENDER_MAX_CHARS = "sender_max_chars"
    private const val KEY_AI_TIMEOUT_SEC = "ai_timeout_sec"
    private const val KEY_DISPLAY_SEC = "display_sec"
    private const val KEY_USE_ROOT_JUMP = "use_root_jump"

    /** 通知正文的字符上限。太长会被系统折行折叠，看着乱。 */
    const val MIN_CHARS = 4
    const val MAX_CHARS = 16
    const val DEFAULT_CHARS = 16

    /** 发信人名的字符上限。有些微信名特别长，不压会把标题挤爆。 */
    const val MIN_SENDER_CHARS = 3
    const val MAX_SENDER_CHARS = 12
    const val DEFAULT_SENDER_CHARS = 6

    /** 触发字数：正文短于这个长度就不打扰。只在正文里数，不含发信人名称。 */
    const val MIN_TRIGGER_CHARS = 0
    const val MAX_TRIGGER_CHARS = 80
    const val DEFAULT_TRIGGER_CHARS = 0

    private val lock = Any()
    private var cache: JSONObject? = null

    /** 通知摘要总开关。默认开着——装完就能用，不想被打扰再去关。 */
    fun enabled(context: Context): Boolean = synchronized(lock) {
        load(context)?.optBoolean(KEY_ENABLED, true) ?: true
    }

    fun setEnabled(context: Context, value: Boolean) = put(context, KEY_ENABLED, value)

    /** 通知正文最多显示多少字。 */
    fun summaryMaxChars(context: Context): Int = synchronized(lock) {
        (load(context)?.optInt(KEY_MAX_CHARS, DEFAULT_CHARS) ?: DEFAULT_CHARS)
            .coerceIn(MIN_CHARS, MAX_CHARS)
    }

    fun setSummaryMaxChars(context: Context, value: Int) = put(context, KEY_MAX_CHARS, value.coerceIn(MIN_CHARS, MAX_CHARS))

    /** 通知标题最多显示多少字。 */
    fun senderMaxChars(context: Context): Int = synchronized(lock) {
        (load(context)?.optInt(KEY_SENDER_MAX_CHARS, DEFAULT_SENDER_CHARS) ?: DEFAULT_SENDER_CHARS)
            .coerceIn(MIN_SENDER_CHARS, MAX_SENDER_CHARS)
    }

    fun setSenderMaxChars(context: Context, value: Int) =
        put(context, KEY_SENDER_MAX_CHARS, value.coerceIn(MIN_SENDER_CHARS, MAX_SENDER_CHARS))

    /** AI 归纳最多等多久，超时就直接用原文。 */
    const val MIN_AI_TIMEOUT_SEC = 3
    const val MAX_AI_TIMEOUT_SEC = 20
    const val DEFAULT_AI_TIMEOUT_SEC = 8

    /** AI 归纳最多等多久。超时就用原文，不让通知一直不出现。 */
    fun aiTimeoutSec(context: Context): Int = synchronized(lock) {
        (load(context)?.optInt(KEY_AI_TIMEOUT_SEC, DEFAULT_AI_TIMEOUT_SEC) ?: DEFAULT_AI_TIMEOUT_SEC)
            .coerceIn(MIN_AI_TIMEOUT_SEC, MAX_AI_TIMEOUT_SEC)
    }

    fun setAiTimeoutSec(context: Context, value: Int) =
        put(context, KEY_AI_TIMEOUT_SEC, value.coerceIn(MIN_AI_TIMEOUT_SEC, MAX_AI_TIMEOUT_SEC))

    /** 归纳通知在通知栏停留多久，到点自动消失。 */
    const val MIN_DISPLAY_SEC = 3
    const val MAX_DISPLAY_SEC = 30
    const val DEFAULT_DISPLAY_SEC = 8

    /** 归纳通知停留多久。到点自动消失，不用手动划掉。 */
    fun displaySec(context: Context): Int = synchronized(lock) {
        (load(context)?.optInt(KEY_DISPLAY_SEC, DEFAULT_DISPLAY_SEC) ?: DEFAULT_DISPLAY_SEC)
            .coerceIn(MIN_DISPLAY_SEC, MAX_DISPLAY_SEC)
    }

    fun setDisplaySec(context: Context, value: Int) =
        put(context, KEY_DISPLAY_SEC, value.coerceIn(MIN_DISPLAY_SEC, MAX_DISPLAY_SEC))

    /**
     * 点击通知跳会话时，是否只走 root。
     *
     * 微信的 ChattingUI 没 exported，普通 uid 必然吃 SecurityException。
     * 开了这个开关就不再尝试普通方式，直接用 su 代发启动命令。
     * 默认关，让用户自己决定要不要弹授权。
     */
    fun useRootJump(context: Context): Boolean = synchronized(lock) {
        load(context)?.optBoolean(KEY_USE_ROOT_JUMP, false) ?: false
    }

    fun setUseRootJump(context: Context, value: Boolean) = put(context, KEY_USE_ROOT_JUMP, value)

    /** 正文至少多少字才调 AI 总结。0 表示每条都总结。 */
    fun minTriggerChars(context: Context): Int = synchronized(lock) {
        (load(context)?.optInt(KEY_MIN_TRIGGER_CHARS, DEFAULT_TRIGGER_CHARS) ?: DEFAULT_TRIGGER_CHARS)
            .coerceIn(MIN_TRIGGER_CHARS, MAX_TRIGGER_CHARS)
    }

    fun setMinTriggerChars(context: Context, value: Int) =
        put(context, KEY_MIN_TRIGGER_CHARS, value.coerceIn(MIN_TRIGGER_CHARS, MAX_TRIGGER_CHARS))

    private fun put(context: Context, key: String, value: Any) = synchronized(lock) {
        // load 返回 null = 文件损坏且无备份可救。放弃这次写入，
        // 否则会拿空对象把磁盘上的其它配置一起覆盖掉。
        val obj = load(context) ?: return
        obj.put(key, value)
        write(context, obj)
    }

    private fun load(context: Context): JSONObject? {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val backup = File(context.filesDir, "$FILE.bak")
        if (!file.exists()) return JSONObject()
        // IO 失败让异常抛出去，调用方据此放弃写入
        val text = file.readText()
        val obj = runCatching { JSONObject(text) }.getOrElse { error ->
            MoodLog.w("COMMUNICATE_CORRUPT " + error.javaClass.simpleName)
            // 先看备份：以前这里直接 getOrDefault(空对象)，等于把配置判死刑
            val recovered = runCatching {
                if (backup.exists()) JSONObject(backup.readText()) else null
            }.getOrNull()
            if (recovered != null) {
                MoodLog.w("COMMUNICATE_RECOVERED_FROM_BAK keys=" + recovered.length())
                runCatching { atomicWrite(file, recovered.toString()) }
                recovered
            } else {
                // 留证据但不删原文件，返回 null 让调用方放弃写入
                runCatching { file.copyTo(File(file.parentFile, "$FILE.corrupt"), overwrite = true) }
                return null
            }
        }
        cache = obj
        runCatching { if (!backup.exists()) atomicWrite(backup, obj.toString()) }
        return obj
    }

    private fun write(context: Context, obj: JSONObject) {
        val text = runCatching { obj.toString() }.getOrElse {
            MoodLog.w("COMMUNICATE_WRITE_FAILED serialize " + it.javaClass.simpleName)
            return
        }
        // 主文件和备份都原子替换：以前 renameTo 失败会退回 file.writeText，
        // 进程那一刻被杀就留下半截 JSON，冷启动后读到空 → 后续写入覆盖配置。
        if (!atomicWrite(File(context.filesDir, FILE), text)) return
        runCatching { atomicWrite(File(context.filesDir, "$FILE.bak"), text) }
        cache = obj
    }

    /** 写临时文件再整份替换。失败返回 false，调用方放弃这一轮。 */
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
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrElse {
        MoodLog.w("COMMUNICATE_ATOMIC_WRITE_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
        false
    }
}
