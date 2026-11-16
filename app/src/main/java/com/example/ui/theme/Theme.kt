package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat

private val UltraRestoreColorScheme = darkColorScheme(
    primary = CrimsonRed,
    onPrimary = PureWhite,
    primaryContainer = CrimsonDark,
    onPrimaryContainer = PureWhite,
    secondary = PureWhite,
    onSecondary = MatteBlack,
    secondaryContainer = CarbonElevated,
    onSecondaryContainer = PureWhite,
    tertiary = CrimsonGlow,
    onTertiary = PureWhite,
    background = MatteBlack,
    onBackground = PureWhite,
    surface = CarbonSurface,
    onSurface = PureWhite,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = MutedSilver,
    outline = SubtleBorder,
    outlineVariant = BrightBorder,
    error = CrimsonGlow,
    onError = PureWhite,
)

val UltraRestoreShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun UltraRestoreTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = UltraRestoreColorScheme,
        typography = UltraRestoreTypography,
        shapes = UltraRestoreShapes,
        content = content,
    )
}
