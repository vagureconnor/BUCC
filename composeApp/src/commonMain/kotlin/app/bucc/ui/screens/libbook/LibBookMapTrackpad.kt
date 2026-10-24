package app.bucc.ui.screens.libbook

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Mobile touch stays with transformable; desktop supplies native trackpad magnification. */
@Composable internal expect fun Modifier.libBookMapTrackpadZoom(onZoom: (Float) -> Unit): Modifier
