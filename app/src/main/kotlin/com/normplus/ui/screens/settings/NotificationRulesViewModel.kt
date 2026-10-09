package com.normplus.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.data.apps.InstalledApp
import com.normplus.data.apps.InstalledAppsRepository
import com.normplus.data.db.dao.NotificationRuleDao
import com.normplus.data.db.entities.NotificationRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "NotifRulesVM"

/** One row of the app picker: the app plus its (possibly absent) whitelist rule. */
data class AppRuleRow(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val enabled: Boolean,
    val vibrateOnFirst: Boolean,
    val muteGroupChats: Boolean,
)

data class NotificationRulesUiState(
    val loading: Boolean = true,
    val listenerEnabled: Boolean = false,
    val query: String = "",
    val showSystemApps: Boolean = false,
    val enabledCount: Int = 0,
    val apps: List<AppRuleRow> = emptyList(),
    val error: String? = null,
)

/**
 * Drives the notification-whitelist picker.
 *
 * All PackageManager work (labels, then icons) happens here on a background dispatcher and lands in
 * a [StateFlow] — never in composition — so the list scrolls smoothly with 200+ apps. Labels are
 * published first and icons stream in afterwards in batches, so the list is usable immediately.
 */
@HiltViewModel
class NotificationRulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleDao: NotificationRuleDao,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)

    private val queryText = MutableStateFlow("")
    private val showSystemApps = MutableStateFlow(false)
    private val listenerEnabled = MutableStateFlow(isListenerEnabled())

    private data class Filters(val query: String, val showSystem: Boolean)

    val uiState: StateFlow<NotificationRulesUiState> = combine(
        combine(installedApps, icons) { apps, iconMap -> apps to iconMap },
        ruleDao.queryAll(),
        combine(queryText, showSystemApps) { q, s -> Filters(q, s) },
        combine(loading, error) { isLoading, err -> isLoading to err },
        listenerEnabled,
    ) { (apps, iconMap), rules, filters, (isLoading, err), listener ->
        val rulesByPackage = rules.associateBy { it.packageName }
        val needle = filters.query.trim()

        val rows = apps.asSequence()
            .map { app -> app to rulesByPackage[app.packageName] }
            // System apps are hidden by default (they're noise), but never hide one the user has
            // already whitelisted — it must stay reachable to be turned back off.
            .filter { (app, rule) -> filters.showSystem || !app.isSystem || rule?.enabled == true }
            .filter { (app, _) ->
                needle.isEmpty() ||
                    app.label.contains(needle, ignoreCase = true) ||
                    app.packageName.contains(needle, ignoreCase = true)
            }
            .map { (app, rule) ->
                AppRuleRow(
                    packageName = app.packageName,
                    label = app.label,
                    icon = iconMap[app.packageName],
                    enabled = rule?.enabled == true,
                    vibrateOnFirst = rule?.vibrateOnFirst ?: true,
                    muteGroupChats = rule?.muteGroupChats ?: true,
                )
            }
            // Whitelisted apps pinned to the top, then alphabetical (the picker's stable order).
            .sortedWith(compareByDescending<AppRuleRow> { it.enabled }.thenBy { it.label.lowercase() })
            .toList()

        NotificationRulesUiState(
            loading = isLoading,
            listenerEnabled = listener,
            query = filters.query,
            showSystemApps = filters.showSystem,
            enabledCount = rules.count { it.enabled },
            apps = rows,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationRulesUiState())

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            // Failures here must not kill the screen — surface them as state and show an empty list.
            val apps = runCatching {
                val known = ruleDao.queryAll().first().map { it.packageName }.toSet()
                appsRepository.loadApps(alwaysInclude = known)
            }.onFailure {
                Log.e(TAG, "failed to enumerate installed apps", it)
                error.value = "Couldn't read the installed-app list: ${it.message ?: it::class.simpleName}"
            }.getOrDefault(emptyList())

            installedApps.value = apps
            loading.value = false

            runCatching {
                val sizePx = appsRepository.iconSizePx()
                appsRepository.iconStream(apps.map { it.packageName }, sizePx)
                    .collect { icons.value = it }
            }.onFailure { Log.w(TAG, "icon streaming failed — showing placeholders", it) }
        }
    }

    fun setQuery(value: String) { queryText.value = value }

    fun setShowSystemApps(value: Boolean) { showSystemApps.value = value }

    /** Whitelist / un-whitelist an app. Creates the rule row on first opt-in. */
    fun setEnabled(row: AppRuleRow, enabled: Boolean) = upsert(row.copy(enabled = enabled))


    fun setMuteGroupChats(row: AppRuleRow, mute: Boolean) = upsert(row.copy(muteGroupChats = mute))

    private fun upsert(row: AppRuleRow) {
        viewModelScope.launch {
            runCatching {
                ruleDao.upsert(
                    NotificationRuleEntity(
                        packageName = row.packageName,
                        appLabel = row.label,
                        enabled = row.enabled,
                        vibrateOnFirst = row.vibrateOnFirst,
                        muteGroupChats = row.muteGroupChats,
                    )
                )
            }.onFailure {
                Log.e(TAG, "failed to save rule for ${row.packageName}", it)
                error.value = "Couldn't save the rule for ${row.label}"
            }
        }
    }

    fun clearError() { error.value = null }

    /** Re-read the notification-listener grant — call from the screen's ON_RESUME. */
    fun refreshListenerState() { listenerEnabled.value = isListenerEnabled() }

    private fun isListenerEnabled(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)
}
