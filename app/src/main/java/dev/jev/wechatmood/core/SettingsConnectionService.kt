package dev.jev.wechatmood.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle

/** Short-lived bound connection; shares the Provider's caller checks and private storage. */
class SettingsConnectionService : Service() {
    override fun onCreate() { super.onCreate(); MoodLog.init(this) }
    private val binder = object : ISettingsConnection.Stub() {
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
            SettingsProvider.dispatch(this@SettingsConnectionService, Binder.getCallingUid(), method, arg, extras)
    }
    override fun onBind(intent: Intent) = binder
}
