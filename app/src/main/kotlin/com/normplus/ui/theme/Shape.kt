package com.normplus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * Paper is cut square. The Material shape scale is tightened so cards, menus, dialogs and
 * sheets read as printed stock rather than soft tiles; controls (buttons, chips, switches,
 * the navigation indicator) keep Material's own shapes, so they stay the controls people know.
 */
internal val NormShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * The Day Sheet's own shapes. Read as `NormPlusTheme.shapes`.
 *
 * @property sheet a day sheet: square at the top where it is bound, the lightest round below.
 * @property plate the reversed ink-blue plate.
 * @property notice fix-it cards and error notices.
 * @property bar a chart's bar: square, as printed.
 */
@Immutable
data class NormShapeSet(
    val sheet: Shape = RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp),
    val plate: Shape = RoundedCornerShape(6.dp),
    val notice: Shape = RoundedCornerShape(6.dp),
    val bar: Shape = RectangleShape,
)
