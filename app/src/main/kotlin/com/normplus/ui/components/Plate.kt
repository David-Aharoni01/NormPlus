package com.normplus.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.ReversedColors

/**
 * The one reversed ink-blue plate a screen may have: the backing behind Today's sheet, the
 * watch card on Watch, the confirm step of the firmware flow. One per screen, never more.
 *
 * Everything inside prints reversed without a colour at the call site: text and icons take
 * the plate's ink, and Material components come out light on ink (a filled Button is white
 * with ink-blue words). A [DaySheet] placed on the plate prints normally again.
 *
 * @param shape [NormPlusTheme.shapes].plate by default; Today's backing, which continues the
 *   top app bar, passes RectangleShape.
 */
@Composable
fun Plate(
    modifier: Modifier = Modifier,
    shape: Shape = NormPlusTheme.shapes.plate,
    contentPadding: PaddingValues = PaddingValues(NormPlusTheme.spacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NormPlusTheme.colors
    ReversedColors {
        Surface(modifier = modifier, shape = shape, color = colors.plate, contentColor = colors.onPlate) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}
