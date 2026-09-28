package dev.jev.wechatmood.core

/** Presentation only: deliberately excluded from analysis identity and cache keys. */
data class CardDisplaySettings(val intent: Boolean = true, val concern: Boolean = true, val tone: Boolean = true) {
    companion object {
        const val KEY_INTENT = "card_show_intent"
        const val KEY_CONCERN = "card_show_concern"
        const val KEY_TONE = "card_show_tone"
        val KEYS = listOf(KEY_INTENT, KEY_CONCERN, KEY_TONE)
        fun load(read: (String) -> Boolean?) = CardDisplaySettings(
            read(KEY_INTENT) ?: true, read(KEY_CONCERN) ?: true, read(KEY_TONE) ?: true)
    }
}
