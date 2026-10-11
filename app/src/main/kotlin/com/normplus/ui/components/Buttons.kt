package com.normplus.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.normplus.ui.theme.NormPlusTheme

/** What a [PillButton] does, which decides how loud it is. */
enum class PillTone {
    /** The way on: violet, filled. One per place. */
    Primary,
    /** An everyday action: a raised grey pill with violet words ("Earlier", "Later", "Sync"). */
    Tonal,
    /** A neutral action on a raised pill, in the text colour ("Not now", "Details"). */
    Neutral,
    /** An action that removes something ("Forget this watch"): raised, with red words. */
    Danger,
    /** The quietest: violet words, no pill until pressed ("Retry" on a row). */
    Quiet,
}

/**
 * A pill button: Material's [Button] (or [TextButton] for [PillTone.Quiet]) in Dark Dial's
 * colours, full round, at least 48 dp tall. [icon] sits before the words and is decorative.
 *
 * @param colorsOverride for a pill printed on a coloured banner, whose fill decides its colours.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Tonal,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    colorsOverride: ButtonColors? = null,
) {
    val spacing = NormPlusTheme.spacing
    val padding = PaddingValues(horizontal = spacing.cardPadding, vertical = spacing.s)
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(spacing.smallIcon))
            Spacer(Modifier.width(spacing.s))
        }
        Text(text, style = NormPlusTheme.type.pill, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val sized = modifier.heightIn(min = spacing.touchTarget)
    if (tone == PillTone.Quiet && colorsOverride == null) {
        TextButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = NormPlusTheme.shapes.pill,
            contentPadding = PaddingValues(horizontal = spacing.m, vertical = spacing.s),
        ) { content() }
        return
    }
    Button(
        onClick = onClick,
        modifier = sized,
        enabled = enabled,
        shape = NormPlusTheme.shapes.pill,
        colors = colorsOverride ?: pillColors(tone),
        contentPadding = padding,
    ) { content() }
}

/** The colours of a [PillButton] of [tone]. */
@Composable
fun pillColors(tone: PillTone): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    val disabledContainer = c.raised.copy(alpha = DISABLED_CONTAINER)
    val disabledContent = scheme.onSurface.copy(alpha = DISABLED_CONTENT)
    return when (tone) {
        PillTone.Primary -> ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = disabledContainer,
            disabledContentColor = disabledContent,
        )
        PillTone.Tonal -> ButtonDefaults.buttonColors(
            containerColor = c.raised,
            contentColor = scheme.primary,
            disabledContainerColor = disabledContainer,
            disabledContentColor = disabledContent,
        )
        PillTone.Neutral -> ButtonDefaults.buttonColors(
            containerColor = c.raised,
            contentColor = scheme.onSurface,
            disabledContainerColor = disabledContainer,
            disabledContentColor = disabledContent,
        )
        PillTone.Danger -> ButtonDefaults.buttonColors(
            containerColor = c.raised,
            contentColor = scheme.error,
            disabledContainerColor = disabledContainer,
            disabledContentColor = disabledContent,
        )
        PillTone.Quiet -> ButtonDefaults.textButtonColors(contentColor = scheme.primary)
    }
}

/** A pill printed on a filled banner: the banner's words become the pill, its fill the words. */
@Composable
internal fun invertedPillColors(fill: Color, words: Color): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = words, contentColor = fill)

/** Material's disabled alphas. */
internal const val DISABLED_CONTENT = 0.38f
internal const val DISABLED_CONTAINER = 0.6f
