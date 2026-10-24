package app.bucc.api.local

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun getLibBookHttpClientEngine(): HttpClientEngine = Darwin.create()
