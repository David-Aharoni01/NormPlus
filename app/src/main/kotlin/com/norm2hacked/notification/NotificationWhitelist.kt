package com.norm2hacked.notification

import android.util.Log
import com.norm2hacked.data.db.dao.NotificationRuleDao
import com.norm2hacked.data.db.entities.NotificationRuleEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NotifWhitelist"

/**
 * The per-app gate in front of notification forwarding.
 *
 * Semantics are a strict **whitelist**: a notification reaches the watch only if the user has
 * explicitly picked its app in the notification-apps screen. An app that has never been
 * configured has no row in `notification_rules` at all, and an app the user turned off has
 * `enabled = 0` — both are dropped. (Before v4 of the DB this was an opt-*out* list: the
 * forwarder auto-inserted `enabled = 1` for every app it ever saw. See `MIGRATION_3_4`.)
 *
 * The whitelist is *necessary but not sufficient*: [NotificationFilter]'s junk-type rules and the
 * 30s dedup in [RecentNotificationCache] still apply on top, in the forwarder.
 *
 * [NotificationWhitelistPolicy.decide] is pure (no Android, no DB) so it is unit-testable on the
 * plain JVM; [NotificationWhitelist] is the thin injectable wrapper that reads the DB.
 */
object NotificationWhitelistPolicy {

    /**
     * @param rule the stored rule for the package, or `null` when the app has never been
     *   configured — which is the common case and must default to *not forwarded*.
     */
    fun decide(rule: WhitelistRule?): WhitelistDecision = when {
        rule == null -> WhitelistDecision.NotWhitelisted
        !rule.enabled -> WhitelistDecision.NotWhitelisted
        else -> WhitelistDecision.Allowed(suppressDuplicates = rule.suppressDuplicates)
    }
}

/** Android/Room-free view of a stored per-app rule, so the policy stays pure. */
data class WhitelistRule(
    val enabled: Boolean,
    val suppressDuplicates: Boolean,
)

sealed interface WhitelistDecision {
    /** No row, or the user turned the app off — drop it. */
    data object NotWhitelisted : WhitelistDecision

    /**
     * The user picked this app; carries the per-app delivery options.
     *
     * @param suppressDuplicates apply [RecentNotificationCache]'s repeat suppression to this app —
     *   a re-post with identical `(mergeKey, title, content)` is not sent again until the user
     *   dismisses it on the phone. Stored in the legacy `muteGroupChats` column.
     */
    data class Allowed(val suppressDuplicates: Boolean) : WhitelistDecision
}

fun NotificationRuleEntity.toWhitelistRule() = WhitelistRule(
    enabled = enabled,
    // `muteGroupChats` is the historical column name; the behaviour it gates is now the general
    // duplicate-suppression cache, not a 30s group-chat window. `vibrateOnFirst` is deliberately
    // not mapped — see NotificationRuleEntity.
    suppressDuplicates = muteGroupChats,
)

@Singleton
class NotificationWhitelist @Inject constructor(
    private val ruleDao: NotificationRuleDao,
) {
    /**
     * Looks the package up and applies [NotificationWhitelistPolicy].
     *
     * Fails **closed**: a DB error is logged and treated as "not whitelisted" rather than thrown,
     * so a Room hiccup can never kill the forwarder's coroutine scope or leak a notification the
     * user didn't opt into.
     */
    suspend fun decide(pkg: String): WhitelistDecision {
        val rule = runCatching { ruleDao.queryByPackage(pkg) }
            .onFailure { Log.w(TAG, "rule lookup failed for pkg=$pkg — treating as not whitelisted", it) }
            .getOrNull()
        return NotificationWhitelistPolicy.decide(rule?.toWhitelistRule())
    }
}
