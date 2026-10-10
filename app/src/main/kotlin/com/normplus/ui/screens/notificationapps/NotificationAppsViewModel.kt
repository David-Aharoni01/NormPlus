package com.normplus.ui.screens.notificationapps

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.data.apps.InstalledApp
import com.normplus.data.apps.InstalledAppsRepository
import com.normplus.data.db.dao.NotificationRuleDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "NotificationAppsVM"

/**
 * Drives Notification apps (#104): which apps may send their notifications to the watch.
 * The whitelist itself (`NotificationWhitelist`) reads the same `notification_rules` rows this
 * writes; nothing here changes what it decides.
 *
 * All PackageManager work (labels, then icons) happens off the main thread in
 * [InstalledAppsRepository] and lands in a [StateFlow], never in composition, so 400 rows scroll
 * smoothly. Labels are published first and icons stream in afterwards in batches.
 *
 * Whether notification access is on is not read here: it is the shared status source's
 * `Blocker.NotificationAccessOff`, sampled again whenever the app comes back to the front.
 */
@HiltViewModel
class NotificationAppsViewModel @Inject constructor(
    private val ruleDao: NotificationRuleDao,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val loading = MutableStateFlow(true)
    private val loadFailed = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow<AppsProblem.SaveFailed?>(null)

    private val queryText = MutableStateFlow("")
    private val showSystemApps = MutableStateFlow(false)

    private val rules = ruleDao.queryAll().catch {
        Log.e(TAG, "reading the notification rules failed", it)
        loadFailed.value = true
        emit(emptyList())
    }

    private data class Filters(val query: String, val showSystem: Boolean)
    private data class Flags(val loading: Boolean, val loadFailed: Boolean, val saveFailed: AppsProblem.SaveFailed?)

    val uiState: StateFlow<NotificationAppsUiState> = combine(
        combine(installedApps, icons) { apps, iconMap -> apps to iconMap },
        rules,
        combine(queryText, showSystemApps) { q, s -> Filters(q, s) },
        combine(loading, loadFailed, saveFailed) { l, f, s -> Flags(l, f, s) },
    ) { (apps, iconMap), ruleList, filters, flags ->
        NotificationAppsUiState(
            loading = flags.loading,
            query = filters.query,
            showSystemApps = filters.showSystem,
            forwardingCount = ruleList.count { it.enabled },
            apps = appRows(apps, iconMap, ruleList, filters.query, filters.showSystem),
            loadFailed = flags.loadFailed,
            saveFailed = flags.saveFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationAppsUiState())

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Reads the installed apps (again, after a failure), then streams their icons. */
    fun load() {
        loadJob?.cancel()
        loading.value = true
        loadFailed.value = false
        loadJob = viewModelScope.launch {
            // Failures here must not kill the screen: surface them as state, with an empty list.
            val apps = runCatching {
                val known = ruleDao.queryAll().first().map { it.packageName }.toSet()
                appsRepository.loadApps(alwaysInclude = known)
            }.onFailure {
                Log.e(TAG, "failed to enumerate installed apps", it)
                loadFailed.value = true
            }.getOrDefault(emptyList())

            installedApps.value = apps
            loading.value = false

            runCatching {
                val sizePx = appsRepository.iconSizePx()
                appsRepository.iconStream(apps.map { it.packageName }, sizePx)
                    .collect { icons.value = it }
            }.onFailure { Log.w(TAG, "icon streaming failed: showing placeholders", it) }
        }
    }

    fun setQuery(value: String) { queryText.value = value }

    fun setShowSystemApps(value: Boolean) { showSystemApps.value = value }

    /** Whitelist or un-whitelist an app. Creates the rule row on the first opt-in. */
    fun setEnabled(row: AppRow, enabled: Boolean) = upsert(row.copy(enabled = enabled))

    fun setSuppressDuplicates(row: AppRow, suppress: Boolean) = upsert(row.copy(suppressDuplicates = suppress))

    private fun upsert(row: AppRow) {
        viewModelScope.launch {
            runCatching { ruleDao.upsert(row.toRule()) }
                .onFailure {
                    Log.e(TAG, "failed to save rule for ${row.packageName}", it)
                    saveFailed.value = AppsProblem.SaveFailed(row.label)
                }
        }
    }

    fun clearSaveFailed() { saveFailed.value = null }
}
