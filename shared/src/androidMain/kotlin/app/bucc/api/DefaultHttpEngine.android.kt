package app.bucc.api.core

import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*

actual fun getDefaultEngine(): HttpClientEngine =
    OkHttp.create { config { retryOnConnectionFailure(false) } }

internal actual fun getCampusCardEngine(): HttpClientEngine = getDefaultEngine()

internal actual fun getFinanceDirectEngine(): HttpClientEngine = getDefaultEngine()

internal actual fun getFinanceCardEngine(): HttpClientEngine = getDefaultEngine()
