package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyPlusLabelsTest {
    // JVM Java classes always have a constructor. A private one reproduces the absent public
    // reflection entry point; the actual host DEX has no declared label constructor at all.
    class Label private constructor() {
        @JvmField var a: String? = null
        @JvmField var b: String? = null
        companion object { fun hostCreate() = Label() }
    }
    class HostItem {
        @JvmField var n2: Label? = Label.hostCreate()
        @JvmField var o2: Label? = Label.hostCreate()
        @JvmField var p2: Label? = Label.hostCreate()
        @JvmField var q2: Label? = Label.hostCreate()
    }

    @Test fun `populates host initialized labels with no public constructor and preserves their identities`() {
        assertTrue(runCatching { Label::class.java.getConstructor() }.exceptionOrNull() is NoSuchMethodException)
        val item = HostItem()
        val original = listOf(item.n2, item.o2, item.p2, item.q2)
        repeat(2) { ReplyPlusLabels.populate(item, Label::class.java) }
        val actual = listOf(item.n2, item.o2, item.p2, item.q2)
        original.indices.forEach { i ->
            assertSame(original[i], actual[i])
            assertEquals("帮我回", actual[i]?.a)
            assertEquals("", actual[i]?.b)
        }
    }

    @Test fun `missing label fails closed before modifying any existing label`() {
        val item = HostItem().apply { q2 = null }
        assertTrue(runCatching { ReplyPlusLabels.populate(item, Label::class.java) }.isFailure)
        assertNull(item.n2?.a); assertNull(item.o2?.a); assertNull(item.p2?.a)
    }

    @Test fun `unexpected label type fails closed`() {
        val item = HostItem()
        assertTrue(runCatching { ReplyPlusLabels.populate(item, String::class.java) }.isFailure)
        assertNull(item.n2?.a)
    }
}
