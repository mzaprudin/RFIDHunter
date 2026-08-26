package ru.rfidhunter.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Large-print type scale for the user-facing screens. Every style keeps the
 * roomy 1.28 line height the old Typography patch applied, so text density
 * stays familiar.
 */
object AppStyles {
    val ScreenTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 1.28.em)
    val Headline = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 1.28.em)
    val ThingTitle = TextStyle(fontSize = 30.sp, lineHeight = 1.28.em)
    val Subtitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 1.28.em)
    val BodyMedium = TextStyle(fontSize = 21.sp, lineHeight = 1.28.em)
    val ButtonLabel = TextStyle(fontSize = 20.sp, lineHeight = 1.28.em)
    val ButtonLabelSmall = TextStyle(fontSize = 18.sp, lineHeight = 1.28.em)
    val Hint = TextStyle(fontSize = 16.sp, lineHeight = 1.28.em)
    val Caption = TextStyle(fontSize = 15.sp, lineHeight = 1.28.em)
}

fun roomyTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(lineHeight = 1.28.em),
        displayMedium = base.displayMedium.copy(lineHeight = 1.28.em),
        displaySmall = base.displaySmall.copy(lineHeight = 1.28.em),
        headlineLarge = base.headlineLarge.copy(lineHeight = 1.28.em),
        headlineMedium = base.headlineMedium.copy(lineHeight = 1.28.em),
        headlineSmall = base.headlineSmall.copy(lineHeight = 1.28.em),
        titleLarge = base.titleLarge.copy(lineHeight = 1.28.em),
        titleMedium = base.titleMedium.copy(lineHeight = 1.28.em),
        titleSmall = base.titleSmall.copy(lineHeight = 1.28.em),
        bodyLarge = base.bodyLarge.copy(lineHeight = 1.28.em),
        bodyMedium = base.bodyMedium.copy(lineHeight = 1.28.em),
        bodySmall = base.bodySmall.copy(lineHeight = 1.28.em),
        labelLarge = base.labelLarge.copy(lineHeight = 1.28.em),
        labelMedium = base.labelMedium.copy(lineHeight = 1.28.em),
        labelSmall = base.labelSmall.copy(lineHeight = 1.28.em),
    )
}
