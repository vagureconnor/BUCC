package app.bucc.ui.screens.td

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.bucc.ui.common.util.PickedImage
import app.bucc.ui.common.util.rememberPlatformImagePicker

@Composable
actual fun rememberTdImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): TdImagePicker {
  val picker = rememberPlatformImagePicker(onImagePicked, onError)
  return remember(picker) { DelegatingTdImagePicker(picker::pickImage) }
}
