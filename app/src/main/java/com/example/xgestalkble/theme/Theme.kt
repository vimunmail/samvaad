package com.example.xgestalkble.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * App-wide theme state. Persisted to SharedPreferences so the user's
 * light/dark choice survives app restarts. Call [init] once at startup.
 */
object ThemeConfig {
    private const val PREFS_NAME = "AppTheme"
    private const val KEY_DARK = "isDarkTheme"

    var isDarkTheme by mutableStateOf(false)
        private set

    fun init(context: Context) {
        isDarkTheme = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, false)
    }

    fun toggle(context: Context) = setDark(context, !isDarkTheme)

    fun setDark(context: Context, dark: Boolean) {
        isDarkTheme = dark
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = DarkElectricCyan,
    onPrimary = Color(0xFF06101F),
    primaryContainer = Color(0xFF17335C),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = DarkNeonBlue,
    onSecondary = Color(0xFF06101F),
    secondaryContainer = Color(0xFF1B2C4A),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = DarkVioletAccent,
    onTertiary = Color(0xFF1B102E),
    tertiaryContainer = Color(0xFF332352),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = DarkBg,
    onBackground = DarkTitleText,
    surface = DarkCardBg,
    onSurface = DarkTitleText,
    surfaceVariant = DarkSubtleBg,
    onSurfaceVariant = DarkSlateTextDark,
    surfaceContainer = DarkCardBg,
    surfaceContainerHigh = DarkSubtleBg,
    error = DarkCrimsonRed,
    onError = Color(0xFF210A0A),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFFDADA),
    outline = DarkCardBorder,
    outlineVariant = Color(0xFF222738),
)

private val LightColorScheme = lightColorScheme(
    primary = LightElectricCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBFF),
    onPrimaryContainer = Color(0xFF093A66),
    secondary = LightNeonBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEAF7),
    onSecondaryContainer = Color(0xFF0A2C45),
    tertiary = LightVioletAccent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE4FF),
    onTertiaryContainer = Color(0xFF2E1065),
    background = LightBg,
    onBackground = LightTitleText,
    surface = LightCardBg,
    onSurface = LightTitleText,
    surfaceVariant = LightSubtleBg,
    onSurfaceVariant = LightSlateText,
    surfaceContainer = LightCardBg,
    surfaceContainerHigh = LightSubtleBg,
    error = LightCrimsonRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE2E2),
    onErrorContainer = Color(0xFF7A1B22),
    outline = LightCardBorder,
    outlineVariant = Color(0xFFEDF1F7),
)

// Bold claymorphic shapes — pillowy corners on every Material component
// (buttons, dialogs, text fields, menus) so the whole app feels molded.
private val ClayShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun SamvaadTheme(
    darkTheme: Boolean = ThemeConfig.isDarkTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Keep status/navigation bar icons readable in both themes (edge-to-edge)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ClayShapes,
        content = content
    )
}
