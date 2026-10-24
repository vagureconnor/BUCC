@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.probe

import app.bucc.api.plantform.PlatformImageRasterDecoder
import app.bucc.diagnostics.PlatformDiagnosticFiles
import app.bucc.diagnostics.diagnosticStackFrames
import app.bucc.platform.HarmonyAppDirectories
import app.bucc.td.HarmonyDefaultTdImages
import kotlin.math.abs
import platform.posix.EACCES
import platform.posix.EPERM
import platform.posix.errno
import platform.posix.symlink
import platform.posix.unlink

internal var imageProbeStep = 400
internal var imageSymlinkProbeSkipped = false

internal fun imageDiagnosticProbe() {
  imageProbeStep = 401
  val alpha = PlatformImageRasterDecoder.decode(NativeImageFixtures.alpha)
  imageProbeStep = 402
  check(alpha.width == 13 && alpha.height == 3)
  alpha.argb.forEachIndexed { index, actual ->
    imageProbeStep = 410 + index
    val expected = NativeImageFixtures.alphaArgb[index]
    check(actual ushr 24 == expected ushr 24)
    if (expected ushr 24 != 0) check(actual == expected)
  }
  imageProbeStep = 450
  val large = PlatformImageRasterDecoder.decode(NativeImageFixtures.large)
  imageProbeStep = 451
  check(large.width == 1024 && large.height == 512)
  check(large.argb.size == 1024 * 512 && large.argb.all { it == 0xff2575cbu.toInt() })
  imageProbeStep = 452
  val jpeg = PlatformImageRasterDecoder.decode(NativeImageFixtures.jpeg)
  imageProbeStep = 453
  check(jpeg.width == 17 && jpeg.height == 5)
  jpeg.argb.forEach { pixel ->
    check(pixel ushr 24 == 255)
    check(abs((pixel shr 16 and 255) - 40) <= 10)
    check(abs((pixel shr 8 and 255) - 130) <= 10)
    check(abs((pixel and 255) - 216) <= 10)
  }
  imageProbeStep = 454
  check(HarmonyDefaultTdImages.images.size == 3)
  HarmonyDefaultTdImages.images.values.forEach { bytes ->
    val image = PlatformImageRasterDecoder.decode(bytes)
    check(image.width > 0 && image.height > 0 && image.argb.size == image.width * image.height)
  }
  imageProbeStep = 455
  check(runCatching { PlatformImageRasterDecoder.decode(byteArrayOf()) }.isFailure)
  check(runCatching { PlatformImageRasterDecoder.decode(byteArrayOf(1, 2, 3)) }.isFailure)

  imageProbeStep = 460
  val files = PlatformDiagnosticFiles
  check(files.enabled)
  try {
    repeat(3) { slot ->
      imageProbeStep = 461 + slot
      check(files.read(slot) == null && files.modifiedAt(slot) == null)
      val text = "synthetic slot $slot 中文\n"
      files.write(slot, text)
      check(files.read(slot) == text && checkNotNull(files.modifiedAt(slot)) > 0)
      files.write(slot, "updated $slot")
      check(files.read(slot) == "updated $slot")
    }
    imageProbeStep = 465
    files.enabled = false
    check(!files.enabled)
    files.enabled = true
    check(files.enabled)
    imageProbeStep = 466
    check(runCatching { files.read(-1) }.isFailure)
    check(runCatching { files.write(3, "invalid") }.isFailure)
    check(runCatching { files.write(0, "x".repeat(2 * 1024 * 1024 + 1)) }.isFailure)
    check(files.read(0) == "updated 0")
    imageProbeStep = 4671
    val directory = HarmonyAppDirectories.cacheDir + "/bucc-diagnostics"
    files.delete(2)
    imageProbeStep = 4672
    if (symlink("$directory/events-0.jsonl", "$directory/events-2.jsonl") == 0) {
      try {
        imageProbeStep = 468
        check(runCatching { files.read(2) }.isFailure)
        files.write(2, "replacement")
        check(files.read(0) == "updated 0" && files.read(2) == "replacement")
      } finally {
        unlink("$directory/events-2.jsonl")
      }
    } else {
      check(errno == EPERM || errno == EACCES)
      // Some Harmony app sandboxes prohibit creating even an owned symlink.
      imageSymlinkProbeSkipped = true
    }
    imageProbeStep = 469
    val marker = "synthetic-message-must-not-enter-stack-frames"
    check(diagnosticStackFrames(IllegalStateException(marker)).none { marker in it })
  } finally {
    files.enabled = true
    repeat(3) { files.delete(it) }
  }
  imageProbeStep = 470
  repeat(3) { check(files.read(it) == null && files.modifiedAt(it) == null) }
}
