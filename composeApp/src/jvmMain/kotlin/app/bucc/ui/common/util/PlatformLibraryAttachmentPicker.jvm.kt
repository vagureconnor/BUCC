package app.bucc.ui.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilenameFilter

private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

@Composable
actual fun rememberLibraryAttachmentPicker(
    onPicked: (LibraryAttachment) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
  val currentOnPicked by rememberUpdatedState(onPicked)
  val currentOnError by rememberUpdatedState(onError)
  return remember {
    {
      runCatching {
            val dialog =
                FileDialog(null as Frame?, "选择附件", FileDialog.LOAD).apply {
                  filenameFilter = FilenameFilter { _, name ->
                    name.hasLibraryAttachmentExtension()
                  }
                  isMultipleMode = false
                  isVisible = true
                }
            try {
              val selectedName = dialog.file ?: return@runCatching null
              val selectedDirectory = dialog.directory ?: return@runCatching null
              File(selectedDirectory, selectedName).readLibraryAttachment()
            } finally {
              dialog.dispose()
            }
          }
          .onSuccess { attachment -> if (attachment != null) currentOnPicked(attachment) }
          .onFailure { currentOnError(it.message ?: "读取附件失败") }
    }
  }
}

private fun File.readLibraryAttachment(): LibraryAttachment {
  val mimeType = name.libraryAttachmentMimeType()
  val output = ByteArrayOutputStream()
  inputStream().use { input ->
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      require(total <= MAX_ATTACHMENT_BYTES) { "附件不能超过 10 MiB" }
      output.write(buffer, 0, count)
    }
  }
  return LibraryAttachment(output.toByteArray(), name, mimeType)
}

private fun String.hasLibraryAttachmentExtension(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "bmp", "pdf")

private fun String.libraryAttachmentMimeType(): String =
    when (substringAfterLast('.', "").lowercase()) {
      "jpg",
      "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "bmp" -> "image/bmp"
      "pdf" -> "application/pdf"
      else -> error("仅支持 JPEG、PNG、BMP 或 PDF 附件")
    }
