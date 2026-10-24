package app.bucc.ui.common.util

import androidx.compose.runtime.Composable

/** Camera-backed QR scanner. Platform implementations must not persist or log decoded values. */
@Composable expect fun PlatformQrScanner(onDecoded: (String) -> Unit, onCancel: () -> Unit)

/** Whether this platform currently has a usable camera scanner implementation. */
expect fun supportsQrCamera(): Boolean
