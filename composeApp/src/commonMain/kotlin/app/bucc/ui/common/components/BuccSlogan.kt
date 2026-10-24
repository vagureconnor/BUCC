package app.bucc.ui.common.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle

@Composable
fun buccSlogan(
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    outline: Boolean = false,
): AnnotatedString {
  val primary = MaterialTheme.colorScheme.primary
  val initials =
      SpanStyle(
          color = if (outline) primary.copy(alpha = .62f) else primary,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.Bold,
          fontSize = style.fontSize * 1.45f,
          shadow =
              Shadow(
                  color = primary.copy(alpha = if (outline) .5f else .38f),
                  offset = if (outline) Offset(1.6f, 2.2f) else Offset(.8f, 1.2f),
                  blurRadius = if (outline) .7f else .9f,
              ),
      )
  return buildAnnotatedString {
    listOf("Beyond", "Utility", "Comes", "Creativity.").forEachIndexed { index, word ->
      if (index > 0) append(" ")
      withStyle(initials) { append(word.first()) }
      append(word.drop(1))
    }
  }
}

/** Both layers share absolute type sizes; wrapping always happens between words. */
@Composable
fun BuccSlogan(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
  val measurer = rememberTextMeasurer()
  val base =
      style.copy(
          fontFamily = FontFamily.SansSerif,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
  val text = buccSlogan(base)
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val naturalWidth = measurer.measure(text, base, softWrap = false).size.width.coerceAtLeast(1)
    val scale = (constraints.maxWidth.toFloat() / naturalWidth).coerceIn(.75f, 1f)
    val fitted = base.copy(fontSize = base.fontSize * scale)
    Text(
        buccSlogan(fitted, outline = true),
        style = fitted.copy(color = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
    )
    Text(buccSlogan(fitted), style = fitted, modifier = Modifier.fillMaxWidth())
  }
}
