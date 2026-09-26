package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class SettingsEntryPlacementTest {
    private fun row(key: String, plugin: Boolean = false) = SettingsEntryPlacement.Row(key, plugin)
    private val search = row(SettingsEntryPlacement.SEARCH)
    private val personal = row(SettingsEntryPlacement.PERSONAL)
    private val account = row("account")
    private val own = row(SettingsEntryPlacement.KEY, true)

    @Test fun standaloneEntryFollowsSearchWithItsOwnGroup() {
        val plan = SettingsEntryPlacement.plan(listOf(search, personal, account))!!
        assertEquals(1, plan.index)
        assertTrue(plan.showGroup)
    }

    @Test fun coexistsWithMultiplePluginsWithoutMovingHostRows() {
        val rows = listOf(search, row("wekit", true), row("another"), personal, account)
        val plan = SettingsEntryPlacement.plan(rows)!!
        assertEquals(3, plan.index)
        assertFalse(plan.showGroup)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.keepIndices)
    }

    @Test fun rebuildRemovesOldCopiesAndRecomputesGroup() {
        val plan = SettingsEntryPlacement.plan(listOf(search, own, own, personal, account))!!
        assertEquals(listOf(0, 3, 4), plan.keepIndices)
        assertEquals(1, plan.index)
        assertTrue(plan.showGroup)
    }

    @Test fun pluginElsewhereDoesNotHideOurHeader() {
        assertTrue(SettingsEntryPlacement.plan(listOf(search, personal, account, row("late", true)))!!.showGroup)
    }

    @Test fun reopeningWithWeKitStillKeepsOneEntryAndOnePluginHeader() {
        val plan = SettingsEntryPlacement.plan(listOf(search, row("wekit", true), own, personal, account))!!
        assertEquals(listOf(0, 1, 3, 4), plan.keepIndices)
        assertEquals(2, plan.index)
        assertFalse(plan.showGroup)
    }

    @Test fun aDifferentGroupAfterPluginsRequiresANewHeader() {
        assertTrue(SettingsEntryPlacement.plan(listOf(search, row("wekit", true),
            SettingsEntryPlacement.Row("notice", false, true), personal))!!.showGroup)
    }

    @Test fun missingOrReversedAnchorsLeaveUnknownHostListUntouched() {
        assertNull(SettingsEntryPlacement.plan(emptyList()))
        assertNull(SettingsEntryPlacement.plan(listOf(personal, account)))
        assertNull(SettingsEntryPlacement.plan(listOf(search, account)))
        assertNull(SettingsEntryPlacement.plan(listOf(personal, search)))
        assertNull(SettingsEntryPlacement.plan(listOf(search, personal, personal)))
    }
}
