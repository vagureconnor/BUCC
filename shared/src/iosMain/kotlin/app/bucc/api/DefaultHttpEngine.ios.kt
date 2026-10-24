package app.bucc.api.core

import io.ktor.client.engine.*
import io.ktor.client.engine.darwin.*

actual fun getDefaultEngine(): HttpClientEngine = Darwin.create()

internal actual fun getCampusCardEngine(): HttpClientEngine = getDefaultEngine()

internal actual fun getFinanceDirectEngine(): HttpClientEngine = getDefaultEngine()

internal actual fun getFinanceCardEngine(): HttpClientEngine = getDefaultEngine()
