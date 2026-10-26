package app.bucc.ui.screens.libbook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.bucc.model.dto.LibBookSeatDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun libBookSeatMapHasCoordinates(seats: List<LibBookSeatDto>): Boolean =
    seats.any(::libBookSeatHasCoordinates)

internal fun libBookSeatHasCoordinates(seat: LibBookSeatDto): Boolean = seat.mapRegion() != null

private fun LibBookSeatDto.mapRegion(): LibBookMapSeatRegion? =
    libBookMapSeatRegion(id, pointX, pointY, width, height, status, inLabel)

/**
 * HD image pixels are preserved; only seat fill pixels are replaced, in original image coordinates.
 */
@Composable
internal fun LibBookSeatMap(
    base: ImageBitmap,
    seats: List<LibBookSeatDto>,
    selectedSeatId: String?,
    onSeatClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    palette: LibBookSeatMapPalette = LibBookSeatMapPalette(),
) {
  val regions = remember(seats) { seats.mapNotNull { it.mapRegion() } }
  val original = remember(base) { IntArray(base.width * base.height).also { base.readPixels(it) } }
  val rendered by
      produceState(base, base, regions, palette) {
        value =
            withContext(Dispatchers.Default) {
              val pixels =
                  recolorLibBookMapPixels(original, base.width, base.height, regions, palette)
              libBookSeatMapBitmap(base, original, pixels)
            }
      }
  var gesture by remember(base) { mutableStateOf(LibBookMapGestureTransform()) }
  var viewSize by remember { mutableStateOf(IntSize.Zero) }
  val transform = rememberTransformableState { zoomChange, panChange, _ ->
    gesture = updateLibBookMapGesture(gesture, zoomChange, panChange.x, panChange.y)
  }
  val viewport =
      LibBookMapViewport(
          viewSize.width.toFloat(),
          viewSize.height.toFloat(),
          base.width,
          base.height,
          gesture.zoom,
          gesture.panX,
          gesture.panY,
      )
  val selectedColor = MaterialTheme.colorScheme.primary
  Box(modifier.clipToBounds()) {
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { viewSize = it }
            .libBookMapTrackpadZoom { factor -> gesture = updateLibBookMapGesture(gesture, factor) }
            .transformable(transform)
            .pointerInput(viewport, regions, onSeatClick) {
              detectTapGestures { point ->
                libBookSeatMapHit(regions, viewport, point.x, point.y)?.let(onSeatClick)
              }
            }
    ) {
      if (viewport.scale > 0) {
        val topLeft = viewport.imageToView(0f, 0f)
        withTransform({
          translate(topLeft.first, topLeft.second)
          scale(viewport.scale, viewport.scale, Offset.Zero)
        }) {
          drawImage(rendered)
        }
        regions
            .firstOrNull { it.id == selectedSeatId }
            ?.let { region ->
              val frame = libBookMapSelectionFrame(region, viewport, density)
              val position = Offset(frame.left, frame.top)
              val frameSize = Size(frame.width, frame.height)
              // Screen-space strokes stay bold at every map zoom and never cover seat numbers.
              drawRect(Color.White, position, frameSize, style = Stroke(frame.outerStrokePx))
              drawRect(selectedColor, position, frameSize, style = Stroke(frame.innerStrokePx))
            }
      }
    }
    Row(
        Modifier.align(Alignment.TopEnd).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { gesture = updateLibBookMapGesture(gesture, 1.25f) }) {
        Icon(Icons.Default.ZoomIn, "放大地图")
      }
      IconButton(onClick = { gesture = updateLibBookMapGesture(gesture, 0.8f) }) {
        Icon(Icons.Default.ZoomOut, "缩小地图")
      }
      TextButton(onClick = { gesture = LibBookMapGestureTransform() }) { Text("重置") }
    }
  }
}

private fun libBookSeatMapBitmap(
    base: ImageBitmap,
    original: IntArray,
    pixels: IntArray,
): ImageBitmap {
  val changes = mutableMapOf<Int, MutableList<Offset>>()
  for (index in pixels.indices) {
    if (pixels[index] != original[index]) {
      changes
          .getOrPut(pixels[index]) { mutableListOf() }
          .add(Offset((index % base.width) + 0.5f, (index / base.width) + 0.5f))
    }
  }
  if (changes.isEmpty()) return base
  val output = ImageBitmap(base.width, base.height)
  val canvas = BitmapCanvas(output)
  canvas.drawImage(base, Offset.Zero, Paint())
  val paint =
      Paint().apply {
        strokeWidth = 1f
        isAntiAlias = false
      }
  changes.forEach { (argb, points) ->
    paint.color = Color(argb)
    canvas.drawPoints(PointMode.Points, points, paint)
  }
  return output
}
