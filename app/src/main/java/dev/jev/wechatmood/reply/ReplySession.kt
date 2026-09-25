package dev.jev.wechatmood.reply

class ReplySession {
    data class Ticket(val serial: Long, val talker: String, val fingerprint: String)
    private var serial = 0L
    private var current: Ticket? = null
    fun begin(talker: String, fingerprint: String) = Ticket(++serial, talker, fingerprint).also { current = it }
    fun accepts(ticket: Ticket, talker: String?) = ticket == current && ticket.talker == talker
    fun cancel() { serial++; current = null }
}

class DraftReplacement(private val original: String, private val replacement: String) {
    fun undo(current: String): String? = original.takeIf { current == replacement }
}
