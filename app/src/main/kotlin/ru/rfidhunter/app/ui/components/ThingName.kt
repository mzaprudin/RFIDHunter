package ru.rfidhunter.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.theme.AppStyles

/** The one visual treatment for a user-facing item name. */
@Composable
internal fun ThingName(
    name: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    fontSize: androidx.compose.ui.unit.TextUnit = 30.sp,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Center,
    includePrefix: Boolean = true,
) {
    val prefix = if (includePrefix) stringResource(R.string.thing_name_prefix) else ""
    Text(
        buildAnnotatedString {
            append(prefix)
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
            append(name)
            pop()
        },
        modifier = modifier,
        style = AppStyles.ThingTitle,
        fontSize = fontSize,
        textAlign = textAlign,
    )
}
