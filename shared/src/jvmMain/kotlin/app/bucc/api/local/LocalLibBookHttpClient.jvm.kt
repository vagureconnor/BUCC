package app.bucc.api.local

import app.bucc.api.core.createDesktopEngine
import io.ktor.client.engine.HttpClientEngine

internal actual fun getLibBookHttpClientEngine(): HttpClientEngine =
    createDesktopEngine(campusCardTransport = false, relaxTls = true)
