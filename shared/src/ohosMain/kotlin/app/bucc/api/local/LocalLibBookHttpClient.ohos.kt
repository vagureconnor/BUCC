package app.bucc.api.local

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.curl.Curl

internal actual fun getLibBookHttpClientEngine(): HttpClientEngine = Curl.create()
