package com.dogheadstrategist.chat.core

import java.io.Closeable
import java.security.MessageDigest

/** Minimal SQLite surface shared by the reply stores. */
interface CacheDatabase : Closeable {
    fun execute(sql: String, args: List<String> = emptyList())
    fun query(sql: String, args: List<String> = emptyList()): String?
}

/** No credentials or account paths are stored: namespaces and identities are hashes. */
object CacheKey {
    fun digest(vararg values: String): String {
        val source = values.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
