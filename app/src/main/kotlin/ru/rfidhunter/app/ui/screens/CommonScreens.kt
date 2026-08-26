package ru.rfidhunter.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.PillRole
import ru.rfidhunter.app.ui.components.TextButton
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.Dimens

@Composable internal fun StatusScreen(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, style = AppStyles.Subtitle, textAlign = TextAlign.Center) }

@Composable internal fun MessageScreen(text: String, onNext: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(text, fontSize = 28.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(20.dp)); Button(onNext, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight), icon = Icons.Filled.Check) { Text(stringResource(R.string.message_ok_action), style = AppStyles.ButtonLabel) } } }
@Composable
internal fun ConfirmScreen(
    text: String,
    highlightedTitle: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(text, style = AppStyles.Headline, textAlign = TextAlign.Center)
        if (!highlightedTitle.isNullOrBlank()) {
            Spacer(Modifier.height(18.dp))
            ThingName(highlightedTitle, fontSize = 38.sp)
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onConfirm,
            Modifier.fillMaxWidth().height(Dimens.PillButtonHeight),
            role = PillRole.Success,
            icon = Icons.Filled.Check,
        ) { Text(stringResource(R.string.confirm_accept_action), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.weight(1f))
        TextButton(
            onCancel,
            Modifier.fillMaxWidth().height(Dimens.PillButtonHeight),
            icon = Icons.Filled.Close,
        ) { Text(stringResource(R.string.confirm_cancel_action), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
    }
}
