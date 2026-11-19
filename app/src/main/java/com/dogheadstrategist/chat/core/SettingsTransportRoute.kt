package com.dogheadstrategist.chat.core

internal object SettingsTransportRoute {
    fun <C : AutoCloseable, T> call(provider: () -> C?, bound: () -> C, request: (C) -> T): T =
        (provider() ?: bound()).use(request)
}
