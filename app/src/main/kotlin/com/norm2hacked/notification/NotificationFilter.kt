package com.norm2hacked.notification

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Drops notification *types* that are never useful on the watch, so we don't flood it with
 * garbage: phone charging status, media "now playing", foreground-service "Waiting for
 * messages…", download progress, group summaries, etc.
 *
 * This is informed by the original NORM companion app's filter chain
 * (`cn.appscomm.messagepush.filter.{ProgressFilter,EmptyContentFilter}`) and Gadgetbridge's
 * `NotificationListener` reference logic, but it deliberately goes further than the original
 * (which never filtered ongoing/foreground-service/media/local-only). Crucially the junk is a
 * *type*, not an app — WhatsApp's "Waiting for messages" is a foreground-service notification
 * from an app we *do* want forwarded — so the decision inspects flags/category/extras, not the
 * package whitelist.
 *
 * [decide] is pure (no Android types) so it is unit-testable on the plain JVM; [toFacts] is the
 * thin Android-side extractor that reads the [StatusBarNotification].
 */
object NotificationFilter {

    // Mirror android.app.Notification.CATEGORY_* string values so [decide] stays Android-free
    // (pure, unit-testable). These are stable platform contract constants (valid on API 30+).
    const val CATEGORY_CALL = "call"
    const val CATEGORY_MISSED_CALL = "missed_call"
    const val CATEGORY_MESSAGE = "msg"
    const val CATEGORY_EMAIL = "email"
    const val CATEGORY_EVENT = "event"
    const val CATEGORY_REMINDER = "reminder"
    const val CATEGORY_ALARM = "alarm"
    const val CATEGORY_TRANSPORT = "transport"
    const val CATEGORY_SERVICE = "service"
    const val CATEGORY_PROGRESS = "progress"
    const val CATEGORY_SYSTEM = "sys"

    /**
     * System sources whose notifications are pure status noise (charging, system UI). Deliberately
     * does NOT include the dialers: until first-class call support lands (see the forwarder TODO),
     * call/missed-call notifications ride the generic text path and must not be swallowed here.
     */
    private val SYSTEM_SOURCES = setOf(
        "android",
        "com.android.systemui",
    )

    /**
     * User-facing categories that are real events even when posted as ongoing / backed by a
     * foreground service (calls, chat messages, alarms, …). Exempted from the ongoing/FGS drop so
     * a messenger that holds a foreground service doesn't lose its actual messages.
     */
    private val KEEP_WHEN_ONGOING = setOf(
        CATEGORY_CALL,
        CATEGORY_MISSED_CALL,
        CATEGORY_MESSAGE,
        CATEGORY_EMAIL,
        CATEGORY_EVENT,
        CATEGORY_REMINDER,
        CATEGORY_ALARM,
    )

    /** Categories that are status/transport noise, never a message. (TRANSPORT is caught earlier as media.) */
    private val JUNK_CATEGORIES = setOf(
        CATEGORY_SERVICE,
        CATEGORY_PROGRESS,
        CATEGORY_SYSTEM,
    )

    /**
     * Apps that mark their notifications FLAG_LOCAL_ONLY but whose messages we still want on the
     * watch (they set it defensively, not to mean "don't bridge"). Mirrors Gadgetbridge.
     */
    private val LOCAL_ONLY_EXCEPTIONS = setOf(
        "com.tencent.mm",                // WeChat
        "org.telegram.messenger",        // Telegram
        "com.microsoft.office.outlook",
        "com.skype.raider",              // Skype
    )

