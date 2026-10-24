@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.ui.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.napi.asByteArray
import androidx.compose.ui.napi.asString
import app.bucc.platform.HarmonyHost
import app.bucc.platform.status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
actual fun rememberPlatformImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): PlatformImagePicker {
  val scope = rememberCoroutineScope()
  val picked by rememberUpdatedState(onImagePicked)
  val failed by rememberUpdatedState(onError)
  return remember(scope) {
    object : PlatformImagePicker {
      override val canCapturePhoto = true
      private var picking = false

      private fun select(operation: String) {
        if (picking) return
        picking = true
        scope.launch {
          try {
            val result = HarmonyHost.request(operation)
            when (result.status) {
              "success" -> {
                val bytes = checkNotNull(result["bytes"].asByteArray())
                require(bytes.size in 1..20 * 1024 * 1024)
                picked(
                    PickedImage(
                        bytes,
                        result["fileName"].asString() ?: "photo.jpg",
                        result["mimeType"].asString() ?: "image/jpeg",
                    )
                )
              }
              "cancelled" -> Unit
              else -> failed("无法读取图片，请检查权限或选择其他图片")
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            failed("无法读取图片，请检查权限或选择其他图片")
          } finally {
            picking = false
          }
        }
      }

      override fun pickImage() = select("pickPhoto")

      override fun capturePhoto() = select("capturePhoto")
    }
  }
}

@Composable
actual fun rememberLibraryAttachmentPicker(
    onPicked: (LibraryAttachment) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
  val scope = rememberCoroutineScope()
  val picked by rememberUpdatedState(onPicked)
  val failed by rememberUpdatedState(onError)
  return remember(scope) {
    var picking = false
    {
      if (!picking) {
        picking = true
        scope.launch {
          try {
            val result = HarmonyHost.request("pickLibraryAttachment")
            when (result.status) {
              "success" -> {
                val bytes = checkNotNull(result["bytes"].asByteArray())
                val name =
                    checkNotNull(result["fileName"].asString())
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                val mime =
                    when (name.substringAfterLast('.').lowercase()) {
                      "jpg",
                      "jpeg" -> "image/jpeg"
                      "png" -> "image/png"
                      "bmp" -> "image/bmp"
                      "pdf" -> "application/pdf"
                      else -> error("Unsupported attachment")
                    }
                require(bytes.size in 1..10 * 1024 * 1024)
                picked(LibraryAttachment(bytes, name, mime))
              }
              "cancelled" -> Unit
              else -> failed("无法读取附件，请选择 10 MiB 内的 JPEG、PNG、BMP 或 PDF 文件")
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            failed("无法读取附件，请选择 10 MiB 内的 JPEG、PNG、BMP 或 PDF 文件")
          } finally {
            picking = false
          }
        }
      }
    }
  }
}
