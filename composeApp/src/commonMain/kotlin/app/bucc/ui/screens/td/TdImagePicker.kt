package app.bucc.ui.screens.td

import androidx.compose.runtime.Composable
import app.bucc.ui.common.util.PickedImage

interface TdImagePicker {
  fun pickImage()
}

internal class DelegatingTdImagePicker(private val action: () -> Unit) : TdImagePicker {
  override fun pickImage() = action()
}

@Composable
expect fun rememberTdImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): TdImagePicker
