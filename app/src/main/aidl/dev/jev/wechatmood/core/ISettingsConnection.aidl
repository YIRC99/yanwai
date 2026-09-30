package dev.jev.wechatmood.core;

import android.os.Bundle;

/** The server derives identity from Binder, never from caller-supplied fields. */
interface ISettingsConnection {
    Bundle call(String method, @nullable String arg, in @nullable Bundle extras);
}
