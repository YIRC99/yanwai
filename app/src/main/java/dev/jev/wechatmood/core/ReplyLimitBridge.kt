package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ReplyLimitBridge {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-reply-limit") }
    private fun queue(context: Context) = ReplyLimitQueue({ worker.execute(it) }, { account ->
        val result = requireNotNull(SettingsTransport.call(context, "reply_limit_get", account, null))
        result.getInt("limit", ReplyHistoryLimit.DEFAULT).also { require(ReplyHistoryLimit.valid(it)) }
    }, { account, value ->
        val result = SettingsTransport.call(context, "reply_limit_put", account,
            Bundle().apply { putInt("limit", value) })
        check(result?.getBoolean("saved") == true)
    })

    suspend fun load(context: Context, account: String?): Int = suspendCancellableCoroutine { continuation ->
        queue(context.applicationContext).load(account) { result ->
            if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    }
    fun save(context: Context, owner: ReplyIdentityOwner, value: Int, verify: () -> String?, complete: (Boolean) -> Unit) =
        queue(context.applicationContext).save(owner, value, verify, complete)
}
