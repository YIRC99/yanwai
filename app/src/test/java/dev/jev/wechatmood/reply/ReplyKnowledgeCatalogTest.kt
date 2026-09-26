package dev.jev.wechatmood.reply

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReplyKnowledgeCatalogTest {
    @Test fun `all role references exist in the bundled original knowledge`() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        ReplyRelationship.entries.forEach { role ->
            val paths = ReplyKnowledgeCatalog.paths(role)
            assertTrue(paths.size in 3..5)
            assertEquals(paths.distinct(), paths)
            paths.forEach { path -> assertTrue("Missing $path for $role", File(assets, path).readText().isNotBlank()) }
        }
    }

    @Test fun `family and friend roles receive fitting guidance without dating references`() {
        listOf(ReplyRelationship.ELDER, ReplyRelationship.YOUNGER_SIBLING, ReplyRelationship.FAMILY).forEach { role ->
            val paths = ReplyKnowledgeCatalog.paths(role)
            assertTrue(paths.any { it.contains("家庭") })
            assertFalse(paths.any { it.contains("吸引约会") })
            assertTrue(role.guidance.contains("不"))
        }
        assertTrue(ReplyKnowledgeCatalog.paths(ReplyRelationship.CRUSH).any { it.contains("吸引约会") })
        assertTrue(ReplyKnowledgeCatalog.paths(ReplyRelationship.FRIEND).any { it.contains("接话") })
    }
}
