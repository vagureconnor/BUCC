package app.bucc.ui.common.util

import androidx.compose.runtime.Composable

data class LibraryAttachment(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
) {
  override fun toString(): String = "LibraryAttachment(<redacted>)"
}

@Composable
expect fun rememberLibraryAttachmentPicker(
    onPicked: (LibraryAttachment) -> Unit,
    onError: (String) -> Unit,
): () -> Unit
