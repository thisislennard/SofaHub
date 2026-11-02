package de.sofahub.app.integration.updates

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface InstallEvent {
    /** Systemdialog zur Bestätigung wurde geöffnet. */
    data object ConfirmationShown : InstallEvent
    data object Succeeded : InstallEvent
    data object Aborted : InstallEvent
    data class Failed(val status: Int, val message: String?) : InstallEvent
}

sealed interface InstallStart {
    data object Launched : InstallStart
    data object NeedsPermission : InstallStart
    data object FileChanged : InstallStart
    data class Error(val code: String) : InstallStart
}

/** Übergibt eine geprüfte APK an den Android-Systeminstaller (PackageInstaller-Session, immer mit Nutzerbestätigung). */
class InstallerAdapter(private val context: Context) {

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Öffnet die Freigabe "Apps unbekannter Herkunft" für Sofa Hub. Fire OS bietet diesen Dialog evtl. nicht an. */
    fun openPermissionSettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Schreibt die Datei in eine Session und prüft dabei erneut den SHA-256 der tatsächlich übergebenen Bytes:
     * wurde die Datei zwischen Prüfung und Installation ausgetauscht, wird die Session verworfen.
     */
    suspend fun install(file: File, expectedSha256: String): InstallStart = withContext(Dispatchers.IO) {
        if (!canInstall()) return@withContext InstallStart.NeedsPermission
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return@withContext InstallStart.Error("SESSION_CREATE_${e.javaClass.simpleName}")
        }
        val session = installer.openSession(sessionId)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                session.fsync(out)
            }
            if (digest.digest().toHex() != expectedSha256) {
                session.abandon()
                return@withContext InstallStart.FileChanged
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
            )
            session.commit(pending.intentSender)
            InstallStart.Launched
        } catch (e: Exception) {
            runCatching { session.abandon() }
            InstallStart.Error("SESSION_${e.javaClass.simpleName}")
        } finally {
            session.close()
        }
    }

    companion object {
        private val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
        val installEvents: SharedFlow<InstallEvent> = events.asSharedFlow()
        internal fun emit(event: InstallEvent) = events.tryEmit(event)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        InstallerAdapter.emit(InstallEvent.ConfirmationShown)
                    } catch (_: Exception) {
                        InstallerAdapter.emit(InstallEvent.Failed(status, "CONFIRM_NOT_STARTED"))
                    }
                } else {
                    InstallerAdapter.emit(InstallEvent.Failed(status, "NO_CONFIRM_INTENT"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallerAdapter.emit(InstallEvent.Succeeded)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallerAdapter.emit(InstallEvent.Aborted)
            else -> InstallerAdapter.emit(InstallEvent.Failed(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(120)))
        }
    }
}
