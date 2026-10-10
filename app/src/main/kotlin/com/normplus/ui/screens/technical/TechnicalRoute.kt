package com.normplus.ui.screens.technical

import android.net.Uri
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
 * Technical (#107), at the bottom of Watch: the details ordinary users need not see. It draws
 * `ScreenScaffold`: the large title with Back, and the status pill or the banner under it.
 *
 * @param onBack back to Watch.
 * @param onUpdateFromFile a `.bin` was chosen for the custom-file update: the shell opens the
 *   firmware flow (#106) with it, so the update goes through the same deliberate steps.
 *
 * Stub (#97): a placeholder.
 */
@Composable
fun TechnicalRoute(onBack: () -> Unit, onUpdateFromFile: (Uri) -> Unit) {
    val spacing = NormPlusTheme.spacing
    ScreenScaffold(title = stringResource(R.string.technical_title), onBack = onBack) { padding ->
        Text(
            stringResource(R.string.technical_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = spacing.gutter, vertical = spacing.xl),
        )
    }
}
