package com.norm2hacked.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.norm2hacked.ble.BleManager
import com.norm2hacked.data.db.dao.NotificationRuleDao
import com.norm2hacked.data.db.entities.NotificationRuleEntity
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.NotificationPushCommand
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "NotifForwarder"

/**
 * Intercepts phone notifications and forwards them to the watch.
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
        fun notificationRuleDao(): NotificationRuleDao
        fun recentNotificationCache(): RecentNotificationCache
    }

    private lateinit var bleManager: BleManager
    private lateinit var ruleDao: NotificationRuleDao
    private lateinit var cache: RecentNotificationCache

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NotificationForwarder started")
        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationForwarderEntryPoint::class.java,
        )
        bleManager = ep.bleManager()
        ruleDao = ep.notificationRuleDao()
        cache = ep.recentNotificationCache()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        if (!bleManager.connectionState.value.isConnected) {
            Log.d(TAG, "Not connected — dropping notification from pkg=$pkg")
            return
        }

        val extras = sbn.notification?.extras ?: return

        scope.launch {
            // App display name — used as the new-rule default and as a title fallback. Resolved at
            // most once and lazily, so a disabled app (which returns early below) does no lookup.
            val appLabel by lazy(LazyThreadSafetyMode.NONE) { resolveAppLabel(pkg) }

            val rule = ruleDao.queryByPackage(pkg) ?: run {
                val newRule = NotificationRuleEntity(pkg, appLabel)
                ruleDao.upsert(newRule)
                Log.i(TAG, "New app seen: pkg=$pkg label='$appLabel' — auto-added with defaults (enabled=true, muteGroupChats=true)")
                newRule
            }

            if (!rule.enabled) {
                Log.d(TAG, "pkg=$pkg: rule.enabled=false — dropping")
                return@launch
            }

            val titleRaw = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val title = titleRaw.ifBlank { appLabel }
            val groupKey = sbn.groupKey ?: sbn.key
            val messageType = NotificationPushCommand.socialTypeForPackage(pkg)
            // Stable positive id from the notification key so an EDIT/repeat updates in place.
            val id = (sbn.key ?: groupKey).hashCode() and 0x7FFFFFFF

            val vibrate = if (rule.muteGroupChats) cache.shouldVibrate(groupKey)
                          else rule.vibrateOnFirst

            Log.i(TAG, "Forward: pkg=$pkg type=$messageType vibrate=$vibrate title='${title.take(30)}' text='${text.take(40)}'")

            if (vibrate) {
                // Routed through the write queue (urgent) so it's serialised + MTU-chunked — a real
                // title+body exceeds one MTU and a raw writeToChar would truncate it.
                bleManager.sendCommandNoResponse(
                    CommandCode.SOCIAL_NEW_PUSH, Action.SET,
                    NotificationPushCommand.appNotificationPayload(messageType, id, title, text),
                    urgent = true,
                )
            } else {
                Log.d(TAG, "  silent push (dedup window active or vibrateOnFirst=false)")
                bleManager.sendCommandNoResponse(
                    CommandCode.MSG_COUNT_PUSH, Action.SET,
                    NotificationPushCommand.msgCountPayload(1),
                    urgent = true,
                )
            }
        }
    }

    /** Human-readable app name for [pkg], falling back to the package name if it can't be resolved. */
    private fun resolveAppLabel(pkg: String): String = runCatching {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onNotificationRemoved(sbn: StatusBarNotification) { /* watch clears independently */ }

    override fun onDestroy() {
        Log.i(TAG, "NotificationForwarder destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
