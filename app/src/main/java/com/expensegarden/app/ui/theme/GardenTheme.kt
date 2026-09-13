package com.expensegarden.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 0xRRGGBB token -> opaque Compose Color. */
private fun rgb(token: Int) = Color(0xFF000000.toInt() or token)

/**
 * The three budget states. Carried beside the ColorScheme because Material 3 has no slot for
 * them: borrowing `primary` and `tertiary` is what made "on pace" and "ahead of pace" render as
 * two indistinguishable purples.
 */
@Immutable
data class GardenStatusColors(val onPace: Color, val warning: Color, val over: Color)

private val LightStatus = GardenStatusColors(
    onPace = rgb(GardenTokens.LightOnPace),
    warning = rgb(GardenTokens.LightWarning),
    over = rgb(GardenTokens.LightOver),
)

private val DarkStatus = GardenStatusColors(
    onPace = rgb(GardenTokens.DarkOnPace),
    warning = rgb(GardenTokens.DarkWarning),
    over = rgb(GardenTokens.DarkOver),
)

private val LocalGardenStatusColors = staticCompositionLocalOf { LightStatus }

/** `GardenStatus.colors.onPace` at any call site inside GardenTheme. */
object GardenStatus {
    val colors: GardenStatusColors
        @Composable @ReadOnlyComposable get() = LocalGardenStatusColors.current
}

private val LightScheme = lightColorScheme(
    primary = rgb(GardenTokens.LightPrimary),
    onPrimary = rgb(GardenTokens.LightOnPrimary),
    primaryContainer = rgb(GardenTokens.LightPrimaryContainer),
    onPrimaryContainer = rgb(GardenTokens.LightOnPrimaryContainer),
    secondary = rgb(GardenTokens.LightSecondary),
    onSecondary = rgb(GardenTokens.LightOnSecondary),
    secondaryContainer = rgb(GardenTokens.LightSecondaryContainer),
    onSecondaryContainer = rgb(GardenTokens.LightOnSecondaryContainer),
    tertiary = rgb(GardenTokens.LightTertiary),
    onTertiary = rgb(GardenTokens.LightOnTertiary),
    tertiaryContainer = rgb(GardenTokens.LightTertiaryContainer),
    onTertiaryContainer = rgb(GardenTokens.LightOnTertiaryContainer),
    error = rgb(GardenTokens.LightError),
    onError = rgb(GardenTokens.LightOnError),
    errorContainer = rgb(GardenTokens.LightErrorContainer),
    onErrorContainer = rgb(GardenTokens.LightOnErrorContainer),
    background = rgb(GardenTokens.LightSurface),
    onBackground = rgb(GardenTokens.LightOnSurface),
    surface = rgb(GardenTokens.LightSurface),
    onSurface = rgb(GardenTokens.LightOnSurface),
    surfaceVariant = rgb(GardenTokens.LightSurfaceVariant),
    onSurfaceVariant = rgb(GardenTokens.LightOnSurfaceVariant),
    outline = rgb(GardenTokens.LightOutline),
)

private val DarkScheme = darkColorScheme(
    primary = rgb(GardenTokens.DarkPrimary),
    onPrimary = rgb(GardenTokens.DarkOnPrimary),
    primaryContainer = rgb(GardenTokens.DarkPrimaryContainer),
    onPrimaryContainer = rgb(GardenTokens.DarkOnPrimaryContainer),
    secondary = rgb(GardenTokens.DarkSecondary),
    onSecondary = rgb(GardenTokens.DarkOnSecondary),
    secondaryContainer = rgb(GardenTokens.DarkSecondaryContainer),
    onSecondaryContainer = rgb(GardenTokens.DarkOnSecondaryContainer),
    tertiary = rgb(GardenTokens.DarkTertiary),
    onTertiary = rgb(GardenTokens.DarkOnTertiary),
    tertiaryContainer = rgb(GardenTokens.DarkTertiaryContainer),
    onTertiaryContainer = rgb(GardenTokens.DarkOnTertiaryContainer),
    error = rgb(GardenTokens.DarkError),
    onError = rgb(GardenTokens.DarkOnError),
    errorContainer = rgb(GardenTokens.DarkErrorContainer),
    onErrorContainer = rgb(GardenTokens.DarkOnErrorContainer),
    background = rgb(GardenTokens.DarkSurface),
    onBackground = rgb(GardenTokens.DarkOnSurface),
    surface = rgb(GardenTokens.DarkSurface),
    onSurface = rgb(GardenTokens.DarkOnSurface),
    surfaceVariant = rgb(GardenTokens.DarkSurfaceVariant),
    onSurfaceVariant = rgb(GardenTokens.DarkOnSurfaceVariant),
    outline = rgb(GardenTokens.DarkOutline),
)

/**
 * Typography and shapes are Material's defaults on purpose (spec section 9): Roboto is neutral
 * and legible, and a display face is a real design commitment with asset weight behind it.
 */
@Composable
fun GardenTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGardenStatusColors provides if (dark) DarkStatus else LightStatus,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
