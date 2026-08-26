package ru.rfidhunter.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic roles the M3 scheme cannot express on its own. */
class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val cardSurface: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        success = SuccessGreen,
        onSuccess = Color.White,
        successContainer = SuccessContainer,
        onSuccessContainer = SuccessDeep,
        warning = WarningAmber,
        onWarning = OnWarningBrown,
        cardSurface = CardSurface,
    )
}

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = BrandBlueDark,
    error = DangerRed,
    onError = Color.White,
    errorContainer = DangerContainer,
    onErrorContainer = DangerRed,
    background = AppBackdrop,
    onBackground = NeutralInk,
    surface = AppBackdrop,
    onSurface = NeutralInk,
    surfaceVariant = NeutralSurfaceVariant,
    onSurfaceVariant = NeutralMuted,
    outline = NeutralOutline,
    outlineVariant = TrackGrey,
)

@Composable
fun RfidHunterTheme(content: @Composable () -> Unit) {
    val typography = remember { roomyTypography() }
    CompositionLocalProvider(
        LocalExtendedColors provides ExtendedColors(
            success = SuccessGreen,
            onSuccess = Color.White,
            successContainer = SuccessContainer,
            onSuccessContainer = SuccessDeep,
            warning = WarningAmber,
            onWarning = OnWarningBrown,
            cardSurface = CardSurface,
        ),
    ) {
        MaterialTheme(colorScheme = LightColorScheme, typography = typography, content = content)
    }
}
