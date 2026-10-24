package app.bucc.ui.screens.td

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.bucc.td.TdAppleBridge
import app.bucc.td.TdAppleCancellable
import app.bucc.td.TdAppleImageCallback
import app.bucc.ui.common.util.PickedImage

@Composable
actual fun rememberTdImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): TdImagePicker {
  val currentOnImagePicked = rememberUpdatedState(onImagePicked)
  val currentOnError = rememberUpdatedState(onError)
  return remember {
    IosTdImagePicker(
        onImagePicked = { currentOnImagePicked.value(it) },
        onError = { currentOnError.value(it) },
    )
  }
}

private class IosTdImagePicker(
    private val onImagePicked: (PickedImage) -> Unit,
    private val onError: (String) -> Unit,
) : TdImagePicker {
  private var request: TdAppleCancellable? = null

  override fun pickImage() {
    request?.cancel()
    request =
        TdAppleBridge.requirePlatform()
            .pickImage(
                object : TdAppleImageCallback {
                  override fun onSuccess(name: String, mimeType: String, bytes: ByteArray) {
                    request = null
                    onImagePicked(PickedImage(bytes = bytes, fileName = name, mimeType = mimeType))
                  }

                  override fun onFailure(message: String) {
                    request = null
                    if (message.isNotBlank()) onError(message)
                  }
                }
            )
  }
}
