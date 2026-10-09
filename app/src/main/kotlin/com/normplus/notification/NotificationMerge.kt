package com.normplus.notification

import android.service.notification.StatusBarNotification

/**
 * Phone-side notification grouping/merging.
 *
 * **Why this exists at all.** The Norm 2 is on the `New` push generation (cmd `0x76`
 * `MessageNewBT`), whose body carries *no identity field* — no id, no slot, no crud byte. There is
 * therefore no wire-level way to update, replace, or delete a notification once it is on the watch:
 * `MessagePushRepositoryHelper.deleteMessage` (smali `messagepush/repository/helper/…:16-40`) is a
 * literal no-op unless the device is `Perfect`, which ours is not. The original companion app solves
 * grouping *entirely on the phone, before sending* — and so do we.
 *
 * Three mechanisms, mirroring the original:
 *  - **Debounce** ([COALESCE_WINDOW_MS], 300 ms — `MessagePushController.schedulePushTask` →
 *    `MessagePushHandler.sendMessageDelayed(…, 0x12c)`): posts that land in the same burst are
 *    merged instead of each becoming a separate wrist buzz.
 *  - **Last-write-wins per merge key** (`NotificationTaskMerger.removeMessageTask`): the key is
 *    `pkg + sbn.id + tag` (`NotificationParser.parseId`), i.e. five in-place updates of the same
 *    WhatsApp chat collapse to the newest one.
 *  - **Group summary vs children** (`NotificationTaskMerger.mergeGroupTaskAndReturnIfNeedAdd`,
 *    `else` branch — `BlueToothDevice.isSupportGroupNotification()` is a hardcoded `false`): the
 *    summary wins and the children are evicted. See [merge] for our one deliberate deviation.
 *
 * [PendingNotification] and [NotificationMergePolicy.merge] are Android-free and pure so they are
 * unit-testable on the plain JVM; [mergeKey] is the thin Android-side extractor.
 */
object NotificationMergePolicy {

    /**
     * Debounce between the first post of a burst and the BLE write.
     * `0x12c` = 300 ms in `MessagePushHandler.smali:80-97`.
     */
    const val COALESCE_WINDOW_MS = 300L

    /**
     * UX policy knob (see [merge]). `true` = when a group summary and exactly **one** child are in
     * the same window, forward the child (its real message text) rather than the summary (a count).
     * `false` = strict smali behaviour: the summary always wins.
     */
    const val PREFER_LONE_CHILD = true

    /**
     * Collapses one debounce window's worth of posts into the set actually worth sending.
     *
     * Order is preserved by *newest* occurrence (a re-post moves the entry to the back, exactly as
     * `NotificationTaskMerger` removes the older task and appends the new one).
     *
     * Group resolution, for each group that has a summary in the window:
     *  - **0 children** → emit the summary (smali baseline — it is all we have).
     *  - **1 child** → emit the child when [preferLoneChild], else the summary. *This is the one
     *    deliberate deviation from the smali.* A lone child's text ("Alice: on my way") is strictly
     *    more useful on a wrist than the summary that describes it ("1 new message"), and there is
     *    no flooding to prevent when only one child exists. It is a UX call, not a wire-protocol
     *    one, and it is a single flag away from the strict behaviour.
     *  - **2+ children** → emit only the summary (smali baseline). This is the case the user
     *    actually complained about: five chats buzzing five times becomes one "5 new messages".
     *
     * Only the newest summary of a group survives; ungrouped entries always pass through.
     */
    fun merge(
        window: List<PendingNotification>,
        preferLoneChild: Boolean = PREFER_LONE_CHILD,
    ): List<PendingNotification> {
        // Phase 1 — last-write-wins per merge key, newest keeps the newest position.
        val byKey = LinkedHashMap<String, PendingNotification>()
        for (e in window) {
            byKey.remove(e.mergeKey) // re-insert so the entry moves to the back
            byKey[e.mergeKey] = e
        }
        val deduped = byKey.values.toList()

        // Phase 2 — group summary vs children.
        val summaries = deduped.filter { it.isGroupSummary && it.groupKey != null }
        if (summaries.isEmpty()) return deduped

        val newestSummaryKey = summaries.associate { it.groupKey!! to it.mergeKey }
        val childCount = deduped
            .filter { !it.isGroupSummary && it.groupKey != null }
            .groupingBy { it.groupKey!! }
            .eachCount()

        return deduped.filter { e ->
            val group = e.groupKey ?: return@filter true
            val summaryKey = newestSummaryKey[group] ?: return@filter true // no summary → keep child
            val loneChildWins = preferLoneChild && childCount[group] == 1
            if (e.isGroupSummary) e.mergeKey == summaryKey && !loneChildWins else loneChildWins
        }
    }
}

/**
 * Android-free snapshot of a notification queued for the watch — everything the merge policy and
 * the BLE write need, and nothing else.
 *
 * @param mergeKey `pkg + sbn.id + tag` — the original's `NotificationParser.parseId`. Identity for
 *   last-write-wins and for the suppression cache; *not* anything the watch ever sees.
 * @param groupKey `sbn.groupKey`, or `null` when the post is not part of a group.
 * @param suppressDuplicates whether the per-app rule opts this app into duplicate suppression.
 */
data class PendingNotification(
    val mergeKey: String,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val pkg: String,
    val messageType: Byte,
    val title: String,
    val content: String,
    val suppressDuplicates: Boolean = true,
)

/**
 * Accumulates one debounce window's posts and merges them on drain.
 *
 * Posts arrive on arbitrary listener/coroutine threads while the flush runs on the forwarder's
 * scope, so every operation is `synchronized` — the buffer is the only mutable state shared between
 * the two.
 */
class NotificationMergeBuffer {
    private val window = ArrayList<PendingNotification>()

    @Synchronized
    fun offer(entry: PendingNotification) {
        window += entry
    }

    /** Empties the buffer and returns the merged result. Never throws. */
    @Synchronized
    fun drainMerged(preferLoneChild: Boolean = NotificationMergePolicy.PREFER_LONE_CHILD):
        List<PendingNotification> {
        if (window.isEmpty()) return emptyList()
        val merged = NotificationMergePolicy.merge(window.toList(), preferLoneChild)
        window.clear()
        return merged
    }

    @get:Synchronized
    val size: Int get() = window.size
}

/**
 * The original app's notification identity: `packageName + sbn.id`, plus the tag when it is
 * non-empty (`NotificationParser.parseId`, smali `…/parse/NotificationParser.smali:188-247`).
 *
 * Distinct from [StatusBarNotification.getGroupKey], which identifies the *group* an app bundles its
 * posts into — several chats share one group key, so it must not be used for last-write-wins.
 */
fun StatusBarNotification.mergeKey(): String = packageName + id + (tag ?: "")