    /** Pure decision over extracted facts. Drops on the first matching rule. */
    fun decide(f: NotificationFacts): FilterDecision {
        if (f.pkg in SYSTEM_SOURCES) return FilterDecision.Drop(DropReason.SYSTEM_SOURCE)

        // Ongoing / foreground-service notifications are status, not events — except real
        // user-facing categories (calls, messages, alarms, …), which are ongoing by nature.
        if ((f.isOngoing || f.isForegroundService) && f.category !in KEEP_WHEN_ONGOING) {
            return FilterDecision.Drop(
                if (f.isForegroundService) DropReason.FOREGROUND_SERVICE else DropReason.ONGOING,
            )
        }

        if (f.isMediaStyle || f.category == CATEGORY_TRANSPORT) return FilterDecision.Drop(DropReason.MEDIA)
        if (f.hasProgress) return FilterDecision.Drop(DropReason.PROGRESS)
        if (f.isGroupSummary) return FilterDecision.Drop(DropReason.GROUP_SUMMARY)
        if (f.isLocalOnly && f.pkg !in LOCAL_ONLY_EXCEPTIONS) return FilterDecision.Drop(DropReason.LOCAL_ONLY)
        if (f.category in JUNK_CATEGORIES) return FilterDecision.Drop(DropReason.JUNK_CATEGORY)
        if (f.titleBlank && f.textBlank) return FilterDecision.Drop(DropReason.EMPTY_CONTENT)

        return FilterDecision.Forward
    }

    /**
     * True if [template] (the `EXTRA_TEMPLATE` value) is any MediaStyle variant — covers both the
     * platform (`android.app.Notification$MediaStyle` / `$DecoratedMediaCustomViewStyle`) and the
     * AndroidX-compat (`androidx.media.app.NotificationCompat$Media…`) forms that real players use.
     */
    fun isMediaTemplate(template: String?): Boolean {
        if (template == null) return false
        // Match by style class name so both the platform (android.app.Notification$…) and the
        // AndroidX-compat (androidx.media.app.NotificationCompat$…) package prefixes are covered.
        return template.endsWith("MediaStyle") ||
            template.endsWith("DecoratedMediaCustomViewStyle")
    }
}

/** Android-free snapshot of the bits of a notification the filter cares about. */
data class NotificationFacts(
    val pkg: String,
    val category: String?,
    val isOngoing: Boolean,
    val isForegroundService: Boolean,
    val isGroupSummary: Boolean,
    val isLocalOnly: Boolean,
    val isMediaStyle: Boolean,
    val hasProgress: Boolean,
    val titleBlank: Boolean,
    val textBlank: Boolean,
)

enum class DropReason {
    SYSTEM_SOURCE,
    ONGOING,
    FOREGROUND_SERVICE,
    MEDIA,
    PROGRESS,
    GROUP_SUMMARY,
    LOCAL_ONLY,
    JUNK_CATEGORY,
    EMPTY_CONTENT,
}

sealed interface FilterDecision {
    data object Forward : FilterDecision
    data class Drop(val reason: DropReason) : FilterDecision
}

/** Extracts the [NotificationFacts] the filter needs from a posted [StatusBarNotification]. */
fun StatusBarNotification.toFacts(): NotificationFacts {
    val n = notification
    val extras = n.extras
    val flags = n.flags

    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    // Prefer the expanded body (BigTextStyle) — same source the forwarder sends — so the
    // empty-content check sees exactly what would be transmitted.
    val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString()

    // Faithful to the original BaseInfoParser: progress is determinate progress/max only.
    val hasProgress = extras != null && (
        extras.getInt(Notification.EXTRA_PROGRESS, 0) > 0 ||
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        )

    val isMediaStyle = extras != null && (
        extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            NotificationFilter.isMediaTemplate(extras.getString(Notification.EXTRA_TEMPLATE))
        )

    return NotificationFacts(
        pkg = packageName,
        category = n.category,
        // StatusBarNotification.isOngoing() is exactly (flags & FLAG_ONGOING_EVENT) != 0.
        isOngoing = isOngoing,
        isForegroundService = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
        isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        isLocalOnly = (flags and Notification.FLAG_LOCAL_ONLY) != 0,
        isMediaStyle = isMediaStyle,
        hasProgress = hasProgress,
        titleBlank = title.isNullOrBlank(),
        textBlank = text.isNullOrBlank(),
    )
}
