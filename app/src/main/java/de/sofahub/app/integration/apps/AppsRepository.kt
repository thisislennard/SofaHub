package de.sofahub.app.integration.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

sealed interface LaunchResult {
    data object Started : LaunchResult
    data object NotInstalled : LaunchResult
    data object Disabled : LaunchResult
    data object NoLaunchActivity : LaunchResult
    data object Failed : LaunchResult
}

/** Echte App-Grafik: TV-Banner (320×180, wie im Fire-TV-Startbildschirm) und/oder App-Icon. */
data class AppArt(val banner: android.graphics.Bitmap?, val icon: android.graphics.Bitmap?)

interface AppsRepository {
    val installed: StateFlow<List<InstalledApp>>
    suspend fun refresh()
    suspend fun art(packageName: String): AppArt?
    fun status(packageName: String): LaunchResult
    fun launch(packageName: String): LaunchResult
}

/**
 * Startbare TV-Apps über die im Manifest deklarierten Intent-Queries (LEANBACK_LAUNCHER, LAUNCHER) – ohne
 * QUERY_ALL_PACKAGES. Start bevorzugt den Leanback-Intent, sonst den normalen Launcher-Intent.
 */
class AndroidAppsRepository(private val context: Context) : AppsRepository {
    private val pm: PackageManager = context.packageManager
    private val state = MutableStateFlow<List<InstalledApp>>(emptyList())
    override val installed: StateFlow<List<InstalledApp>> = state.asStateFlow()

    override suspend fun refresh() {
        state.value = withContext(Dispatchers.IO) {
            val own = context.packageName
            val leanback = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            (leanback + launcher)
                .map { it.activityInfo.applicationInfo }
                .filter { it.packageName != own && it.enabled }
                .distinctBy { it.packageName }
                .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    // Nach Bytes begrenzt (nicht nach Anzahl): viele App-Banner sollen den Stick nicht knapp an Speicher bringen.
    private val artCache = object : android.util.LruCache<String, AppArt>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: AppArt) = (value.banner?.byteCount ?: 0) + (value.icon?.byteCount ?: 0)
    }

    override suspend fun art(packageName: String): AppArt? = withContext(Dispatchers.IO) {
        artCache.get(packageName)?.let { return@withContext it }
        val art = runCatching {
            val leanback = pm.getLeanbackLaunchIntentForPackage(packageName)
            val banner = (leanback?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(packageName))?.toBitmap(400, 225)
            val icon = (leanback?.let { pm.getActivityIcon(it) } ?: pm.getApplicationIcon(packageName)).toBitmap(128, 128)
            AppArt(banner, icon)
        }.getOrNull() ?: return@withContext null
        artCache.put(packageName, art)
        art
    }

    private fun android.graphics.drawable.Drawable.toBitmap(maxW: Int, maxH: Int): android.graphics.Bitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: maxW
        val h = intrinsicHeight.takeIf { it > 0 } ?: maxH
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h, 2f)
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        setBounds(0, 0, bw, bh)
        draw(canvas)
        return bmp
    }

    private fun launchIntent(packageName: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)

    override fun status(packageName: String): LaunchResult {
        val info = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return LaunchResult.NotInstalled
        }
        if (!info.enabled) return LaunchResult.Disabled
        return if (launchIntent(packageName) != null) LaunchResult.Started else LaunchResult.NoLaunchActivity
    }

    override fun launch(packageName: String): LaunchResult {
        val status = status(packageName)
        if (status != LaunchResult.Started) return status
        val intent = launchIntent(packageName) ?: return LaunchResult.NoLaunchActivity
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            LaunchResult.Started
        } catch (_: ActivityNotFoundException) {
            LaunchResult.NoLaunchActivity
        } catch (_: SecurityException) {
            LaunchResult.Failed
        }
    }
}
