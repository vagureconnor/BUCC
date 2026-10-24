package app.bucc.ui.screens.libbook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import app.bucc.api.feature.fetchLibBookMapImage
import app.bucc.model.dto.LibBookMapImagesDto
import app.bucc.model.dto.LibBookSeatDto
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.imageResource

private data class LibBookLoadedMap(val url: String, val bitmap: ImageBitmap?)

/** Local HD resources win. A new remote URL never exposes the previous area's bitmap. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun rememberLibBookMapBitmap(local: DrawableResource?, remoteUrl: String): ImageBitmap? {
  if (local != null) return imageResource(local)
  val result by
      produceState(LibBookLoadedMap(remoteUrl, null), remoteUrl) {
        value = LibBookLoadedMap(remoteUrl, decodeLibBookMap(remoteUrl))
      }
  return result.takeIf { it.url == remoteUrl }?.bitmap
}

private data class LibBookPaletteRequest(
    val base: ImageBitmap,
    val regions: List<LibBookMapSeatRegion>,
    val images: LibBookMapImagesDto,
)

private data class LibBookLoadedPalette(
    val request: LibBookPaletteRequest,
    val palette: LibBookSeatMapPalette,
)

/** Sample official full-map state sprites at the same seat fill coordinates; never the legend. */
@Composable
internal fun rememberLibBookMapPalette(
    base: ImageBitmap,
    seats: List<LibBookSeatDto>,
    images: LibBookMapImagesDto,
): LibBookSeatMapPalette {
  val regions =
      seats.mapNotNull {
        libBookMapSeatRegion(it.id, it.pointX, it.pointY, it.width, it.height, "1", true)
      }
  val request = remember(base, regions, images) { LibBookPaletteRequest(base, regions, images) }
  val fallback = LibBookSeatMapPalette()
  val result by
      produceState(LibBookLoadedPalette(request, fallback), request) {
        val palette =
            withContext(Dispatchers.Default) {
              val pixels = IntArray(base.width * base.height).also { base.readPixels(it) }
              // Store only five derived colors. Decode one full map at a time, releasing it
              // before fetching the next; identical state URLs are fetched only once.
              val colors = mutableMapOf<String, Int?>()
              suspend fun stateColor(url: String, default: Int): Int {
                if (url.isBlank()) return default
                if (!colors.containsKey(url)) {
                  val bitmap = decodeLibBookMap(url)
                  colors[url] =
                      bitmap?.let {
                        val statePixels =
                            IntArray(it.width * it.height).also { buffer -> it.readPixels(buffer) }
                        sampleLibBookStateFill(
                            pixels,
                            base.width,
                            base.height,
                            statePixels,
                            it.width,
                            it.height,
                            regions,
                        )
                      }
                }
                return colors[url] ?: default
              }
              fallback.copy(
                  booked = stateColor(images.book, fallback.booked),
                  inUse = stateColor(images.use, fallback.inUse),
                  leave = stateColor(images.leave, fallback.leave),
                  closed = stateColor(images.close, fallback.closed),
                  restricted = stateColor(images.notAvailable, fallback.restricted),
              )
            }
        value = LibBookLoadedPalette(request, palette)
      }
  return if (result.request == request) result.palette else fallback
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun decodeLibBookMap(url: String): ImageBitmap? {
  if (url.isBlank()) return null
  return try {
    val bytes = fetchLibBookMapImage(url)
    if (bytes.size > 8 * 1024 * 1024) null
    else
        withContext(Dispatchers.Default) {
          bytes.decodeToImageBitmap().takeIf {
            it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 16_777_216
          }
        }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }
}

/**
 * Pure image sampling, including normalized coordinates when the official sprite is lower
 * resolution.
 */
internal fun sampleLibBookStateFill(
    base: IntArray,
    baseWidth: Int,
    baseHeight: Int,
    state: IntArray,
    stateWidth: Int,
    stateHeight: Int,
    regions: List<LibBookMapSeatRegion>,
): Int? {
  if (
      baseWidth <= 0 ||
          baseHeight <= 0 ||
          stateWidth <= 0 ||
          stateHeight <= 0 ||
          base.size.toLong() != baseWidth.toLong() * baseHeight ||
          state.size.toLong() != stateWidth.toLong() * stateHeight
  )
      return null
  val baseAspect = baseWidth.toDouble() / baseHeight
  if (abs(stateWidth.toDouble() / stateHeight / baseAspect - 1) > 0.01) return null
  data class Bucket(var count: Int = 0, var red: Long = 0, var green: Long = 0, var blue: Long = 0)
  val histogram = mutableMapOf<Int, Bucket>()
  for (region in regions) {
    val left = (region.x * baseWidth / 100).toInt().coerceIn(0, baseWidth)
    val top = (region.y * baseHeight / 100).toInt().coerceIn(0, baseHeight)
    val right = ((region.x + region.width) * baseWidth / 100).toInt().coerceIn(left, baseWidth)
    val bottom = ((region.y + region.height) * baseHeight / 100).toInt().coerceIn(top, baseHeight)
    for (y in top until bottom) for (x in left until right) {
      val original = base[y * baseWidth + x]
      val br = original ushr 16 and 255
      val bg = original ushr 8 and 255
      val bb = original and 255
      if (
          max(br, max(bg, bb)) < 100 ||
              max(br, max(bg, bb)) - min(br, min(bg, bb)) < 48 ||
              max(bg, bb) < br * 1.18 ||
              bg < br * 1.08
      )
          continue
      val sx = ((x + 0.5) * stateWidth / baseWidth).toInt().coerceAtMost(stateWidth - 1)
      val sy = ((y + 0.5) * stateHeight / baseHeight).toInt().coerceAtMost(stateHeight - 1)
      val target = state[sy * stateWidth + sx]
      val r = target ushr 16 and 255
      val g = target ushr 8 and 255
      val b = target and 255
      // Transparent/background/number/outline pixels cannot define a fill color.
      if (target ushr 24 < 200 || min(r, min(g, b)) > 232 || max(r, max(g, b)) < 70) continue
      val bin = ((r / 16) shl 8) or ((g / 16) shl 4) or (b / 16)
      histogram
          .getOrPut(bin) { Bucket() }
          .apply {
            count++
            red += r
            green += g
            blue += b
          }
    }
  }
  val best = histogram.values.maxByOrNull { it.count } ?: return null
  return 0xff000000.toInt() or
      ((best.red / best.count).toInt() shl 16) or
      ((best.green / best.count).toInt() shl 8) or
      (best.blue / best.count).toInt()
}
