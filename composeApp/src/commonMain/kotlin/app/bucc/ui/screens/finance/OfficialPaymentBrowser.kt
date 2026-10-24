package app.bucc.ui.screens.finance

import androidx.compose.runtime.Composable
import app.bucc.api.finance.OfficialPaymentPage

@Composable
expect fun OfficialPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
    onExternalPaymentAppReturn: (() -> Unit)? = null,
)

expect suspend fun initializePaymentBrowser(): Boolean
