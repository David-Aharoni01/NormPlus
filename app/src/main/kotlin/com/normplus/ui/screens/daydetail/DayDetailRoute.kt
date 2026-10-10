package com.normplus.ui.screens.daydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.normplus.R
import com.normplus.ui.components.dateLineText
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.theme.NormPlusTheme
import java.time.LocalDate

/**
 * Day detail (#101): a past day's hero card and tiles, below a tab. It draws `ScreenScaffold`: the large title with Back,
 * and the status pill or the banner under it. Moving to the previous or
 * next day happens inside the screen, with the shared axis between days.
 *
 * @param date the day to open first.
 * @param onBack back to the tab it was opened from.
 *
 * Stub (#97): a placeholder.
 */
@Composable
fun DayDetailRoute(date: LocalDate, onBack: () -> Unit) {
    val spacing = NormPlusTheme.spacing
    ScreenScaffold(title = stringResource(R.string.daydetail_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = spacing.gutter, vertical = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(dateLineText(date), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.daydetail_placeholder), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
