package app.bucc.api.core

import io.ktor.client.engine.HttpClientEngine

/** 获取各平台用于直接访问学校上游服务的默认 HTTP 引擎。 */
expect fun getDefaultEngine(): HttpClientEngine

internal expect fun getCampusCardEngine(): HttpClientEngine

/** Finance/payment hosts must not inherit the campus physical proxy. */
internal expect fun getFinanceDirectEngine(): HttpClientEngine

/** Card host uses a certificate chain that is not in the bundled JVM trust store. */
internal expect fun getFinanceCardEngine(): HttpClientEngine
