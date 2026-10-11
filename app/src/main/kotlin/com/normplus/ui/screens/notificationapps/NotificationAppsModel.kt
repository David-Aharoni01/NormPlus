package com.normplus.ui.screens.notificationapps

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.normplus.data.apps.InstalledApp
import com.normplus.data.db.entities.NotificationRuleEntity

/**
 * One app in the list: the installed app plus its (possibly absent) whitelist rule.
 *
 * [suppressDuplicates] is stored in the rule's legacy `muteGroupChats` column (see
 * `NotificationWhitelist.toWhitelistRule`); [vibrateOnFirst] is carried through unchanged so a
 * save never rewrites it.
 */
@Immutable
data class AppRow(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val enabled: Boolean,
    val vibrateOnFirst: Boolean,
    val suppressDuplicates: Boolean,
)

/** What went wrong, worded by the screen. Neither stops it: the list stays usable. */
sealed interface AppsProblem {
    /** The installed-app list could not be read: the list is empty, with Retry. */
    data object LoadFailed : AppsProblem

    /** A switch could not be saved: the switch shows what is stored; a snackbar says so. */
    data class SaveFailed(val label: String) : AppsProblem
}

@Immutable
data class NotificationAppsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val showSystemApps: Boolean = false,
    /** Every enabled rule, installed or not: the apps whose notifications may reach the watch. */
    val forwardingCount: Int = 0,
    val apps: List<AppRow> = emptyList(),
    val loadFailed: Boolean = false,
    val saveFailed: AppsProblem.SaveFailed? = null,
)

/**
 * The list as the picker has always shown it (moved unchanged from `NotificationRulesViewModel`):
 *
 * - system apps are hidden unless [showSystem], but one already whitelisted never is, so it
 *   stays reachable to be turned off;
 * - the [query] (trimmed) matches the label or the package name, ignoring case;
 * - whitelisted apps first, then by label.
 */
fun appRows(
    apps: List<InstalledApp>,
    icons: Map<String, ImageBitmap>,
    rules: List<NotificationRuleEntity>,
    query: String,
    showSystem: Boolean,
): List<AppRow> {
    val rulesByPackage = rules.associateBy { it.packageName }
    val needle = query.trim()
    return apps.asSequence()
        .map { app -> app to rulesByPackage[app.packageName] }
        .filter { (app, rule) -> showSystem || !app.isSystem || rule?.enabled == true }
        .filter { (app, _) ->
            needle.isEmpty() ||
                app.label.contains(needle, ignoreCase = true) ||
                app.packageName.contains(needle, ignoreCase = true)
        }
        .map { (app, rule) ->
            AppRow(
                packageName = app.packageName,
                label = app.label,
                icon = icons[app.packageName],
                enabled = rule?.enabled == true,
                vibrateOnFirst = rule?.vibrateOnFirst ?: true,
                suppressDuplicates = rule?.muteGroupChats ?: true,
            )
        }
        .sortedWith(compareByDescending<AppRow> { it.enabled }.thenBy { it.label.lowercase() })
        .toList()
}

/** The rule a row saves as (the first opt-in creates it). */
fun AppRow.toRule() = NotificationRuleEntity(
    packageName = packageName,
    appLabel = label,
    enabled = enabled,
    vibrateOnFirst = vibrateOnFirst,
    muteGroupChats = suppressDuplicates,
)
