package dev.jev.wechatmood.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Resource contracts: choices remain accessible, keys stay out of saved view state. */
class SettingsLayoutTest {
    private fun elements(name: String): List<Element> {
        val file = File("src/main/res/layout/$name.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun analysisChoicesUseNonEditableDropdowns() {
        val nodes = elements("intent_settings")
        assertFalse("Long radio lists should be replaced with dropdowns", nodes.any { it.tagName == "RadioGroup" })
        listOf("emotionSource", "configSource", "intentRoute").forEach { id ->
            val field = nodes.single { it.getAttribute("android:id") == "@+id/$id" }
            assertEquals("none", field.getAttribute("android:inputType"))
            assertEquals("false", field.getAttribute("android:saveEnabled"))
            assertEquals("56dp", field.getAttribute("android:minHeight"))
        }
    }

    @Test fun secretsNeverEnterAndroidSavedViewState() {
        listOf("activity_main", "intent_settings", "reply_settings").forEach { layout ->
            elements(layout).filter { it.getAttribute("android:inputType") == "textPassword" }.forEach {
                assertEquals("$layout must not persist keys in instance state", "false", it.getAttribute("android:saveEnabled"))
                assertEquals("no", it.getAttribute("android:importantForAutofill"))
            }
        }
    }

    @Test fun cardDisplayControlsHaveOneHomeOutsideModelForms() {
        val controls = listOf("showIntent", "showConcern", "showTone")
        val modelForms = elements("intent_settings") + elements("reply_settings")
        assertFalse(modelForms.any { it.getAttribute("android:id").removePrefix("@+id/") in controls })
        controls.forEach { id ->
            assertEquals(1, elements("activity_main").count { it.getAttribute("android:id") == "@+id/$id" })
        }
    }
}
