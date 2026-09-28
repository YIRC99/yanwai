package dev.jev.wechatmood.reply

import java.util.WeakHashMap

/** Retry only on a later host rebuild; never schedule a rebuild or retain a panel. */
internal class ReplyPlusRebuildGuard {
    private data class State(var active: Boolean = false, var unsafe: Boolean = false)
    private val states = WeakHashMap<Any, State>()

    fun begin(panel: Any): Boolean {
        val state = states.getOrPut(panel) { State() }
        if (state.active || state.unsafe) return false
        state.active = true
        return true
    }

    fun finish(panel: Any, unsafe: Boolean = false) {
        val state = states[panel] ?: return
        state.active = false
        state.unsafe = unsafe
    }
}
