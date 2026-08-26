package ru.rfidhunter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.ui.theme.Dimens
import ru.rfidhunter.app.ui.theme.LocalExtendedColors

@Composable
internal fun StatusBanner(text: String, modifier: Modifier = Modifier) {
    val extendedColors = LocalExtendedColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.CardCorner),
        color = extendedColors.successContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = extendedColors.onSuccessContainer,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = extendedColors.onSuccessContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
