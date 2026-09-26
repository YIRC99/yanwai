package dev.jev.wechatmood.reply

/** Prepared local evidence, independent of the last successful model result. */
class ReplyHistorySelection(private val talker: String, initial: ReplyContext? = null) {
    data class Ticket(val serial: Long, val limit: Int)
    private var serial = 0L
    private var active: Ticket? = null
    var context: ReplyContext? = initial?.takeIf { it.talker == talker }
        private set
    val loading: Boolean get() = active != null

    fun begin(limit: Int): Ticket {
        require(limit in OPTIONS)
        context = null
        return Ticket(++serial, limit).also { active = it }
    }

    fun complete(ticket: Ticket, loaded: ReplyContext, currentTalker: String?): Boolean {
        if (active != ticket || currentTalker != talker || loaded.talker != talker ||
            loaded.requestedMessages != ticket.limit || loaded.messages.isEmpty()) return false
        context = loaded; active = null
        return true
    }

    fun fail(ticket: Ticket) { if (active == ticket) active = null }
    fun isCurrent(ticket: Ticket): Boolean = ticket.serial == serial
    fun cancel() { serial++; active = null; context = null }

    companion object { val OPTIONS = listOf(30, 50, 100) }
}
