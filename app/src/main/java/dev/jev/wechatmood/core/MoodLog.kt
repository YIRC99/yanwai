package dev.jev.wechatmood.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Each process keeps its own journal; exporting in WeChat never needs the module bridge. */
object MoodLog {
    private const val TAG = "WeChatMood"
    private var journal: DiagnosticJournal? = null
    private var early = ""
    private val secrets = mutableSetOf<String>()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var frameworkSink: ((String) -> Unit)? = null

    @Synchronized fun protect(secret: String) { if (secret.isNotBlank()) secrets.add(secret) }
    @Synchronized fun sanitize(text: String) = DiagnosticText.sanitize(text, secrets)

    @Synchronized fun init(context: Context) {
        if (journal != null) return
        journal = DiagnosticJournal(File(context.filesDir, "mood.log"))
        if (early.isNotBlank()) journal?.append(early)
        early = ""
        i("PROCESS_START package=${context.packageName} uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} module=${dev.jev.wechatmood.BuildConfig.VERSION_NAME} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, throwable: Throwable? = null) =
        write("E", if (throwable == null) message else DiagnosticText.failure(message, throwable))
    fun dump(title: String, body: String) = write("D", "===== $title =====\n$body\n===== /$title =====")

    @Synchronized private fun write(level: String, message: String) {
        val safe = sanitize(message).take(32 * 1024)
        val line = "${format.format(Date())} [$level] $safe\n"
        runCatching { Log.println(if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO, TAG, safe) }
        journal?.append(line) ?: run { early = (early + line).takeLast(128 * 1024) }
        val lifecycleEvent = listOf("PROCESS_START", "ENVIRONMENT", "BRIDGE_RECOVERED", "SYNC_RECEIVED", "SWITCH_SAVED")
            .any(message::startsWith)
        if (level == "E" || level == "W" || lifecycleEvent) {
            runCatching { frameworkSink?.invoke("$TAG $line") }
        }
    }

    @Synchronized fun read(): String = sanitize(buildString {
        journal?.diskFailure?.let { appendLine("[LOG_DISK_FAILURE] $it；以下保留内存日志，请在关闭微信前导出。") }
        append(journal?.read() ?: early)
    })
}
