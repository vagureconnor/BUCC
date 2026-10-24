package app.bucc.api.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.curl.Curl

actual fun getDefaultEngine(): HttpClientEngine = Curl.create()

internal actual fun getCampusCardEngine(): HttpClientEngine = getDefaultEngine()

internal actual fun getFinanceDirectEngine(): HttpClientEngine = getDefaultEngine()

internal actual fun getFinanceCardEngine(): HttpClientEngine = getDefaultEngine()
