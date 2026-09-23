package dev.jev.wechatmood.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块日志。
 *
 * 微信进程里的日志没法直接看（没有终端），所以除了 Logcat 还写一份到文件，
 * 设置页读这个文件显示。探索模式的输出量很大，用固定上限的环形截断，
 * 不让它无限长。
 */
object MoodLog {

    private const val MAX_BYTES = 512 * 1024L
    private const val LOGCAT_TAG = "WeChatMood"

    private var logFile: File? = null
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        logFile = File(context.filesDir, "mood.log")
        ready = true
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String, throwable: Throwable? = null) {
        write("E", message + (throwable?.let { "\n  ${it.javaClass.name}: ${it.message}" } ?: ""))
    }

    /** 给探索模式用：直接落盘一大段多行文本，避免每行都加时间戳前缀。 */
    fun dump(title: String, body: String) {
        write("D", "===== $title =====\n$body\n===== /$title =====")
    }

    @Synchronized
    private fun write(level: String, message: String) {
        when (level) {
            "I" -> Log.i(LOGCAT_TAG, message)
            "W" -> Log.w(LOGCAT_TAG, message)
            "E" -> Log.e(LOGCAT_TAG, message)
            else -> Log.d(LOGCAT_TAG, message)
        }
        val f = logFile ?: return
        runCatching {
            val stamp = timeFormat.format(Date())
            f.appendText("$stamp [$level] $message\n")
            rotateIfNeeded(f)
        }.onFailure { Log.e(LOGCAT_TAG, "写日志失败", it) }
    }

    /** 超过上限就丢掉前半段，保留最近的内容。 */
    private fun rotateIfNeeded(f: File) {
        if (f.length() <= MAX_BYTES) return
        val text = f.readText()
        val keep = text.substring(text.length / 2)
        f.writeText("[日志超过上限，前半段已丢弃]\n$keep")
    }

    fun read(): String {
        val f = logFile ?: return ""
        return runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")
    }

    fun clear() {
        runCatching { logFile?.writeText("") }
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
}
