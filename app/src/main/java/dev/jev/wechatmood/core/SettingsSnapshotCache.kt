package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 微信进程冷启动时的设置回落。
 *
 * 微信进程和言外进程 UID 不同，Android 10+ 沙箱下唯一可行的落盘位置是微信自己的
 * 私有目录。密钥用本进程 UID 在 Android Keystore 里的 alias，言外主进程读不到，
 * 也不会随备份导出（allowBackup=false）。
 *
 * 任何一步失败都只是「没有快照」，读取会退回原来的 Provider 通道，不影响功能。
 */
object SettingsSnapshotCache {
    private const val PREFS = "yanwai_settings_snapshot"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_SAVED_AT = "saved_at"
    private const val ALIAS = "yanwai_settings_snapshot_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun save(context: Context, snapshot: Bundle) {
        runCatching {
            val plain = toBytes(snapshot)
            val sealed = seal(plain) ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PAYLOAD, sealed)
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .commit()
        }.onFailure { MoodLog.w("SNAPSHOT_SAVE_FAILED ${it.javaClass.simpleName}") }
    }

    fun load(context: Context): Bundle? = runCatching {
        val sealed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null) ?: return null
        val plain = unseal(sealed) ?: return null
        fromBytes(plain)
    }.onFailure { MoodLog.w("SNAPSHOT_LOAD_FAILED ${it.javaClass.simpleName}") }.getOrNull()

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun toBytes(snapshot: Bundle): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            snapshot.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun fromBytes(plain: ByteArray): Bundle? {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(plain, 0, plain.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    internal fun seal(plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }.onFailure { MoodLog.w("SNAPSHOT_SEAL_FAILED ${it.javaClass.simpleName}") }.getOrNull()

    internal fun unseal(sealed: String): ByteArray? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(GCM_TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
    }.onFailure { MoodLog.w("SNAPSHOT_UNSEAL_FAILED ${it.javaClass.simpleName}") }.getOrNull()

    internal fun sealText(value: String): String? = seal(value.toByteArray(Charsets.UTF_8))

    internal fun unsealText(sealed: String): String? = unseal(sealed)?.toString(Charsets.UTF_8)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        return generator.generateKey()
    }
}
