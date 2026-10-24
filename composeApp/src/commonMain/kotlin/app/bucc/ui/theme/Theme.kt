package app.bucc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.bucc.repository.ScheduleStore

enum class ThemeMode(val label: String) {
  SYSTEM("跟随系统"),
  LIGHT("浅色"),
  DARK("深色"),
}

object ThemePreferenceStore {
  private const val KEY_PREFIX = "theme_preference"

  fun read(accountId: String): ThemeMode {
    if (accountId.isBlank()) return ThemeMode.SYSTEM
    return runCatching {
          ThemeMode.valueOf(
              ScheduleStore.settings.getStringOrNull("$KEY_PREFIX:$accountId") ?: "SYSTEM"
          )
        }
        .getOrDefault(ThemeMode.SYSTEM)
  }

  fun write(accountId: String, mode: ThemeMode) {
    if (accountId.isNotBlank())
        ScheduleStore.settings.putString("$KEY_PREFIX:$accountId", mode.name)
  }
}

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF8CC8FF),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF174F7D),
        onPrimaryContainer = Color(0xFFD1E8FF),
        secondary = Color(0xFFB5D1EA),
        onSecondary = Color(0xFF1B344B),
        secondaryContainer = Color(0xFF294C6B),
        onSecondaryContainer = Color(0xFFD6E9FB),
        tertiary = Color(0xFF83D0F2),
        onTertiary = Color(0xFF003546),
        tertiaryContainer = Color(0xFF124E63),
        onTertiaryContainer = Color(0xFFC1EAFB),
        background = Color(0xFF101820),
        onBackground = Color(0xFFE1EAF2),
        surface = Color(0xFF101820),
        onSurface = Color(0xFFE1EAF2),
        surfaceVariant = Color(0xFF3A4855),
        onSurfaceVariant = Color(0xFFC1CED9),
        outline = Color(0xFF8998A5),
        outlineVariant = Color(0xFF3A4855),
        surfaceTint = Color(0xFF8CC8FF),
        surfaceBright = Color(0xFF35434F),
        surfaceDim = Color(0xFF101820),
        surfaceContainerLowest = Color(0xFF0B1219),
        surfaceContainerLow = Color(0xFF17212B),
        surfaceContainer = Color(0xFF1D2934),
        surfaceContainerHigh = Color(0xFF25333F),
        surfaceContainerHighest = Color(0xFF2E3E4B),
    )
private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF087BC1),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD0ECFF),
        onPrimaryContainer = Color(0xFF00334F),
        secondary = Color(0xFF476A88),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD8EBFA),
        onSecondaryContainer = Color(0xFF17364F),
        tertiary = Color(0xFF087E9E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBDEEFF),
        onTertiaryContainer = Color(0xFF003545),
        background = Color(0xFFF6FAFD),
        onBackground = Color(0xFF17232D),
        surface = Color(0xFFF6FAFD),
        onSurface = Color(0xFF17232D),
        surfaceVariant = Color(0xFFDDE8EF),
        onSurfaceVariant = Color(0xFF3D4D59),
        outline = Color(0xFF6D7F8C),
        outlineVariant = Color(0xFFC4D5E2),
        surfaceTint = Color(0xFF087BC1),
        surfaceBright = Color(0xFFFAFCFF),
        surfaceDim = Color(0xFFD7E4EF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F7FC),
        surfaceContainer = Color(0xFFEAF3FA),
        surfaceContainerHigh = Color(0xFFE2EEF7),
        surfaceContainerHighest = Color(0xFFD9E8F3),
    )

@Composable
fun BUCCTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
  val darkTheme =
      when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
      }
  val colorScheme =
      when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
      }

  MaterialTheme(colorScheme = colorScheme, typography = getAppTypography()) {
    Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background) { content() }
  }
}
