package com.normplus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Dark Dial is round: cards at 20-28 dp, and full pills on buttons, chips and the navigation
 * capsule. Material's own components take the scale below (cards medium, dialogs and sheets
 * extra large, chips small), so a stock component already belongs; buttons, switches and the
 * navigation indicator are pills in Material itself.
 */
private val Pill = RoundedCornerShape(percent = 50)

internal val NormShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),          // text fields, menus, snackbars
    small = Pill,                                   // chips
    medium = RoundedCornerShape(20.dp),             // cards
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),         // dialogs, bottom sheets
)

/**
 * Dark Dial's own shapes. Read as `NormPlusTheme.shapes`.
 *
 * @property hero the hero card (Today's steps): the roundest card.
 * @property card a card: settings sections, charts, notices.
 * @property tile a metric tile and a row card (Yesterday).
 * @property banner the full-width state banner.
 * @property pill a pill: buttons, the status pill, state marks, the navigation capsule.
 * @property barCorner the round at the top of a chart's bar.
 */
@Immutable
data class NormShapeSet(
    val hero: Shape = RoundedCornerShape(28.dp),
    val card: Shape = RoundedCornerShape(24.dp),
    val tile: Shape = RoundedCornerShape(20.dp),
    val banner: Shape = RoundedCornerShape(20.dp),
    val pill: Shape = Pill,
    val barCorner: Dp = 3.dp,
)
