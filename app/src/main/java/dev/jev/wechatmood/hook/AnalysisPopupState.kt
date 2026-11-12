package dev.jev.wechatmood.hook

/** Main-thread presentation state, independent of Android views and the analysis cache. */
internal class AnalysisPopupState(private val retention: Int = 256) {
    init { require(retention > 0) }
    private val seen = linkedSetOf<String>()
    var activeKey: String? = null
        private set

    fun offer(key: String): Boolean {
        if (activeKey == key) return true
        if (key in seen) return false
        remember(key)
        if (activeKey != null) return false
        activeKey = key
        return true
    }

    /** Explicit message-menu requests may reopen a dismissed result or replace the current one. */
    fun request(key: String) {
        activeKey?.let(::remember)
        seen.remove(key)
        activeKey = key
    }

    fun dismiss() {
        activeKey?.let(::remember)
        activeKey = null
    }

    private fun remember(key: String) {
        seen.add(key)
        while (seen.size > retention) seen.remove(seen.first())
    }
}
