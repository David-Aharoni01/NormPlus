package com.norm2hacked.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.norm2hacked.R
import com.norm2hacked.data.preferences.WatchPreferences
import com.norm2hacked.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL_ID = "ble_service"
private const val NOTIF_ID = 1

@AndroidEntryPoint
class BleService : Service() {

    @Inject lateinit var bleManager: BleManager
    @Inject lateinit var watchPreferences: WatchPreferences

    private val serviceScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Searching for watch…"))
        observeConnectionState()
        connectIfKnownDevice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bleManager.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeConnectionState() {
        bleManager.connectionState.onEach { state ->
            val text = when (state) {
                is BleConnectionState.Ready -> "Connected to ${state.deviceName}"
                is BleConnectionState.Connecting -> "Connecting…"
                is BleConnectionState.Scanning -> "Scanning…"
                is BleConnectionState.Error -> "Reconnecting… (${state.retryCount}/3)"
                else -> "Disconnected"
            }
            updateNotification(text)
        }.launchIn(serviceScope)
    }

    private fun connectIfKnownDevice() {
        serviceScope.launch {
            val mac = watchPreferences.getDeviceMac() ?: return@launch
            bleManager.connect(mac)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watch Connection",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Norm 2")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
