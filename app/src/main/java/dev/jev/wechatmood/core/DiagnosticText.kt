package dev.jev.wechatmood.core

object DiagnosticText {
    fun sanitize(text: String, secrets: Collection<String> = emptyList()): String {
        var result = text
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach {
            result = result.replace(it, "[REDACTED]")
        }
        result = result.replace(Regex("(?i)(Bearer\\s+)[^\\s\"',;]+"), "$1[REDACTED]")
        result = result.replace(Regex("(?i)([\"']?(?:api[_-]?key|access[_-]?token|token|authorization)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,}]+"), "$1[REDACTED]")
        return result
    }

    fun failure(code: String, error: Throwable): String = "[$code] ${error.stackTraceToString()}"
}
