package dev.jev.wechatmood.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Both transports use the same UID-scoped operations. Never replay a possibly completed write. */
object SettingsTransport {
    private interface Connection : AutoCloseable {
        fun call(method: String, arg: String?, extras: Bundle?): Bundle?
    }
    private var lastFallbackLog = -60_000L

    fun call(context: Context, method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = context.applicationContext ?: context
        return SettingsTransportRoute.call({
            // Failure to acquire happens BEFORE dispatch, so falling back cannot duplicate a write.
            app.contentResolver.acquireUnstableContentProviderClient(SettingsProvider.URI)?.let { client ->
                object : Connection {
                    override fun call(method: String, arg: String?, extras: Bundle?) = client.call(method, arg, extras)
                    override fun close() = client.close()
                }
            }
        }, { bind(app) }) { it.call(method, arg, extras) }
    }

    private fun bind(context: Context): Connection {
        check(Looper.myLooper() != Looper.getMainLooper()) { "设置备用连接必须在后台读取" }
        val ready = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("设置连接已断开"))
            }
            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("设置服务未提供连接"))
            }
            override fun onBindingDied(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("设置服务已更新，请重试"))
            }
        }
        fun release() { runCatching { context.unbindService(connection) } }
        try {
            val intent = Intent().setComponent(ComponentName("dev.jev.wechatmood",
                "dev.jev.wechatmood.core.SettingsConnectionService"))
            check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "设置服务无法连接" }
            val remote = ISettingsConnection.Stub.asInterface(ready.get(4, TimeUnit.SECONDS))
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFallbackLog >= 60_000L) {
                    MoodLog.i("BRIDGE_BOUND_CONNECTED 设置服务已通过备用通道连接")
                    lastFallbackLog = now
                }
            }
            return object : Connection {
                override fun call(method: String, arg: String?, extras: Bundle?) = remote.call(method, arg, extras)
                override fun close() = release()
            }
        } catch (error: Exception) {
            release()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw SettingsConnectionUnavailableException(error)
        }
    }
}

class SettingsConnectionUnavailableException(cause: Throwable) : IllegalStateException(
    "无法连接言外设置服务；请允许言外后台运行，并检查应用隐藏或分身空间设置。无需测试模型。", cause)
