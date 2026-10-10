package com.normplus.ui.screens.notificationapps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.R
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.screens.settings.NotificationRulesScreen

/**
 * Notification apps (#104), below Watch (and opened from the first run). It draws `ScreenScaffold`: the large
 * title with Back, and the status pill or the banner under it.
 *
 * @param onBack back to where it was opened from.
 *
 * Stub (#97): the old notification rules screen.
 */
@Composable
fun NotificationAppsRoute(onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.notificationapps_title), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            NotificationRulesScreen(viewModel = hiltViewModel(), onBack = onBack)
        }
    }
}
