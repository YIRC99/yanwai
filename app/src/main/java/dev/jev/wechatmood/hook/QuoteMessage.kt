package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.MessagePolicy
import dev.jev.wechatmood.core.QuotedMessage
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** Allow only appmsg subtype 57. Link/file titles must never become ordinary chat text. */
data class QuoteMessage(val text: String, val quoted: QuotedMessage) {
    companion object {
        fun parse(content: String): QuoteMessage? {
            if (content.length > 65_536 || content.isBlank() ||
                Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(content)) return null
            return runCatching {
                val path = mutableListOf<String>()
                val fields = mutableMapOf<String, StringBuilder>()
                val wanted = setOf("appmsg/title", "appmsg/type", "appmsg/refermsg/type",
                    "appmsg/refermsg/content", "appmsg/refermsg/displayname", "appmsg/refermsg/svrid")
                var elements = 0
                var apps = 0
                fun key() = (if (path.firstOrNull() == "msg") path.drop(1) else path).joinToString("/")
                val handler = object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                        if (path.size >= 24 || ++elements > 512 || key() in wanted) throw SAXException("Unsupported XML structure")
                        path += qName
                        if (path.size == 1 && qName !in setOf("msg", "appmsg")) throw SAXException("Unknown root")
                        val key = key()
                        if (key == "appmsg" && ++apps > 1) throw SAXException("Ambiguous app message")
                        if (key in wanted) {
                            if (fields.containsKey(key)) throw SAXException("Duplicate field")
                            fields[key] = StringBuilder()
                        }
                    }
                    override fun endElement(uri: String?, localName: String?, qName: String?) { path.removeAt(path.lastIndex) }
                    override fun characters(ch: CharArray, start: Int, length: Int) { fields[key()]?.append(ch, start, length) }
                    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity rejected")
                    override fun error(e: SAXParseException) { throw e }
                    override fun fatalError(e: SAXParseException) { throw e }
                }
                val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
                factory.newSAXParser().parse(InputSource(StringReader(content)), handler)
                fun value(key: String) = fields["appmsg/$key"]?.toString()
                if (apps != 1 || value("type")?.trim() != "57") return null
                val text = MessagePolicy.textOrNull(value("title") ?: return null) ?: return null
                val type = value("refermsg/type")?.trim()?.toIntOrNull()
                val quote = value("refermsg/content")
                val quotedText = if (type == 1) quote?.let(MessagePolicy::textOrNull) else null
                val reason = when {
                    quotedText != null -> null
                    type != null && type != 1 -> "non_text"
                    quote.isNullOrBlank() -> "missing"
                    else -> "too_long"
                }
                val name = value("refermsg/displayname")?.trim()?.takeIf {
                    it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)
                }
                val serverId = value("refermsg/svrid")?.trim()?.takeIf { it.length in 1..32 && it.all(Char::isDigit) }
                QuoteMessage(text, QuotedMessage(quotedText, name, type, serverId, reason))
            }.getOrNull()
        }
    }
}
