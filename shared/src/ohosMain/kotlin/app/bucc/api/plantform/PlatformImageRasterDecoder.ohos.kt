@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.api.plantform

import cnames.structs.OH_DecodingOptions
import cnames.structs.OH_ImageSourceNative
import cnames.structs.OH_PixelmapNative
import cnames.structs.OH_Pixelmap_ImageInfo
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.ImageKit.Image_NativeModule.IMAGE_SUCCESS
import platform.ImageKit.Image_NativeModule.Image_ErrorCode
import platform.ImageKit.Image_NativeModule.OH_DecodingOptions_Create
import platform.ImageKit.Image_NativeModule.OH_DecodingOptions_Release
import platform.ImageKit.Image_NativeModule.OH_DecodingOptions_SetPixelFormat
import platform.ImageKit.Image_NativeModule.OH_ImageSourceNative_CreateFromData
import platform.ImageKit.Image_NativeModule.OH_ImageSourceNative_CreatePixelmap
import platform.ImageKit.Image_NativeModule.OH_ImageSourceNative_Release
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_Create
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_GetAlphaMode
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_GetHeight
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_GetPixelFormat
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_GetRowStride
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_GetWidth
import platform.ImageKit.Image_NativeModule.OH_PixelmapImageInfo_Release
import platform.ImageKit.Image_NativeModule.OH_PixelmapNative_GetImageInfo
import platform.ImageKit.Image_NativeModule.OH_PixelmapNative_ReadPixels
import platform.ImageKit.Image_NativeModule.OH_PixelmapNative_Release
import platform.ImageKit.Image_NativeModule.PIXELMAP_ALPHA_TYPE_OPAQUE
import platform.ImageKit.Image_NativeModule.PIXELMAP_ALPHA_TYPE_PREMULTIPLIED
import platform.ImageKit.Image_NativeModule.PIXELMAP_ALPHA_TYPE_UNPREMULTIPLIED
import platform.ImageKit.Image_NativeModule.PIXEL_FORMAT_RGBA_8888

