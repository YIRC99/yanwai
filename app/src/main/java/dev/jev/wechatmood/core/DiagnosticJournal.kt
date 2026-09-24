package dev.jev.wechatmood.core

import java.io.File

class DiagnosticJournal(private val file: File, private val limit: Int = 128 * 1024) {
    var diskFailure: String? = null
        private set
    private var memory = runCatching { if (file.exists()) file.readText().takeLast(limit) else "" }
        .onFailure { diskFailure = it.javaClass.simpleName }.getOrDefault("")

    @Synchronized fun append(text: String) {
        val overflow = memory.length + text.length > limit
        memory = (memory + text).takeLast(limit)
        runCatching {
            if (overflow || diskFailure != null) file.writeText(memory) else file.appendText(text)
            diskFailure = null
        }.onFailure { diskFailure = it.javaClass.simpleName }
    }

    @Synchronized fun read(): String = memory
}
