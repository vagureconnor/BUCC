package app.bucc.ui.common.util

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream

private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

@Composable
actual fun rememberLibraryAttachmentPicker(
    onPicked: (LibraryAttachment) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
  val context = LocalContext.current
  val currentOnPicked by rememberUpdatedState(onPicked)
  val currentOnError by rememberUpdatedState(onError)
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          runCatching { context.readLibraryAttachment(uri) }
              .onSuccess(currentOnPicked)
              .onFailure { currentOnError(it.message ?: "读取附件失败") }
        }
      }
  return remember(launcher) {
    { launcher.launch(arrayOf("image/jpeg", "image/png", "image/bmp", "application/pdf")) }
  }
}

private fun Context.readLibraryAttachment(uri: Uri): LibraryAttachment {
  val name =
      contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
      } ?: "附件"
  val mimeType = name.libraryAttachmentMimeType()
  val bytes =
      contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          require(total <= MAX_ATTACHMENT_BYTES) { "附件不能超过 10 MiB" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      } ?: error("无法读取所选附件")
  return LibraryAttachment(bytes, name, mimeType)
}

private fun String.libraryAttachmentMimeType(): String =
    when (substringAfterLast('.', "").lowercase()) {
      "jpg",
      "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "bmp" -> "image/bmp"
      "pdf" -> "application/pdf"
      else -> error("仅支持 JPEG、PNG、BMP 或 PDF 附件")
    }
