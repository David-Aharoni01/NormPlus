package com.normplus.ui.screens.watchscreens

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.normplus.R
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.theme.NormPlusTheme

/**
 * The order of the watch's own screens (#103), below Watch. It draws `ScreenScaffold` (the large
 * title with Back, and the status pill or the banner under it); a Done action goes in
 * its `actions`. The new order is sent once, on Done or on leaving.
 *
 * @param onBack back to Watch, after Done or system Back.
 *
 * Stub (#97): a placeholder.
 */
@Composable
fun WatchScreensRoute(onBack: () -> Unit) {
    val spacing = NormPlusTheme.spacing
    ScreenScaffold(title = stringResource(R.string.watchscreens_title), onBack = onBack) { padding ->
        Text(
            stringResource(R.string.watchscreens_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = spacing.gutter, vertical = spacing.xl),
        )
    }
}
