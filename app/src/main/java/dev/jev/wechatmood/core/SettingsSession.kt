package dev.jev.wechatmood.core

// Memory only: credentials are not copied into WeChat's files or backups.
class RuntimeSettings(val revision: Long,
    val exploreMode: Boolean, val api: ApiSettings, val generation: String,
    val reply: dev.jev.wechatmood.reply.ReplySettings = dev.jev.wechatmood.reply.ReplySettings.empty(),
    val replyConsent: Boolean = false,
    val intent: IntentSettings = IntentSettings()) {
    val canAnalyze get() = api.isConfigured
    fun sameAnalysis(other: RuntimeSettings) = generation == other.generation && api.endpoint == other.api.endpoint &&
        api.apiKey == other.api.apiKey && api.model == other.api.model && intent.sameAs(other.intent)
}

class SettingsSession(private val onAnalysisChanged: () -> Unit = {}) {
    @Volatile var current: RuntimeSettings? = null
        private set
    private var generation: String? = null
    private var pendingVerification = false
    private val retiredGenerations = mutableSetOf<String>()

    /** False asks the caller to verify a changed installation through the Provider. */
    @Synchronized fun accept(snapshot: RuntimeSettings?, fromProvider: Boolean = true): Boolean {
        // Transport failure is not a settings change. An explicit empty key is.
        if (snapshot == null) return true
        if (!fromProvider && snapshot.generation in retiredGenerations) return true
        if (!fromProvider && pendingVerification) return false
        if (generation != null && generation != snapshot.generation) {
            if (!fromProvider) {
                current = null // Do not use old credentials while a data reset is unverified.
                onAnalysisChanged()
                MoodStore.clear()
                pendingVerification = true
                return false
            }
            retiredGenerations.add(requireNotNull(generation))
            current = null
        }
        generation = snapshot.generation
        pendingVerification = false
        retiredGenerations.remove(snapshot.generation)
        if (snapshot.revision >= (current?.revision ?: -1L)) {
            if (current?.sameAnalysis(snapshot) != true) {
                // Block new submissions while revoking old jobs and their ownership.
                current = null
                onAnalysisChanged()
                MoodStore.clear()
            }
            current = snapshot
        }
        return true
    }
}
