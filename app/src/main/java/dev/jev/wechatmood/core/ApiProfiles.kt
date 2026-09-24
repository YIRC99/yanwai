package dev.jev.wechatmood.core

/** Values are committed together by SharedPreferences; never log this map. */
object ApiProfiles {
    fun valuesToSave(settings: ApiSettings, read: (String) -> String?): Map<String, String> {
        val updates = linkedMapOf<String, String>()
        val previousEndpoint = read(ModulePrefs.KEY_API_BASE).orEmpty()
        val previous = JevProvider.resolve(read(ModulePrefs.KEY_API_PROVIDER), previousEndpoint)
        // Preserve a 1.0.x account before the first save replaces the global active configuration.
        if (read("channel_${previous.id}_key") == null) {
            updates["channel_${previous.id}_key"] = read(ModulePrefs.KEY_API_KEY).orEmpty()
            updates["channel_${previous.id}_endpoint"] = if (previous == JevProvider.CUSTOM) previousEndpoint else previous.endpoint
            updates["channel_${previous.id}_model"] = if (previous == JevProvider.CUSTOM)
                read(ModulePrefs.KEY_API_MODEL).orEmpty().ifBlank { previous.model } else previous.model
        }
        updates.putAll(mapOf(
            ModulePrefs.KEY_API_PROVIDER to settings.provider.id,
            ModulePrefs.KEY_API_BASE to settings.endpoint,
            ModulePrefs.KEY_API_MODEL to settings.model,
            ModulePrefs.KEY_API_KEY to settings.apiKey,
            "channel_${settings.provider.id}_endpoint" to settings.endpoint,
            "channel_${settings.provider.id}_model" to settings.model,
            "channel_${settings.provider.id}_key" to settings.apiKey,
        ))
        return updates
    }
}
