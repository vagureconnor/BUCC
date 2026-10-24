package app.bucc.ui.common.util

/**
 * Probe-only type harness for the production native compressor, without the Compose UI dependency.
 */
data class PickedImage(val bytes: ByteArray, val fileName: String, val mimeType: String)

expect fun compressAvatarImage(image: PickedImage): PickedImage
