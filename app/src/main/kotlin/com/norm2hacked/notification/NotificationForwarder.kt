package com.norm2hacked.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.norm2hacked.ble.BleConstants
import com.norm2hacked.ble.BleManager
import com.norm2hacked.data.db.dao.NotificationRuleDao
import com.norm2hacked.data.db.entities.NotificationRuleEntity
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
            val rule = ruleDao.queryByPackage(pkg) ?: run {
                val pm = applicationContext.packageManager
                val label = runCatching {
                    pm.getApplicationInfo(pkg, 0).let { pm.getApplicationLabel(it).toString() }
                }.getOrDefault(pkg)
                val newRule = NotificationRuleEntity(pkg, label)
                ruleDao.upsert(newRule)
                Log.i(TAG, "New app seen: pkg=$pkg label='$label' — auto-added with defaults (enabled=true, muteGroupChats=true)")
                newRule
            }

            if (!rule.enabled) {
                Log.d(TAG, "pkg=$pkg: rule.enabled=false — dropping")
                return@launch
            }

            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val content = "$title: $text".take(80)
            val groupKey = sbn.groupKey ?: sbn.key

            val vibrate = if (rule.muteGroupChats) cache.shouldVibrate(groupKey)
                          else rule.vibrateOnFirst

            Log.i(TAG, "Forward: pkg=$pkg vibrate=$vibrate muteGroupChats=${rule.muteGroupChats} content='${content.take(50)}'")

            if (vibrate) {
                bleManager.writeToChar(
                    NotificationPushCommand.buildSocialNewPush(buildPayload(pkg, content)),
                    BleConstants.CHAR_WRITE_8001,
                )
            } else {
                Log.d(TAG, "  silent push (dedup window active or vibrateOnFirst=false)")
                bleManager.writeToChar(
                    NotificationPushCommand.buildMsgCountPush(1),
                    BleConstants.CHAR_WRITE_8001,
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { /* watch clears independently */ }

    override fun onDestroy() {
        Log.i(TAG, "NotificationForwarder destroyed")
        scope.cancel()
        super.onDestroy()
    }

    // [pkgLen(1)][pkg bytes][content bytes] — flexible format for SocialNewPush (0x79)
    private fun buildPayload(pkg: String, content: String): ByteArray {
        val pkgBytes = pkg.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        val contentBytes = content.toByteArray(Charsets.UTF_8).take(80).toByteArray()
        return ByteArray(1 + pkgBytes.size + contentBytes.size).also { buf ->
            buf[0] = pkgBytes.size.toByte()
            pkgBytes.copyInto(buf, 1)
            contentBytes.copyInto(buf, 1 + pkgBytes.size)
        }
    }
}
