package de.sofahub.app.integration.miracast

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

enum class MiracastCapability { SUPPORTED, UNSUPPORTED, UNKNOWN }

sealed interface MiracastLaunch {
    /** Systemansicht wurde geöffnet. Ob ein Sender sich verbindet, kann die App nicht feststellen. */
    data object Started : MiracastLaunch
    data object Unavailable : MiracastLaunch
    data object PermissionDenied : MiracastLaunch
    data class Error(val code: String) : MiracastLaunch
}

interface MiracastController {
    fun capability(): MiracastCapability
    fun launch(): MiracastLaunch
    /** Ausweichweg: Fire-TV-Einstellungen "Display & Sounds" (enthält "Display-Spiegelung aktivieren"). */
    fun openFallbackSettings(): Boolean
    /** Name, unter dem der Fire TV beim Spiegeln erscheint (Gerätename), sofern lesbar. */
    fun deviceName(): String?
}

/**
 * Startet den vorhandenen Fire-TV-Spiegelungsempfang (kein eigener Miracast-Stack).
 *
 * Kandidat (gemessen am Stick AFTKRT, Fire OS 8.1.6.0, 31.10.2026, siehe docs/COMPATIBILITY.md):
 * com.amazon.cast.sink/.DisplayMirroringSinkActivity – exportiert, ohne Berechtigung. Das ist eine interne Amazon-Activity:
 * Ein Fire-OS-Update kann sie umbenennen oder schützen. Deshalb wird vor jedem Start geprüft, ob sie existiert und
 * exportiert ist; jeder Fehler endet im Ausweichweg statt in einem Absturz.
 */
class FireTvMiracastController(private val context: Context) : MiracastController {
    private val pm = context.packageManager

    private fun sinkIntent() = Intent().setComponent(SINK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun capability(): MiracastCapability {
        val info = try {
            pm.getActivityInfo(SINK, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return MiracastCapability.UNSUPPORTED
        } catch (_: Exception) {
            return MiracastCapability.UNKNOWN
        }
        return when {
            !info.exported || !info.enabled -> MiracastCapability.UNSUPPORTED
            info.permission != null -> MiracastCapability.UNSUPPORTED
            else -> MiracastCapability.SUPPORTED
        }
    }

    override fun launch(): MiracastLaunch {
        if (capability() == MiracastCapability.UNSUPPORTED) return MiracastLaunch.Unavailable
        return try {
            context.startActivity(sinkIntent())
            MiracastLaunch.Started
        } catch (_: ActivityNotFoundException) {
            MiracastLaunch.Unavailable
        } catch (_: SecurityException) {
            MiracastLaunch.PermissionDenied
        } catch (e: RuntimeException) {
            MiracastLaunch.Error(e.javaClass.simpleName)
        }
    }

    override fun openFallbackSettings(): Boolean = listOf(
        Intent(Settings.ACTION_DISPLAY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    ).any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun deviceName(): String? =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        val SINK = ComponentName("com.amazon.cast.sink", "com.amazon.cast.sink.DisplayMirroringSinkActivity")
    }
}
