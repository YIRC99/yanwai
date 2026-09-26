package dev.jev.wechatmood.hook

import dev.jev.wechatmood.voice.VoiceHostContract
import org.junit.Assert.*
import org.junit.Test

class VoiceHostContractTest {
    class Message
    class Chat
    enum class State { NoTransform, PreTransform, Transforming, Transformed }
    open class Base { @JvmField val chat = Chat() }
    open class Component : Base() {
        fun start(message: Message, flag: Boolean, position: Int, scene: Int) {}
        fun state(id: Long): State = State.NoTransform
        fun text(id: Long, path: String): String = ""
    }
    class Ambiguous : Component() { fun another(message: Message, flag: Boolean, position: Int, scene: Int) {} }
    @Test fun resolvesNativeShapeWithoutHardcodedObfuscatedNames() {
        val contract = VoiceHostContract.resolve(Component::class.java, Chat::class.java)!!
        assertEquals(Message::class.java, contract.messageClass)
        assertEquals("start", contract.submit.name)
        assertEquals("state", contract.state.name)
        assertEquals("text", contract.text.name)
        assertTrue(contract.chatField.get(Component()) is Chat)
    }
    @Test fun ambiguousOrMissingBridgeFailsClosed() {
        assertNull(VoiceHostContract.resolve(Ambiguous::class.java, Chat::class.java))
        assertNull(VoiceHostContract.resolve(Any::class.java, Chat::class.java))
    }
}
