package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `redacts configured keys and bearer tokens without deleting stack frames`() {
        val result = DiagnosticText.sanitize("secret-value Authorization: Bearer token-123\n at Example.call(Example.kt:12)", listOf("secret-value"))
        assertFalse(result.contains("secret-value"))
        assertFalse(result.contains("token-123"))
        assertTrue(result.contains("Example.kt:12"))
    }

    @Test fun `redacts key fields and token query parameters`() {
        val result = DiagnosticText.sanitize("{\"api_key\":\"key-value\"} https://host/path?token=private-value&mode=test")
        assertFalse(result.contains("key-value"))
        assertFalse(result.contains("private-value"))
        assertTrue(result.contains("mode=test"))
    }

    @Test fun `failure retains cause class and stack for bridge diagnosis`() {
        val result = DiagnosticText.failure("BRIDGE_READ", IllegalStateException("missing provider", SecurityException("denied")))
        assertTrue(result.contains("BRIDGE_READ"))
        assertTrue(result.contains("missing provider"))
        assertTrue(result.contains("SecurityException"))
        assertTrue(result.contains("DiagnosticsTest"))
    }

    @Test fun `restart preserves previous process evidence`() {
        val file = temp.newFile()
        DiagnosticJournal(file).append("previous process\n")
        val restarted = DiagnosticJournal(file)
        restarted.append("new process\n")
        assertEquals("previous process\nnew process\n", restarted.read())
    }

    @Test fun `unwritable file retains exportable memory evidence`() {
        val journal = DiagnosticJournal(temp.newFolder())
        journal.append("save failed\n")
        assertTrue(journal.read().contains("save failed"))
        assertNotNull(journal.diskFailure)
    }

    @Test fun `rotation bounds storage and keeps latest failure after restart`() {
        val file = File(temp.root, "bounded.log")
        val journal = DiagnosticJournal(file, 80)
        repeat(50) { journal.append("record $it 中文\n") }
        assertTrue(journal.read().length <= 80)
        assertTrue(journal.read().contains("record 49"))
        assertEquals(journal.read(), DiagnosticJournal(file, 80).read())
        assertTrue(file.length() <= 80 * 4)
    }
}