internal actual object PlatformImageRasterDecoder {
  actual fun decode(input: ByteArray): LocalCgyyImageData {
    require(input.isNotEmpty()) { "Cannot decode an empty CGYY image" }

    return input.usePinned { pinned ->
      memScoped {
        var source: CPointer<OH_ImageSourceNative>? = null
        var options: CPointer<OH_DecodingOptions>? = null
        var pixelmap: CPointer<OH_PixelmapNative>? = null
        var info: CPointer<OH_Pixelmap_ImageInfo>? = null

        try {
          val sourceOut = alloc<CPointerVar<OH_ImageSourceNative>>()
          sourceOut.value = null
          val sourceStatus =
              OH_ImageSourceNative_CreateFromData(
                  pinned.addressOf(0).reinterpret<UByteVar>(),
                  input.size.convert(),
                  sourceOut.ptr,
              )
          source = sourceOut.value
          checkImage(sourceStatus, "create CGYY image source")
          val imageSource = source ?: error("ImageKit returned no CGYY image source")

          val optionsOut = alloc<CPointerVar<OH_DecodingOptions>>()
          optionsOut.value = null
          val optionsStatus = OH_DecodingOptions_Create(optionsOut.ptr)
          options = optionsOut.value
          checkImage(optionsStatus, "create CGYY image decoding options")
          val decodingOptions = options ?: error("ImageKit returned no CGYY decoding options")

          checkImage(
              OH_DecodingOptions_SetPixelFormat(
                  decodingOptions,
                  PIXEL_FORMAT_RGBA_8888.toInt(),
              ),
              "select RGBA_8888 for CGYY image",
          )

          val pixelmapOut = alloc<CPointerVar<OH_PixelmapNative>>()
          pixelmapOut.value = null
          val pixelmapStatus =
              OH_ImageSourceNative_CreatePixelmap(imageSource, decodingOptions, pixelmapOut.ptr)
          pixelmap = pixelmapOut.value
          checkImage(pixelmapStatus, "decode CGYY image")
          val decodedPixelmap = pixelmap ?: error("ImageKit returned no decoded CGYY image")

          val infoOut = alloc<CPointerVar<OH_Pixelmap_ImageInfo>>()
          infoOut.value = null
          val infoStatus = OH_PixelmapImageInfo_Create(infoOut.ptr)
          info = infoOut.value
          checkImage(infoStatus, "create CGYY image information")
          val imageInfo = info ?: error("ImageKit returned no CGYY image information")
          checkImage(
              OH_PixelmapNative_GetImageInfo(
                  decodedPixelmap,
                  imageInfo,
              ),
              "read CGYY image information",
          )

          val widthOut = alloc<UIntVar>()
          val heightOut = alloc<UIntVar>()
          val strideOut = alloc<UIntVar>()
          val formatOut = alloc<kotlinx.cinterop.IntVar>()
          val alphaTypeOut = alloc<kotlinx.cinterop.IntVar>()
          checkImage(
              OH_PixelmapImageInfo_GetWidth(imageInfo, widthOut.ptr),
              "read CGYY image width",
          )
          checkImage(
              OH_PixelmapImageInfo_GetHeight(imageInfo, heightOut.ptr),
              "read CGYY image height",
          )
          checkImage(
              OH_PixelmapImageInfo_GetRowStride(imageInfo, strideOut.ptr),
              "read CGYY image row stride",
          )
          checkImage(
              OH_PixelmapImageInfo_GetPixelFormat(imageInfo, formatOut.ptr),
              "read CGYY image pixel format",
          )
          checkImage(
              OH_PixelmapImageInfo_GetAlphaMode(imageInfo, alphaTypeOut.ptr),
              "read CGYY image alpha type",
          )

          val width = widthOut.value.toLong()
          val height = heightOut.value.toLong()
          val stride = strideOut.value.toLong()
          require(width in 1..Int.MAX_VALUE.toLong() && height in 1..Int.MAX_VALUE.toLong()) {
            "CGYY image has invalid dimensions"
          }
          val rowBytes = width * BYTES_PER_PIXEL
          require(stride >= rowBytes) { "CGYY image row stride is smaller than its pixel row" }
          val pixelCount = width * height
          val requiredByteCount = stride * height
          require(pixelCount <= Int.MAX_VALUE && requiredByteCount <= Int.MAX_VALUE) {
            "CGYY image is too large to decode"
          }
          check(formatOut.value == PIXEL_FORMAT_RGBA_8888.toInt()) {
            "ImageKit returned an unexpected CGYY pixel format"
          }

          val rawPixels = ByteArray(requiredByteCount.toInt())
          val readSize = alloc<ULongVar>()
          readSize.value = rawPixels.size.convert()
          rawPixels.usePinned { pixelsPinned ->
            checkImage(
                OH_PixelmapNative_ReadPixels(
                    decodedPixelmap,
                    pixelsPinned.addressOf(0).reinterpret<UByteVar>(),
                    readSize.ptr,
                ),
                "read CGYY image pixels",
            )
          }
          require(readSize.value.convert<Long>() >= requiredByteCount) {
            "ImageKit returned an incomplete CGYY pixel buffer"
          }

          val alphaType = alphaTypeOut.value
          val opaqueAlpha = PIXELMAP_ALPHA_TYPE_OPAQUE.toInt()
          val premultipliedAlpha = PIXELMAP_ALPHA_TYPE_PREMULTIPLIED.toInt()
          val unpremultipliedAlpha = PIXELMAP_ALPHA_TYPE_UNPREMULTIPLIED.toInt()
          check(
              alphaType == opaqueAlpha ||
                  alphaType == premultipliedAlpha ||
                  alphaType == unpremultipliedAlpha
          ) {
            "ImageKit returned an unsupported CGYY alpha type"
          }

          val argb = IntArray(pixelCount.toInt())
          for (y in 0 until height.toInt()) {
            val rowOffset = y * stride.toInt()
            for (x in 0 until width.toInt()) {
              val pixelOffset = rowOffset + x * BYTES_PER_PIXEL.toInt()
              val red = rawPixels[pixelOffset].toInt() and 0xff
              val green = rawPixels[pixelOffset + 1].toInt() and 0xff
              val blue = rawPixels[pixelOffset + 2].toInt() and 0xff
              val alpha =
                  if (alphaType == opaqueAlpha) 0xff
                  else rawPixels[pixelOffset + 3].toInt() and 0xff
              val straightRed =
                  if (alphaType == premultipliedAlpha) unpremultiply(red, alpha) else red
              val straightGreen =
                  if (alphaType == premultipliedAlpha) unpremultiply(green, alpha) else green
              val straightBlue =
                  if (alphaType == premultipliedAlpha) unpremultiply(blue, alpha) else blue
              argb[y * width.toInt() + x] =
                  (alpha shl 24) or (straightRed shl 16) or (straightGreen shl 8) or straightBlue
            }
          }

          LocalCgyyImageData(width.toInt(), height.toInt(), argb)
        } finally {
          info?.let { OH_PixelmapImageInfo_Release(it) }
          pixelmap?.let { OH_PixelmapNative_Release(it) }
          options?.let { OH_DecodingOptions_Release(it) }
          source?.let { OH_ImageSourceNative_Release(it) }
        }
      }
    }
  }

  private fun unpremultiply(channel: Int, alpha: Int): Int {
    if (alpha == 0) return 0
    return ((channel * 255 + alpha / 2) / alpha).coerceAtMost(255)
  }

  private fun checkImage(status: Image_ErrorCode, operation: String) {
    check(status == IMAGE_SUCCESS) { "Failed to $operation (ImageKit code $status)" }
  }

  private const val BYTES_PER_PIXEL = 4L
}
