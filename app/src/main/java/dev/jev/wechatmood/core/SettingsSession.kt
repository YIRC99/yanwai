package dev.jev.wechatmood.core

// Memory only: credentials are not copied into WeChat's files or backups.
class RuntimeSettings(val revision: Long, val enabled: Boolean, val showBadge: Boolean,
    val exploreMode: Boolean, val api: ApiSettings, val generation: String) {
    val canAnalyze get() = enabled && api.isConfigured
}

class SettingsSession {
    @Volatile var current: RuntimeSettings? = null
        private set
    private var generation: String? = null
    private var pendingVerification = false
    private val retiredGenerations = mutableSetOf<String>()

    /** False asks the caller to verify a changed installation through the Provider. */
    @Synchronized fun accept(snapshot: RuntimeSettings?, fromProvider: Boolean = true): Boolean {
        // Transport failure is not a settings change. An explicit disable or empty key is.
        if (snapshot == null) return true
        if (!fromProvider && snapshot.generation in retiredGenerations) return true
        if (!fromProvider && pendingVerification) return false
        if (generation != null && generation != snapshot.generation) {
            if (!fromProvider) {
                current = null // Do not use old credentials while a data reset is unverified.
                pendingVerification = true
                return false
            }
            retiredGenerations.add(requireNotNull(generation))
            current = null
        }
        generation = snapshot.generation
        pendingVerification = false
        retiredGenerations.remove(snapshot.generation)
        if (snapshot.revision >= (current?.revision ?: -1L)) current = snapshot
        return true
    }
}
