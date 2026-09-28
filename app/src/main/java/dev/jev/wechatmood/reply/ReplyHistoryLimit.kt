package dev.jev.wechatmood.reply

object ReplyHistoryLimit {
    const val DEFAULT = ReplyContext.MAX_MESSAGES
    fun valid(value: Int) = value in 1..ReplyContext.MAX_MESSAGES
    /** Null is cancellation. Invalid input never becomes a new selection. */
    fun parse(input: String?): Int? {
        if (input == null) return null
        val text = input.trim()
        require(text.isNotEmpty()) { "请输入条数" }
        require(text.all { it in '0'..'9' }) { "请输入整数" }
        val value = text.toIntOrNull()
        require(value != null && valid(value)) { "请输入 1～100 的整数" }
        return value
    }
}

/** Backed by the module's existing preferences, isolated by caller UID and verified account. */
class ReplyLimitPreferences(private val caller: Int, private val read: (String) -> Int?,
    private val write: (String, Int) -> Unit) {
    private fun key(account: String): String {
        require(account.matches(Regex("[0-9a-f]{64}")))
        return "reply_history_limit_${caller}_$account"
    }
    fun load(account: String): Int = read(key(account))?.takeIf(ReplyHistoryLimit::valid) ?: ReplyHistoryLimit.DEFAULT
    fun save(account: String, value: Int) {
        require(ReplyHistoryLimit.valid(value))
        write(key(account), value)
    }
}

/** FIFO writes outlive the drawer. A later reopen waits behind all earlier selections. */
class ReplyLimitQueue(private val dispatch: (() -> Unit) -> Unit, private val read: (String) -> Int,
    private val write: (String, Int) -> Unit) {
    fun load(account: String?, complete: (Result<Int>) -> Unit) = dispatch {
        complete(runCatching { if (account == null) ReplyHistoryLimit.DEFAULT else read(account) })
    }
    fun save(owner: ReplyIdentityOwner, value: Int, verify: () -> String?, complete: (Boolean) -> Unit) {
        require(ReplyHistoryLimit.valid(value))
        val account = owner.account ?: run { complete(false); return }
        dispatch {
            complete(runCatching {
                check(owner.acceptsAccount(verify()))
                write(account, value)
            }.isSuccess)
        }
    }
}
