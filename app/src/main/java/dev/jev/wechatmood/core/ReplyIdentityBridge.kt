package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All persistence goes through the existing UID-restricted provider, never host storage. */
object ReplyIdentityBridge {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-reply-identity") }
    private fun queue(context: Context) = ReplyIdentityQueue({ worker.execute(it) }, { key ->
        val result = context.contentResolver.call(SettingsProvider.URI, "reply_identity_get", key.value, null)
        ReplyIdentitySetting.decode(requireNotNull(result?.getString("payload")))
    }, { key, value ->
        val result = context.contentResolver.call(SettingsProvider.URI, "reply_identity_put", key.value,
            Bundle().apply { putString("payload", value.encode()) })
        check(result?.getBoolean("saved") == true)
    })
    suspend fun load(context: Context, key: ReplyContactKey): ReplyIdentitySetting = suspendCancellableCoroutine { continuation ->
        queue(context.applicationContext).load(key) { result ->
            if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    }
    fun save(context: Context, owner: ReplyIdentityOwner, value: ReplyIdentitySetting, verify: () -> String?, complete: (Boolean) -> Unit) {
        queue(context.applicationContext).save(owner, value, verify, complete)
    }
}
