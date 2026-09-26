package dev.jev.wechatmood.hook

/** Position in the already ordered host list; never rewrite another plugin's class links. */
internal object SettingsEntryPlacement {
    const val KEY = "yanwai_settings_entry"
    const val SEARCH = "SettingAdditionHeaderSearch"
    const val PERSONAL = "SettingGroupPersonalInfo"

    data class Row(val key: String, val pluginGroup: Boolean = false, val startsGroup: Boolean = pluginGroup)
    data class Plan(val keepIndices: List<Int>, val index: Int, val showGroup: Boolean)

    fun plan(rows: List<Row>): Plan? {
        val keep = rows.indices.filter { rows[it].key != KEY }
        val clean = keep.map(rows::get)
        if (clean.count { it.key == SEARCH } != 1 || clean.count { it.key == PERSONAL } != 1) return null
        val search = clean.indexOfFirst { it.key == SEARCH }
        val personal = clean.indexOfFirst { it.key == PERSONAL }
        if (search < 0 || personal <= search) return null
        val previousGroup = clean.subList(search + 1, personal).lastOrNull { it.startsGroup }
        return Plan(keep, personal, previousGroup?.pluginGroup != true)
    }
}
