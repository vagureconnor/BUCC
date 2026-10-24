package app.bucc.ui.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeBMP
import platform.UniformTypeIdentifiers.UTTypeJPEG
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.UniformTypeIdentifiers.UTTypePNG
import platform.posix.memcpy

private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

@Composable
actual fun rememberLibraryAttachmentPicker(
    onPicked: (LibraryAttachment) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
  val currentOnPicked by rememberUpdatedState(onPicked)
  val currentOnError by rememberUpdatedState(onError)
  val picker = remember {
    IosLibraryAttachmentPicker(
        onPicked = { currentOnPicked(it) },
        onError = { currentOnError(it) },
    )
  }
  return remember(picker) { { picker.open() } }
}

private class IosLibraryAttachmentPicker(
    private val onPicked: (LibraryAttachment) -> Unit,
    private val onError: (String) -> Unit,
) {
  private var documentPicker: UIDocumentPickerViewController? = null
  private var delegate: AttachmentDocumentPickerDelegate? = null

  fun open() {
    if (documentPicker != null) return
    val presenter =
        UIApplication.sharedApplication.windows
            .filterIsInstance<UIWindow>()
            .firstOrNull { it.isKeyWindow() }
            ?.rootViewController ?: return onError("无法打开文件选择器")
    val picker =
        UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeJPEG, UTTypePNG, UTTypeBMP, UTTypePDF),
            asCopy = true,
        )
    val pickerDelegate =
        AttachmentDocumentPickerDelegate(
            onSelected = { url ->
              runCatching { readLibraryAttachment(url) }
                  .onSuccess(onPicked)
                  .onFailure { onError(it.message ?: "读取附件失败") }
              clear()
            },
            onCancelled = { clear() },
        )
    delegate = pickerDelegate
    documentPicker = picker
    picker.delegate = pickerDelegate
    presenter.presentViewController(picker, animated = true, completion = null)
  }

  private fun clear() {
    documentPicker = null
    delegate = null
  }
}

private class AttachmentDocumentPickerDelegate(
    private val onSelected: (NSURL) -> Unit,
    private val onCancelled: () -> Unit,
) : platform.darwin.NSObject(), UIDocumentPickerDelegateProtocol {
  override fun documentPicker(
      controller: UIDocumentPickerViewController,
      didPickDocumentsAtURLs: List<*>,
  ) {
    val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
    if (url == null) onCancelled() else onSelected(url)
  }

  override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
    onCancelled()
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun readLibraryAttachment(url: NSURL): LibraryAttachment {
  val path = url.path ?: error("无法读取所选附件")
  val startedAccess = url.startAccessingSecurityScopedResource()
  try {
    val attributes =
        NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: error("无法读取附件信息")
    val size =
        (attributes[NSFileSize] as? NSNumber)?.unsignedLongLongValue?.toLong() ?: error("无法确认附件大小")
    require(size in 0..MAX_ATTACHMENT_BYTES.toLong()) { "附件不能超过 10 MiB" }
    val name = url.lastPathComponent ?: "附件"
    val mimeType = name.libraryAttachmentMimeType()
    val data = NSFileManager.defaultManager.contentsAtPath(path) ?: error("无法读取所选附件")
    require(data.length <= MAX_ATTACHMENT_BYTES.toULong()) { "附件不能超过 10 MiB" }
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return LibraryAttachment(bytes, name, mimeType)
  } finally {
    if (startedAccess) url.stopAccessingSecurityScopedResource()
  }
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
