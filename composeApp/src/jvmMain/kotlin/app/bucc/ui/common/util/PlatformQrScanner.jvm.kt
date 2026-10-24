package app.bucc.ui.common.util

import androidx.compose.runtime.Composable

@Composable actual fun PlatformQrScanner(onDecoded: (String) -> Unit, onCancel: () -> Unit) = Unit

actual fun supportsQrCamera(): Boolean = false
