package com.norm2hacked.protocol

/**
 * Minimal logging interface so Packet (and other protocol classes) can emit
 * warnings without importing android.util.Log.
 *
 * The Android app installs an adapter in Norm2Application.
 * The CLI uses the default implementation (stderr).
 */
interface Logger {
    fun v(tag: String, msg: String) {}
    fun d(tag: String, msg: String) {}
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String)

    companion object {
        var instance: Logger = object : Logger {
            override fun i(tag: String, msg: String) = System.out.println("I/$tag: $msg")
            override fun w(tag: String, msg: String) = System.err.println("W/$tag: $msg")
            override fun e(tag: String, msg: String) = System.err.println("E/$tag: $msg")
        }
    }
}
