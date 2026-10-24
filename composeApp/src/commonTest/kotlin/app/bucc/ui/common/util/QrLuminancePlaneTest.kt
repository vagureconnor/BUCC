package app.bucc.ui.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class QrLuminancePlaneTest {
  @Test
  fun contiguousPlane() {
    assertContentEquals(
        byteArrayOf(1, 2, 3, 4),
        copyQrLuminance(byteArrayOf(1, 2, 3, 4), 2, 1, 0, 0, 2, 2),
    )
  }

  @Test
  fun cropRespectsRowPaddingPixelStrideAndBufferOffset() {
    val bytes = byteArrayOf(99, 0, 8, 1, 8, 2, 8, 77, 77, 3, 8, 4, 8, 5)
    assertContentEquals(
        byteArrayOf(1, 2, 4, 5),
        copyQrLuminance(bytes, 8, 2, 1, 0, 2, 2, offset = 1),
    )
  }

  @Test
  fun lastRowDoesNotRequireTrailingPadding() {
    assertContentEquals(
        byteArrayOf(1, 2, 3, 4),
        copyQrLuminance(byteArrayOf(1, 2, 9, 9, 3, 4), 4, 1, 0, 0, 2, 2),
    )
  }

  @Test
  fun invalidOrTruncatedPlanesAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      copyQrLuminance(byteArrayOf(1, 2, 3), 2, 1, 0, 0, 2, 2)
    }
    assertFailsWith<IllegalArgumentException> { copyQrLuminance(ByteArray(10), 2, 1, -1, 0, 2, 2) }
    assertFailsWith<IllegalArgumentException> { copyQrLuminance(ByteArray(10), 2, 1, 1, 0, 2, 2) }
    assertFailsWith<IllegalArgumentException> {
      copyQrLuminance(ByteArray(10), 2, 1, 0, 0, 2, 2, offset = -1)
    }
  }
}
