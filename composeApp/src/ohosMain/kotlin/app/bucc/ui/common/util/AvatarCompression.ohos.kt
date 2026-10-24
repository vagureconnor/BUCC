package app.bucc.ui.common.util

import app.bucc.api.storage.MAX_AVATAR_BYTES
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import app.bucc.api.storage.isValidAvatarInput
import cnames.structs.OH_DecodingOptions
import cnames.structs.OH_ImagePackerNative
import cnames.structs.OH_ImageSourceNative
import cnames.structs.OH_ImageSource_Info
import cnames.structs.OH_PackingOptions
import cnames.structs.OH_PixelmapNative
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.ImageKit.Image_NativeModule.*

@OptIn(ExperimentalForeignApi::class)
actual fun compressAvatarImage(image: PickedImage): PickedImage {
  require(isValidAvatarInput(image.bytes, image.mimeType)) {
    if (image.bytes.size > MAX_AVATAR_INPUT_BYTES) "图片超过 20 MB" else "无法识别图片格式，请选择图片文件"
  }

  return image.bytes.usePinned { pinnedBytes ->
    memScoped {
      val sourceOut = alloc<CPointerVar<OH_ImageSourceNative>>()
      sourceOut.value = null
      checkImageKitResult(
          OH_ImageSourceNative_CreateFromData(
              pinnedBytes.addressOf(0).reinterpret(),
              image.bytes.size.toULong(),
              sourceOut.ptr,
          ),
          "无法读取图片，请选择有效的图片文件",
      )
      val source = sourceOut.value ?: throw IllegalArgumentException("无法读取图片，请选择有效的图片文件")

      try {
        val imageInfoOut = alloc<CPointerVar<OH_ImageSource_Info>>()
        imageInfoOut.value = null
        checkImageKitResult(OH_ImageSourceInfo_Create(imageInfoOut.ptr), "无法读取图片，请选择有效的图片文件")
        val imageInfo = imageInfoOut.value ?: throw IllegalArgumentException("无法读取图片，请选择有效的图片文件")

        val sourceWidth: Int
        val sourceHeight: Int
        try {
          checkImageKitResult(
              OH_ImageSourceNative_GetImageInfo(source, 0, imageInfo),
              "无法读取图片，请选择有效的图片文件",
          )
          val widthOut = alloc<UIntVar>()
          val heightOut = alloc<UIntVar>()
          checkImageKitResult(
              OH_ImageSourceInfo_GetWidth(imageInfo, widthOut.ptr),
              "无法读取图片，请选择有效的图片文件",
          )
          checkImageKitResult(
              OH_ImageSourceInfo_GetHeight(imageInfo, heightOut.ptr),
              "无法读取图片，请选择有效的图片文件",
          )
          sourceWidth = widthOut.value.toInt()
          sourceHeight = heightOut.value.toInt()
        } finally {
          OH_ImageSourceInfo_Release(imageInfo)
        }
        require(sourceWidth > 0 && sourceHeight > 0) { "无法读取图片，请选择有效的图片文件" }

        val orientation = readExifOrientation(source)
        val scale = minOf(1.0, 1024.0 / maxOf(sourceWidth, sourceHeight))
        val decodeOptionsOut = alloc<CPointerVar<OH_DecodingOptions>>()
        decodeOptionsOut.value = null
        checkImageKitResult(OH_DecodingOptions_Create(decodeOptionsOut.ptr), "无法处理所选图片")
        val decodeOptions = decodeOptionsOut.value ?: throw IllegalArgumentException("无法处理所选图片")

        try {
          val desiredSize = alloc<Image_Size>()
          desiredSize.width = (sourceWidth * scale).toInt().coerceAtLeast(1).toUInt()
          desiredSize.height = (sourceHeight * scale).toInt().coerceAtLeast(1).toUInt()
          checkImageKitResult(
              OH_DecodingOptions_SetDesiredSize(decodeOptions, desiredSize.ptr),
              "无法处理所选图片",
          )

          val pixelmapOut = alloc<CPointerVar<OH_PixelmapNative>>()
          pixelmapOut.value = null
          checkImageKitResult(
              OH_ImageSourceNative_CreatePixelmap(source, decodeOptions, pixelmapOut.ptr),
              "无法读取图片，请选择有效的图片文件",
          )
          val pixelmap = pixelmapOut.value ?: throw IllegalArgumentException("无法读取图片，请选择有效的图片文件")

          try {
            applyExifOrientation(pixelmap, orientation)

            val packerOut = alloc<CPointerVar<OH_ImagePackerNative>>()
            packerOut.value = null
            checkImageKitResult(OH_ImagePackerNative_Create(packerOut.ptr), "无法压缩所选图片")
            val packer = packerOut.value ?: throw IllegalArgumentException("无法压缩所选图片")

            try {
              val packingOptionsOut = alloc<CPointerVar<OH_PackingOptions>>()
              packingOptionsOut.value = null
              checkImageKitResult(OH_PackingOptions_Create(packingOptionsOut.ptr), "无法压缩所选图片")
              val packingOptions =
                  packingOptionsOut.value ?: throw IllegalArgumentException("无法压缩所选图片")

              try {
                val jpegMime = "image/jpeg".cstr
                val mimeType = alloc<Image_String>()
                mimeType.data = jpegMime.ptr
                mimeType.size = "image/jpeg".length.toULong()
                checkImageKitResult(
                    OH_PackingOptions_SetMimeType(packingOptions, mimeType.ptr),
                    "无法压缩所选图片",
                )
                // The pixels have already been oriented, so avoid retaining the source EXIF
                // orientation.
                checkImageKitResult(
                    OH_PackingOptions_SetNeedsPackProperties(packingOptions, false),
                    "无法压缩所选图片",
                )

                val outputBuffer = allocArray<kotlinx.cinterop.UByteVar>(MAX_AVATAR_BYTES)
                for (quality in JPEG_QUALITIES) {
                  checkImageKitResult(
                      OH_PackingOptions_SetQuality(packingOptions, quality.toUInt()),
                      "无法压缩所选图片",
                  )
                  val outputSize = alloc<ULongVar>()
                  outputSize.value = MAX_AVATAR_BYTES.toULong()
                  val status =
                      OH_ImagePackerNative_PackToDataFromPixelmap(
                          packer,
                          packingOptions,
                          pixelmap,
                          outputBuffer,
                          outputSize.ptr,
                      )
                  if (status == IMAGE_SUCCESS && outputSize.value <= MAX_AVATAR_BYTES.toULong()) {
                    val output =
                        ByteArray(outputSize.value.toInt()) { index ->
                          outputBuffer[index].toByte()
                        }
                    return@memScoped PickedImage(
                        bytes = output,
                        fileName = image.fileName.substringBeforeLast('.', "avatar") + ".jpg",
                        mimeType = "image/jpeg",
                    )
                  }
                }
                throw IllegalArgumentException("图片压缩后仍超过 2 MB，请选择较简单的图片")
              } finally {
                OH_PackingOptions_Release(packingOptions)
              }
            } finally {
              OH_ImagePackerNative_Release(packer)
            }
          } finally {
            OH_PixelmapNative_Release(pixelmap)
          }
        } finally {
          OH_DecodingOptions_Release(decodeOptions)
        }
      } finally {
        OH_ImageSourceNative_Release(source)
      }
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun readExifOrientation(source: CPointer<OH_ImageSourceNative>): Int = memScoped {
  val keyString = "Orientation".cstr
  val key = alloc<Image_String>()
  key.data = keyString.ptr
  key.size = "Orientation".length.toULong()

  val value = alloc<Image_String>()
  value.data = null
  value.size = 0uL
  if (OH_ImageSourceNative_GetImagePropertyWithNull(source, key.ptr, value.ptr) != IMAGE_SUCCESS)
      return@memScoped 1
  val orientation =
      try {
        value.data?.toKString()
      } finally {
        platform.posix.free(value.data)
      } ?: return@memScoped 1
  orientation.toIntOrNull()?.takeIf { it in 1..8 }
      ?: when (orientation) {
        "Top-left" -> 1
        "Top-right" -> 2
        "Bottom-right" -> 3
        "Bottom-left" -> 4
        "Left-top" -> 5
        "Right-top" -> 6
        "Right-bottom" -> 7
        "Left-bottom" -> 8
        else -> 1
      }
}

@OptIn(ExperimentalForeignApi::class)
private fun applyExifOrientation(pixelmap: CPointer<OH_PixelmapNative>, orientation: Int) {
  val (horizontalFlip, verticalFlip, rotation) =
      when (orientation) {
        2 -> Triple(true, false, 0f)
        3 -> Triple(false, false, 180f)
        4 -> Triple(false, true, 0f)
        5 -> Triple(true, false, -90f)
        6 -> Triple(false, false, 90f)
        7 -> Triple(true, false, 90f)
        8 -> Triple(false, false, -90f)
        else -> Triple(false, false, 0f)
      }
  if (horizontalFlip || verticalFlip) {
    checkImageKitResult(OH_PixelmapNative_Flip(pixelmap, horizontalFlip, verticalFlip), "无法处理所选图片")
  }
  if (rotation != 0f) {
    checkImageKitResult(OH_PixelmapNative_Rotate(pixelmap, rotation), "无法处理所选图片")
  }
}

private fun checkImageKitResult(result: UInt, message: String) {
  require(result == IMAGE_SUCCESS) { message }
}

private val JPEG_QUALITIES = intArrayOf(92, 84, 76, 68, 60, 52, 44, 40)
