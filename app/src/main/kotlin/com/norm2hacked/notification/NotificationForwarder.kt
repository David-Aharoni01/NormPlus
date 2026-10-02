package com.norm2hacked.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.norm2hacked.ble.BleManager
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.MessageNewCommand
import com.norm2hacked.protocol.commands.NotificationPushCommand
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "NotifForwarder"

/**
 * Intercepts phone notifications and forwards them to the watch.
 *
 * Pipeline: junk-type filter ([NotificationFilter]) → per-app rule → a [NotificationMergePolicy]
 * coalescing window (~300 ms, last-write-wins per merge key, group summary vs. children) →
 * duplicate suppression ([RecentNotificationCache]) → `MessageNewBT` (cmd `0x76`).
 *
 * **There is no watch-side removal.** The `New` push generation carries no identity field, so a
 * notification cannot be updated or deleted once sent — see [NotificationMergePolicy]. All grouping
 * therefore happens here, *before* the write.
 *
 * @AndroidEntryPoint does NOT work with NotificationListenerService — the system instantiates
 * it directly without going through Hilt's entry-point wrappers, so @Inject fields would
 * remain null. We use EntryPointAccessors to perform manual injection in onCreate().
 */
class NotificationForwarder : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationForwarderEntryPoint {
        fun bleManager(): BleManager
        fun notificationWhitelist(): NotificationWhitelist
        fun recentNotificationCache(): RecentNotificationCache
    }

    private lateinit var bleManager: BleManager
    private lateinit var whitelist: NotificationWhitelist
    private lateinit var cache: RecentNotificationCache

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Posts accepted for forwarding, awaiting the end of the current coalescing window. */
    private val buffer = NotificationMergeBuffer()
    private var flushJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NotificationForwarder started")
        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationForwarderEntryPoint::class.java,
        )
        bleManager = ep.bleManager()
        whitelist = ep.notificationWhitelist()
        cache = ep.recentNotificationCache()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        // Never forward our own notifications (e.g. the BleService foreground notification) —
        // that's just noise on the watch and a self-feedback loop.
        if (pkg == packageName) return

        if (!bleManager.connectionState.value.isConnected) {
            Log.d(TAG, "Not connected — dropping notification from pkg=$pkg")
            return
        }

        val extras = sbn.notification?.extras ?: return

        // Drop junk *types* (charging/system status, media, foreground-service "Waiting for
        // messages…", progress, etc.) before touching the DB or BLE. Done synchronously since all
        // inputs are on the sbn. See NotificationFilter. Group summaries survive this — the
        // summary-vs-children call is made later, in NotificationMergePolicy.
        val facts = sbn.toFacts()
        when (val decision = NotificationFilter.decide(facts)) {
            is FilterDecision.Drop -> {
                Log.d(TAG, "pkg=$pkg dropped: ${decision.reason}")
                return
            }
            FilterDecision.Forward -> { /* fall through to the per-app rule + dedup path */ }
        }

        scope.launch {
            // App display name — used as the new-rule default and as a title fallback. Resolved at
            // most once and lazily, so a disabled app (which returns early below) does no lookup.
            val appLabel by lazy(LazyThreadSafetyMode.NONE) { resolveAppLabel(pkg) }

            // Whitelist gate: only apps the user explicitly picked forward. Unknown app = drop.
            val allow = whitelist.decide(pkg) as? WhitelistDecision.Allowed ?: run {
                Log.d(TAG, "pkg=$pkg: not whitelisted — dropping")
                return@launch
            }

            // getCharSequence (not getString) so a SpannableString title resolves instead of
            // falling back to the app label. EXTRA_BIG_TEXT first — messaging apps often put the
            // real message there and leave EXTRA_TEXT as a short summary (or empty).
            val titleRaw = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val title = titleRaw.ifBlank { appLabel }
            val messageType = NotificationPushCommand.socialTypeForPackage(pkg)

            // Don't log notification content (PII) — lengths only.
            Log.i(TAG, "Queued: pkg=$pkg type=$messageType summary=${facts.isGroupSummary} titleLen=${title.length} textLen=${text.length}")

            enqueue(
                PendingNotification(
                    mergeKey = sbn.mergeKey(),
                    // For an ungrouped post the platform synthesises a group-of-one key (== sbn.key),
                    // so it can never collide with another notification's group — no special case
                    // needed. Typed nullable only because the platform signature is.
                    groupKey = sbn.groupKey,
                    isGroupSummary = facts.isGroupSummary,
                    pkg = pkg,
                    messageType = messageType,
                    title = title,
                    content = text,
                    suppressDuplicates = allow.suppressDuplicates,
                )
            )
        }
    }

    /**
     * Adds [entry] to the current coalescing window, opening one if none is running.
     *
     * The window is anchored to the *first* post of a burst rather than restarted on each arrival —
     * a sliding window could be starved indefinitely by a steady drip of notifications.
     */
    private fun enqueue(entry: PendingNotification) {
        buffer.offer(entry)
        synchronized(this) {
            if (flushJob?.isActive == true) return
            flushJob = scope.launch {
                delay(NotificationMergePolicy.COALESCE_WINDOW_MS)
                flush()
            }
        }
    }

    /** Sends what survives the merge. Must never throw — it runs on the forwarder's own scope. */
    private suspend fun flush() {
        val merged = buffer.drainMerged()
        for (e in merged) {
            if (e.suppressDuplicates && !cache.shouldSend(SuppressionKey(e.mergeKey, e.title, e.content))) {
                // An exact repeat of text already on the watch. Re-pushing it would add a second
                // copy (the "New" generation cannot replace a notification), and the legacy
                // MSG_COUNT_PUSH (0x72) count-badge belongs to the older generation and is a dead
                // write here — so the only correct action is to send nothing.
                Log.d(TAG, "  suppressed (unchanged text): pkg=${e.pkg}")
                continue
            }
            // This firmware (Norm 2) is on the "New" push generation, so we send MessageNewBT
            // (cmd 0x76) — the legacy MessageBT (0x79) is acked-but-ignored here. Routed through
            // the write queue (urgent) so it's serialised + MTU-chunked — a real title+body
            // exceeds one MTU and a raw writeToChar would truncate it. sendCommandNoResponse
            // swallows link errors internally, so a flap can't kill this scope.
            bleManager.sendCommandNoResponse(
                CommandCode.SOCIAL_EX_PUSH, Action.SET,
                MessageNewCommand.appNotificationPayload(e.messageType, e.title, e.content),
                urgent = true,
            )
        }
        if (merged.isNotEmpty()) Log.i(TAG, "Flushed ${merged.size} notification(s) to the watch")
    }

    /** Human-readable app name for [pkg], falling back to the package name if it can't be resolved. */
    private fun resolveAppLabel(pkg: String): String = runCatching {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * A dismissal on the phone sends **nothing** to the watch — and that is provably correct, not a
     * gap. The `New` push generation (cmd `0x76` `MessageNewBT`) has no id/slot/crud field, so no
     * notification can be addressed after the fact; the original app's own
     * `MessagePushRepositoryHelper.deleteMessage` (smali :16-40) short-circuits to `return-void` for
     * every non-`Perfect` device, which includes ours. The watch clears its own list on its own
     * schedule and the phone never tells it anything.
     *
     * The one thing a removal *is* good for: dropping the entry from the suppression cache, so the
     * same text posted again later is treated as genuinely new rather than a repeat
     * (`OldProtocolFilter.smali:343-344`).
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { cache.evict(sbn.mergeKey()) }
            .onFailure { Log.w(TAG, "onNotificationRemoved: cache evict failed (${it.message})") }
    }

    // ── Bind state ────────────────────────────────────────────────────────────
    // Tracked so NotificationListenerHealth can tell "the system dropped us" from "we're fine".
    // onCreate/onDestroy are NOT enough: the system can unbind the listener while leaving the
    // process alive, and onListenerConnected is the only callback that says we're really live.

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "listener connected — notifications will be intercepted")
    }

    override fun onListenerDisconnected() {
        isConnected = false
        Log.w(TAG, "listener disconnected — notifications will NOT reach the watch until rebound")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        Log.i(TAG, "NotificationForwarder destroyed")
        isConnected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * Whether the system currently has the forwarder bound. Written from the listener
         * callbacks (main thread) and read from the watchdog's background tick, hence `@Volatile`.
         */
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
