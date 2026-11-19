package com.dogheadstrategist.chat.hook

import android.app.Activity

/** Owns the in-chat entry; the reply drawer is the only surface mounted in a conversation. */
class HostUi(private val activity: Activity) {
    private val replyUi = ReplyHostUi(activity)
    private var talker: String? = null

    /** [value] is kept for the caller's wording; the drawer only needs the current conversation. */
    fun showStatus(@Suppress("UNUSED_PARAMETER") value: String, currentTalker: String? = null) {
        talker = currentTalker
        replyUi.update(currentTalker)
    }

    fun showSettings() {
        replyUi.hide()
        talker = null
    }

    fun hide() {
        replyUi.hide()
        talker = null
    }

    fun dispose() {
        hide()
        replyUi.dispose()
    }
}
