package app.bucc.ui.common.util

/** Copies only the cropped Y plane; neither the image nor its decoded value is persisted. */
internal fun copyQrLuminance(
    bytes: ByteArray,
    rowStride: Int,
    pixelStride: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    offset: Int = 0,
): ByteArray {
  require(
      rowStride > 0 &&
          pixelStride > 0 &&
          left >= 0 &&
          top >= 0 &&
          width > 0 &&
          height > 0 &&
          offset >= 0
  )
  val rowEnd = (left.toLong() + width - 1) * pixelStride
  require(rowEnd < rowStride)
  val start = offset.toLong() + top.toLong() * rowStride + left.toLong() * pixelStride
  val end = start + (height.toLong() - 1) * rowStride + (width.toLong() - 1) * pixelStride
  require(end < bytes.size && width.toLong() * height <= Int.MAX_VALUE)
  return ByteArray(width * height) { index ->
    bytes[
        (start + (index / width).toLong() * rowStride + (index % width).toLong() * pixelStride)
            .toInt()]
  }
}
