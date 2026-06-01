package com.norm2hacked.notification

import com.norm2hacked.ble.BleConstants
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentNotificationCache @Inject constructor() {
    private val cache = ConcurrentHashMap<String, Long>()

    // Returns true and atomically records the timestamp if outside the dedup window.
    // Uses ConcurrentHashMap.compute so the read-check-write is a single atomic operation,
    // preventing two concurrent notifications for the same key from both triggering vibration.
    fun shouldVibrate(key: String): Boolean {
        val now = System.currentTimeMillis()
        var vibrate = false
        cache.compute(key) { _, last ->
            if (last == null || now - last > BleConstants.DEDUP_WINDOW_MS) {
                vibrate = true
                now
            } else last
        }
        // Opportunistically evict entries older than the dedup window so the map can't grow without
        // bound over a long-running foreground session (one entry accrues per distinct group key).
        if (cache.size > MAX_ENTRIES_BEFORE_PRUNE) {
            cache.entries.removeIf { now - it.value > BleConstants.DEDUP_WINDOW_MS }
        }
        return vibrate
    }

    private companion object {
        const val MAX_ENTRIES_BEFORE_PRUNE = 256
    }
}
