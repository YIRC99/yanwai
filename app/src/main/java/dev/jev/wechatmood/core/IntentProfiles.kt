package dev.jev.wechatmood.core

import dev.jev.wechatmood.reply.*

/** Reuse provider validation/catalog while keeping every intent credential separate. */
object IntentProfiles {
    const val KEY_PROVIDER = "intent_provider"
    private fun intentKey(key: String) = key.replaceFirst("reply_", "intent_")
    fun load(provider: ReplyProvider, read: (String) -> String?) = ReplyProfiles.load(provider) { read(intentKey(it)) }
    fun valuesToSave(provider: ReplyProvider, settings: ReplySettings, read: (String) -> String?) =
        ReplyProfiles.valuesToSave(provider, settings) { read(intentKey(it)) }.mapKeys { intentKey(it.key) }
}
