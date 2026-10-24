package app.bucc.ui.screens.finance

import androidx.compose.runtime.Composable
import app.bucc.api.finance.OfficialPaymentPage

@Composable
internal actual fun platformPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
): Boolean = false
