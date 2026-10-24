package app.bucc.ui.screens.libbook

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// The shared map handles multi-touch zoom and pan with Compose pointer gestures.
@Composable
internal actual fun Modifier.libBookMapTrackpadZoom(onZoom: (Float) -> Unit): Modifier = this
