package com.normplus.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InstalledApps"

/** One entry in the app picker. [icon] is filled in by [InstalledAppsRepository.iconStream]. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Enumerates installed apps for the notification whitelist picker.
 *
 * **Package visibility (API 30+):** we deliberately do NOT hold `QUERY_ALL_PACKAGES` — it is a
 * Play-policy-restricted permission and unnecessary here. Instead `AndroidManifest.xml` declares a
 * `<queries><intent>` for `ACTION_MAIN` + `CATEGORY_LAUNCHER`, which makes every *launchable* app
 * visible — exactly the set a user recognises and would pick in a notification list. Apps with no
 * launcher entry stay invisible; the only ones that matter are those that already have a rule, and
 * those are re-added explicitly by [loadApps] (falling back to the bare package name if the OS
 * won't resolve them).
 *
 * Everything here touches [PackageManager], which is slow and must never run on the main thread —
 * both entry points are `withContext(Dispatchers.IO)` / `flowOn(Dispatchers.IO)`.
 *
 * It returns Compose [ImageBitmap]s rather than `Drawable`s on purpose: decoding + rasterising the
 * (often adaptive) icon once, off the main thread, at the exact size the row draws is what keeps a
 * 200+ item list scrolling smoothly. Drawables would re-rasterise during scroll.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * All launchable apps, plus [alwaysInclude] (packages that already have a rule, so the user can
     * always find and turn them off). Our own package is excluded. Sorted by label.
     *
     * Never throws — a PackageManager failure degrades to whatever was collected.
     */
    suspend fun loadApps(alwaysInclude: Set<String> = emptySet()): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName
            val byPackage = LinkedHashMap<String, InstalledApp>()

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching { pm.queryIntentActivities(launcherIntent, 0) }
                .onFailure { Log.w(TAG, "queryIntentActivities failed", it) }
                .getOrDefault(emptyList())
                .forEach { resolved ->
                    val info = resolved.activityInfo?.applicationInfo ?: return@forEach
                    if (info.packageName == self) return@forEach
                    byPackage.getOrPut(info.packageName) { info.toInstalledApp(pm) }
                }

            alwaysInclude.forEach { pkg ->
                if (pkg == self || byPackage.containsKey(pkg)) return@forEach
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                // Not resolvable (uninstalled, or invisible under package-visibility rules): still
                // list it under its package name so an old rule can never become unreachable.
                byPackage[pkg] = info?.toInstalledApp(pm)
                    ?: InstalledApp(packageName = pkg, label = pkg, isSystem = false)
            }

            byPackage.values.sortedBy { it.label.lowercase() }
        }

    /**
     * Rasterises icons for [packages] in the background, emitting a **cumulative** map every
     * [chunk] apps so the list fills in progressively instead of blocking on all 200+.
     *
     * Icons that fail to load are simply absent from the map (the row falls back to a placeholder).
     */
    fun iconStream(
        packages: List<String>,
        sizePx: Int,
        chunk: Int = ICON_CHUNK,
    ): Flow<Map<String, ImageBitmap>> = flow {
        val pm = context.packageManager
        val acc = HashMap<String, ImageBitmap>(packages.size)
        packages.chunked(chunk).forEach { batch ->
            batch.forEach { pkg ->
                runCatching { pm.getApplicationIcon(pkg).toBitmap(sizePx, sizePx).asImageBitmap() }
                    .onFailure { Log.d(TAG, "icon load failed for $pkg: ${it.message}") }
                    .getOrNull()
                    ?.let { acc[pkg] = it }
            }
            emit(HashMap(acc))
        }
    }.flowOn(Dispatchers.IO)

    /** Icon raster size in px for a [dp]-sized row slot, capped so 200+ bitmaps stay affordable. */
    fun iconSizePx(dp: Int = ICON_DP): Int =
        (dp * context.resources.displayMetrics.density).toInt().coerceIn(MIN_ICON_PX, MAX_ICON_PX)

    private companion object {
        const val ICON_CHUNK = 32
        const val ICON_DP = 40
        const val MIN_ICON_PX = 48
        const val MAX_ICON_PX = 128
    }
}

private fun ApplicationInfo.toInstalledApp(pm: PackageManager) = InstalledApp(
    packageName = packageName,
    label = runCatching { pm.getApplicationLabel(this).toString() }
        .getOrDefault(packageName)
        .ifBlank { packageName },
    // FLAG_UPDATED_SYSTEM_APP = a system app the user has updated from the store (Chrome, Gmail…).
    // Those behave like normal user apps, so don't hide them behind the "show system apps" toggle.
    isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
)
