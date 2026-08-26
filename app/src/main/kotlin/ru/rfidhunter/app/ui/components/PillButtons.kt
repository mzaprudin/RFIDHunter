package ru.rfidhunter.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ru.rfidhunter.app.ui.theme.LocalExtendedColors

internal enum class PillRole { Primary, Success, Warning, Danger }

@Composable
private fun pillButtonColors(role: PillRole): ButtonColors = when (role) {
    PillRole.Primary -> ButtonDefaults.buttonColors()
    PillRole.Success -> ButtonDefaults.buttonColors(
        containerColor = LocalExtendedColors.current.success,
        contentColor = LocalExtendedColors.current.onSuccess,
    )
    PillRole.Warning -> ButtonDefaults.buttonColors(
        containerColor = LocalExtendedColors.current.warning,
        contentColor = LocalExtendedColors.current.onWarning,
    )
    PillRole.Danger -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    )
}

/**
 * All ordinary buttons go through the same forgiving touch target: the
 * initial pointer decides the button, small movement does not cancel it, a
 * second finger is ignored, and accidental double activation is debounced.
 */
@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: PillRole = PillRole.Primary,
    shape: Shape = RoundedCornerShape(50),
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(90),
        label = "pill-press",
    )
    MaterialButton(
        onClick = {},
        // Input stays outside the scaled layer: the touch target never shrinks.
        modifier = modifier
            .resilientButtonInput(onClick, enabled, screenTouchEnabled)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        colors = pillButtonColors(role),
        shape = shape,
        interactionSource = interactionSource,
    ) {
        PillButtonLeadingIcon(icon)
        content()
    }
}

@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(90),
        label = "pill-press",
    )
    MaterialOutlinedButton(
        onClick = {},
        // Input stays outside the scaled layer: the touch target never shrinks.
        modifier = modifier
            .resilientButtonInput(onClick, enabled, screenTouchEnabled)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
    ) {
        PillButtonLeadingIcon(icon)
        content()
    }
}

@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    MaterialTextButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled, screenTouchEnabled),
        enabled = enabled,
    ) {
        PillButtonLeadingIcon(icon)
        content()
    }
}

@Composable
private fun PillButtonLeadingIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
    }
}
