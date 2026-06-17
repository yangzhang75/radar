package com.shipradar.app.framework

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Compose-side OpenBridge theme. Wraps [ObColorTokens] (pure ARGB) as Compose [Color]s and exposes
 * them via [LocalObColors] / [LocalObTheme], plus a Material3 dark [androidx.compose.material3.ColorScheme]
 * derived from the same tokens so stock Material3 components inherit the maritime palette.
 *
 * Standards basis lives in [ObTokens]; this layer is purely the Compose adapter. Visual target:
 * OpenBridge 6.0 (native redraw). IEC 62288 §5.4.1.1 dark radar background, §4.5.1 lighter foreground.
 */

/** Compose mirror of [ObColorTokens]. */
data class ObColors(
    val operationalBackground: Color,
    val chromeBackground: Color,
    val chromeElevated: Color,
    val chromeBorder: Color,
    val foregroundPrimary: Color,
    val foregroundSecondary: Color,
    val foregroundDisabled: Color,
    val accent: Color,
    val accentForeground: Color,
    val alarm: Color,
    val warning: Color,
    val caution: Color,
    val echoLegendPeak: Color,
    // OpenBridge conning-instrument tokens (compass / instrument-field).
    val instrumentEnhancedPrimary: Color,
    val instrumentEnhancedSecondary: Color,
    val instrumentRegularSecondary: Color,
    val instrumentFramePrimary: Color,
    val instrumentFrameSecondary: Color,
    val instrumentFrameTertiary: Color,
)

/** Build the Compose colour set for [theme] from the pure tokens. */
fun obColors(theme: ObTheme): ObColors = with(obTokens(theme)) {
    ObColors(
        operationalBackground = Color(operationalBackground),
        chromeBackground = Color(chromeBackground),
        chromeElevated = Color(chromeElevated),
        chromeBorder = Color(chromeBorder),
        foregroundPrimary = Color(foregroundPrimary),
        foregroundSecondary = Color(foregroundSecondary),
        foregroundDisabled = Color(foregroundDisabled),
        accent = Color(accent),
        accentForeground = Color(accentForeground),
        alarm = Color(alarm),
        warning = Color(warning),
        caution = Color(caution),
        echoLegendPeak = Color(echoLegendPeak),
        instrumentEnhancedPrimary = Color(instrumentEnhancedPrimary),
        instrumentEnhancedSecondary = Color(instrumentEnhancedSecondary),
        instrumentRegularSecondary = Color(instrumentRegularSecondary),
        instrumentFramePrimary = Color(instrumentFramePrimary),
        instrumentFrameSecondary = Color(instrumentFrameSecondary),
        instrumentFrameTertiary = Color(instrumentFrameTertiary),
    )
}

/** Current ambient theme; defaults to NIGHT (the safe bridge default — preserves dark adaptation, §7.2.1). */
val LocalObTheme = staticCompositionLocalOf { ObTheme.NIGHT }

/** Current resolved chrome colours. */
val LocalObColors = staticCompositionLocalOf { obColors(ObTheme.NIGHT) }

/**
 * Accessor object, à la `MaterialTheme.colorScheme`. Use `OpenBridge.colors.alarm`, `OpenBridge.theme`.
 */
object OpenBridge {
    val colors: ObColors
        @Composable @ReadOnlyComposable get() = LocalObColors.current
    val theme: ObTheme
        @Composable @ReadOnlyComposable get() = LocalObTheme.current
}

/**
 * Root theme provider. Place once near the top of the HMI; the orchestrator passes the user-selected
 * [theme] (day/dusk/night). Provides the OpenBridge locals and a matching Material3 dark scheme so
 * any Material3 widget used inside also follows the palette.
 */
@Composable
fun OpenBridgeTheme(
    theme: ObTheme = ObTheme.NIGHT,
    content: @Composable () -> Unit,
) {
    val colors = obColors(theme)
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.accentForeground,
        background = colors.operationalBackground,
        onBackground = colors.foregroundPrimary,
        surface = colors.chromeBackground,
        onSurface = colors.foregroundPrimary,
        surfaceVariant = colors.chromeElevated,
        onSurfaceVariant = colors.foregroundSecondary,
        // surfaceContainer* 槽位:Card/Surface 等 Material3 容器默认取这里。darkColorScheme 的默认值
        // 在日间是暗的,必须显式映射到 OpenBridge chrome 色,否则浅色主题下控制面板卡片仍发黑。
        surfaceContainerLowest = colors.chromeBackground,
        surfaceContainerLow = colors.chromeBackground,
        surfaceContainer = colors.chromeElevated,
        surfaceContainerHigh = colors.chromeElevated,
        surfaceContainerHighest = colors.chromeElevated,
        secondaryContainer = colors.chromeElevated,
        onSecondaryContainer = colors.foregroundPrimary,
        outline = colors.chromeBorder,
        error = colors.alarm,
        onError = colors.foregroundPrimary,
    )
    CompositionLocalProvider(
        LocalObTheme provides theme,
        LocalObColors provides colors,
    ) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
