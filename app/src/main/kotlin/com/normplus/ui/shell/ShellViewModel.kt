package com.normplus.ui.shell

import androidx.lifecycle.ViewModel
import com.normplus.status.WatchStatus
import com.normplus.status.WatchStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The shell's window on [WatchStatusSource]: the status, the Sync action, and coming to the front. */
@HiltViewModel
class ShellViewModel @Inject constructor(private val source: WatchStatusSource) : ViewModel() {
    val status: StateFlow<WatchStatus> = source.status

    /** The Sync action at the end of Today's title. */
    fun sync() {
        source.sync()
    }

    /** The app came to the front (or back from a settings page): sample the system again. */
    fun onAppVisible() = source.onAppVisible()
}
