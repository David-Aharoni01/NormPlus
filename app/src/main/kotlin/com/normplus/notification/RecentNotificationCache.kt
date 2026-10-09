package com.normplus.notification

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suppresses re-pushes of a notification whose text has not changed.
 *
 * Models the original app's `OldProtocolFilter` (smali `messagepush/filter/OldProtocolFilter.smali`),
 * which is installed for exactly our device class — `AppNotificationManager.initFilter()` builds it
 * whenever the push generation is *not* `Perfect`, i.e. on the Norm 2:
 *  - a FIFO cache of at most [MAX_ENTRIES] (`MOST_CACHE_SIZE = 0x64`) already-pushed notifications;
 *  - a post whose `(id, title, content)` is already cached is **dropped** (`isSameContent`,
 *    `OldProtocolFilter.smali:74-206`, :252-326) — indefinitely, not for a fixed window;
 *  - a *removal* evicts the matching entries (:343-344), so the same text posted again later is
 *    allowed through.
 *
 * That last point is the whole (and only) reason [com.normplus.notification.NotificationForwarder.onNotificationRemoved]
 * exists: there is no wire-level "delete this notification" for the `New` push generation, so a
 * dismissal on the phone can never reach the watch. What it *can* do is unblock a genuine repeat.
 *
 * Every method is `synchronized`: posts and removals arrive on the listener's binder threads while
 * the flush reads on the forwarder's coroutine scope. No Android types — unit-testable as-is.
 */
@Singleton
class RecentNotificationCache @Inject constructor() {

    // LinkedHashSet keeps insertion order, so evicting the head is FIFO. An already-present key is
    // NOT moved to the back (add() returns false and leaves the position alone) — same as the
    // original, which never refreshes a cached entry.
    private val seen = LinkedHashSet<SuppressionKey>()

    /**
     * True if [key] has not been pushed before (and records it); false if it is an exact repeat and
     * should be suppressed.
     */
    @Synchronized
    fun shouldSend(key: SuppressionKey): Boolean {
        if (!seen.add(key)) return false
        val overflow = seen.size - MAX_ENTRIES
        if (overflow > 0) {
            val oldest = seen.iterator()
            repeat(overflow) { oldest.next(); oldest.remove() }
        }
        return true
    }

    /**
     * Forgets every cached entry for [mergeKey] — call when the notification is dismissed or
     * replaced on the phone, so identical text can be forwarded again later.
     */
    @Synchronized
    fun evict(mergeKey: String) {
        seen.removeAll { it.id == mergeKey }
    }

    @Synchronized
    fun clear() = seen.clear()

    /** Current entry count. Exposed for tests and logging only. */
    @get:Synchronized
    val size: Int get() = seen.size

    companion object {
        /** `OldProtocolFilter.MOST_CACHE_SIZE = 0x64`. */
        const val MAX_ENTRIES = 100
    }
}

/**
 * `OldProtocolFilter.isSameContent`: same notification id **and** title **and** body.
 * [id] is the merge key (`pkg + sbn.id + tag`), see [mergeKey].
 */
data class SuppressionKey(val id: String, val title: String, val content: String)
