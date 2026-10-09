package com.normplus

import android.app.Application
import android.util.Log
import com.normplus.protocol.Logger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class Norm2Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // Route protocol-layer logging through android.util.Log so Packet deframer
        // warnings appear in logcat under the correct tags.
        Logger.instance = object : Logger {
            override fun i(tag: String, msg: String) { Log.i(tag, msg) }
            override fun w(tag: String, msg: String) { Log.w(tag, msg) }
            override fun e(tag: String, msg: String) { Log.e(tag, msg) }
        }
    }
}
// startForegroundService is NOT called here — doing so in Application.onCreate() throws
// ForegroundServiceStartNotAllowedException on Android 12+ when the app is launched in
// the background (e.g., by WorkManager or a broadcast). Service start is in MainActivity.
