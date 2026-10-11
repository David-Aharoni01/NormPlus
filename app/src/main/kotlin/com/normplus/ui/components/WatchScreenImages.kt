package com.normplus.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The watch's own screens, screenshotted from the emulated watch (#114): the vendor's screens,
 * kept in the private NORM/_screens and copied into the app's assets at build time
 * (app/build.gradle.kts). A build without them, and every screenshot test, has none, and the
 * dial draws Norm+'s own previews instead; so no vendor image ever reaches a tracked golden.
 *
 * `assets/watchscreens/pages.json` maps a page to its file: the AppSettingCommand page type in
 * hex ("0x01") or "face".
 */
@Immutable
class WatchScreenImages private constructor(
    private val context: Context?,
    private val files: Map<String, String>,
) {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** The screenshot of [screen], or null to draw it instead. The face is always drawn live. */
    fun imageFor(screen: WatchScreen): ImageBitmap? {
        if (screen == WatchScreen.Face) return null
        val type = screen.pageType ?: return null
        val file = files["0x%02X".format(type)] ?: return null
        cache[file]?.let { return it }
        val image = runCatching {
            context!!.assets.open("$DIR/$file").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.onFailure { Log.w(TAG, "$file: ${it.message}") }.getOrNull() ?: return null
        cache[file] = image
        return image
    }

    companion object {
        private const val TAG = "WatchScreenImages"
        private const val DIR = "watchscreens"

        /** No screenshots: the dial draws every screen. The default, and what tests get. */
        val None = WatchScreenImages(null, emptyMap())

        /** The screenshots this build carries, if any. */
        fun load(context: Context): WatchScreenImages = runCatching {
            val json = context.assets.open("$DIR/pages.json").bufferedReader().use { it.readText() }
            val o = JSONObject(json)
            val files = o.keys().asSequence().filter { it != "note" }.associateWith { o.getString(it) }
            WatchScreenImages(context.applicationContext, files)
        }.getOrElse { None }
    }
}

/** The watch's own screenshots, provided by the running app only (MainActivity). */
val LocalWatchScreenImages = staticCompositionLocalOf { WatchScreenImages.None }
