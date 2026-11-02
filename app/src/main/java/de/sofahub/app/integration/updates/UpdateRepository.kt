package de.sofahub.app.integration.updates

import android.os.Build
import de.sofahub.app.BuildConfig
import de.sofahub.app.core.model.PendingUpdate
import de.sofahub.app.core.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class SavedOffer(
    val manifest: UpdateManifest,
    val apkName: String,
    val apkSize: Long,
    val apkUrl: String,
    val notes: String,
    val publishedAt: String,
    val fileName: String,
)

private val savedJson = Json { ignoreUnknownKeys = true }

enum class UpdateError {
    NETWORK, RATE_LIMITED, NO_RELEASE, TOKEN_INVALID, INVALID_METADATA, MIN_SDK, ASSET_MISSING, BLOCKED,
    DOWNLOAD_FAILED, NO_SPACE, SIZE_MISMATCH, HASH_MISMATCH,
    WRONG_PACKAGE, SIGNATURE_MISMATCH, NOT_NEWER, INSTALL_FAILED, INSTALL_ABORTED, DEBUG_BUILD,
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedEpochMs: Long) : UpdateState
    data class Available(val offer: UpdateOffer) : UpdateState
    data class Downloading(val offer: UpdateOffer, val bytes: Long) : UpdateState
    data class Verifying(val offer: UpdateOffer) : UpdateState
    data class AwaitingPermission(val offer: UpdateOffer, val file: File) : UpdateState
    data class ReadyToInstall(val offer: UpdateOffer, val file: File) : UpdateState
    /** Installer gestartet – Erfolg erst nach Neustart an der installierten versionCode erkennbar. */
    data class InstallerLaunched(val offer: UpdateOffer) : UpdateState
    data class Installed(val versionName: String) : UpdateState
    data class Error(val error: UpdateError, val offer: UpdateOffer? = null, val detail: String? = null) : UpdateState
}

/**
 * Ablauf: Idle -> Checking -> UpToDate/Available/Error -> Downloading -> Verifying -> AwaitingPermission/ReadyToInstall
 * -> InstallerLaunched. Nichts wird ohne Nutzeraktion geladen oder installiert.
 */
class UpdateRepository(
    private val settings: SettingsRepository,
    private val github: GitHubReleaseClient,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: InstallerAdapter,
    private val scope: CoroutineScope,
    /** Geprüftes Angebot über einen Prozess-Neustart retten (Fire OS beendet die App beim Erteilen der Installationsfreigabe). */
    private val savedOfferFile: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = state.asStateFlow()
    private var job: Job? = null

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val AUTO_CHECK_POLL_MS = 60 * 60 * 1000L
    }

    private val isReleaseIdentity = BuildConfig.APPLICATION_ID == BuildConfig.RELEASE_APPLICATION_ID

    init {
        scope.launch {
            InstallerAdapter.installEvents.collect { event ->
                val current = state.value
                val offer = (current as? UpdateState.InstallerLaunched)?.offer ?: (current as? UpdateState.ReadyToInstall)?.offer
                when (event) {
                    InstallEvent.ConfirmationShown -> Unit
                    InstallEvent.Succeeded -> Unit // App wird ersetzt; Bestätigung folgt beim nächsten Start.
                    InstallEvent.Aborted -> {
                        settings.update { it.copy(pendingUpdate = null) }
                        state.value = UpdateState.Error(UpdateError.INSTALL_ABORTED, offer)
                    }
                    is InstallEvent.Failed -> {
                        settings.update { it.copy(pendingUpdate = null) }
                        state.value = UpdateState.Error(UpdateError.INSTALL_FAILED, offer, "STATUS_${event.status}")
                    }
                }
            }
        }
    }

    /** Beim Start: Ergebnis eines angestoßenen Updates feststellen, alte Dateien bereinigen, ggf. täglich prüfen. */
    fun onAppStart() {
        scope.launch {
            val s = settings.current()
            val pending = s.pendingUpdate
            if (pending != null) {
                val installed = runCatching { verifier.installedVersionCode() }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
                if (installed >= pending.expectedVersionCode) state.value = UpdateState.Installed(pending.versionName)
                settings.update { it.copy(pendingUpdate = null) }
                downloader.clear()
            } else if (state.value is UpdateState.Idle && !restoreSavedOffer()) {
                downloader.clear()
            }
            if (checkDue()) check()
        }
        // Sofa Hub läuft im Kiosk-Betrieb tagelang durch: stündlich nachsehen, ob die tägliche Prüfung fällig ist.
        scope.launch {
            while (true) {
                delay(AUTO_CHECK_POLL_MS)
                val idle = state.value.let { it is UpdateState.Idle || it is UpdateState.UpToDate || it is UpdateState.Error }
                if (idle && checkDue()) check()
            }
        }
    }

    private suspend fun checkDue(): Boolean {
        val s = settings.current()
        return s.autoUpdateCheck && isReleaseIdentity && now() - s.lastUpdateCheckEpochMs > AUTO_CHECK_INTERVAL_MS
    }

    fun check() {
        if (state.value is UpdateState.Checking || state.value is UpdateState.Downloading) return
        if (!isReleaseIdentity) {
            state.value = UpdateState.Error(UpdateError.DEBUG_BUILD)
            return
        }
        job = scope.launch {
            state.value = UpdateState.Checking
            state.value = evaluate()
            settings.update { it.copy(lastUpdateCheckEpochMs = now()) }
        }
    }

    private suspend fun evaluate(): UpdateState {
        val release = when (val r = github.latest()) {
            is ReleaseFetch.Found -> r.release
            ReleaseFetch.NoRelease -> return UpdateState.Error(UpdateError.NO_RELEASE)
            ReleaseFetch.Unauthorized -> return UpdateState.Error(UpdateError.TOKEN_INVALID)
            is ReleaseFetch.RateLimited -> return UpdateState.Error(UpdateError.RATE_LIMITED)
            ReleaseFetch.Network -> return UpdateState.Error(UpdateError.NETWORK)
            ReleaseFetch.Blocked -> return UpdateState.Error(UpdateError.BLOCKED)
            is ReleaseFetch.Http, ReleaseFetch.Invalid -> return UpdateState.Error(UpdateError.INVALID_METADATA)
        }
        val manifestAsset = release.assets.singleOrNull { it.name == "update.json" } ?: return UpdateState.Error(UpdateError.ASSET_MISSING)
        if (manifestAsset.size > ManifestValidator.MAX_MANIFEST_BYTES) return UpdateState.Error(UpdateError.INVALID_METADATA)
        val text = github.downloadText(github.assetRequest(manifestAsset), ManifestValidator.MAX_MANIFEST_BYTES) ?: return UpdateState.Error(UpdateError.NETWORK)
        val manifest = ManifestValidator.parse(text) ?: return UpdateState.Error(UpdateError.INVALID_METADATA)
        val offer = ManifestValidator.validate(manifest, release, BuildConfig.RELEASE_APPLICATION_ID, Build.VERSION.SDK_INT)
            .getOrElse { e ->
                return when ((e as? ManifestException)?.problem) {
                    is ManifestProblem.MinSdkTooHigh -> UpdateState.Error(UpdateError.MIN_SDK)
                    ManifestProblem.AssetMissing -> UpdateState.Error(UpdateError.ASSET_MISSING)
                    else -> UpdateState.Error(UpdateError.INVALID_METADATA)
                }
            }
        val installed = runCatching { verifier.installedVersionCode() }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
        return if (offer.manifest.versionCode > installed) UpdateState.Available(offer) else UpdateState.UpToDate(now())
    }

    fun download() {
        val offer = when (val s = state.value) {
            is UpdateState.Available -> s.offer
            is UpdateState.Error -> s.offer ?: return
            else -> return
        }
        job?.cancel()
        job = scope.launch {
            state.value = UpdateState.Downloading(offer, 0)
            val target = github.assetRequest(offer.apk)
            val result = downloader.download(
                url = target.url,
                headers = target.headers,
                expectedSize = offer.manifest.apkSizeBytes,
                expectedSha256 = offer.manifest.sha256,
                targetName = offer.manifest.apkAssetName,
            ) { bytes -> state.value = UpdateState.Downloading(offer, bytes) }
            val file = when (result) {
                is DownloadResult.Done -> result.file
                DownloadResult.Blocked -> return@launch fail(UpdateError.BLOCKED, offer)
                DownloadResult.HashMismatch -> return@launch fail(UpdateError.HASH_MISMATCH, offer)
                DownloadResult.SizeMismatch -> return@launch fail(UpdateError.SIZE_MISMATCH, offer)
                DownloadResult.NoSpace -> return@launch fail(UpdateError.NO_SPACE, offer)
                DownloadResult.Network, is DownloadResult.Http -> return@launch fail(UpdateError.DOWNLOAD_FAILED, offer)
            }
            state.value = UpdateState.Verifying(offer)
            when (verifier.verify(file, BuildConfig.RELEASE_APPLICATION_ID, offer.manifest.versionCode)) {
                VerifyResult.Ok -> Unit
                VerifyResult.SignatureMismatch, VerifyResult.Unsigned -> return@launch failAndDelete(UpdateError.SIGNATURE_MISMATCH, offer, file)
                VerifyResult.WrongPackage, VerifyResult.Unparsable -> return@launch failAndDelete(UpdateError.WRONG_PACKAGE, offer, file)
                VerifyResult.WrongVersion, VerifyResult.NotNewer -> return@launch failAndDelete(UpdateError.NOT_NEWER, offer, file)
                VerifyResult.MinSdkTooHigh -> return@launch failAndDelete(UpdateError.MIN_SDK, offer, file)
            }
            readyOrAwaiting(offer, file)
        }
    }

    private fun readyOrAwaiting(offer: UpdateOffer, file: File) {
        saveOffer(offer, file)
        state.value = if (installer.canInstall()) UpdateState.ReadyToInstall(offer, file) else UpdateState.AwaitingPermission(offer, file)
    }

    private fun saveOffer(offer: UpdateOffer, file: File) {
        val target = savedOfferFile ?: return
        runCatching {
            target.writeText(savedJson.encodeToString(SavedOffer.serializer(), SavedOffer(offer.manifest, offer.apk.name, offer.apk.size, offer.apk.downloadUrl, offer.notes, offer.publishedAt, file.name)))
        }
    }

    /** Stellt ein zuvor geprüftes Angebot wieder her, wenn Datei, Prüfsumme, Version und Signatur weiterhin passen. */
    private fun restoreSavedOffer(): Boolean {
        val source = savedOfferFile ?: return false
        if (!source.exists()) return false
        val saved = runCatching { savedJson.decodeFromString(SavedOffer.serializer(), source.readText()) }.getOrNull()
        val file = saved?.let { File(source.parentFile, it.fileName) }
        val ok = saved != null && file != null && file.exists() && !isUnsafeName(saved.fileName) &&
            file.sha256() == saved.manifest.sha256 &&
            verifier.verify(file, saved.manifest.applicationId.takeIf { it == BuildConfig.APPLICATION_ID } ?: BuildConfig.RELEASE_APPLICATION_ID, saved.manifest.versionCode) == VerifyResult.Ok
        if (!ok) {
            source.delete()
            return false
        }
        val offer = UpdateOffer(saved.manifest, ReleaseAsset(saved.apkName, saved.apkSize, saved.apkUrl), saved.notes, saved.publishedAt)
        readyOrAwaiting(offer, file)
        return true
    }

    private fun isUnsafeName(name: String) = name.contains('/') || name.contains('\\') || name.startsWith(".")

    private fun fail(error: UpdateError, offer: UpdateOffer) {
        state.value = UpdateState.Error(error, offer)
    }

    private fun failAndDelete(error: UpdateError, offer: UpdateOffer, file: File) {
        file.delete()
        state.value = UpdateState.Error(error, offer)
    }

    fun cancelDownload() {
        val s = state.value as? UpdateState.Downloading ?: return
        savedOfferFile?.delete()
        job?.cancel()
        downloader.clear()
        state.value = UpdateState.Available(s.offer)
    }

    fun openPermissionSettings(): Boolean = installer.openPermissionSettings()

    /** Nach Rückkehr aus den Einstellungen: Freigabe erneut prüfen. */
    fun recheckPermission() {
        val s = state.value as? UpdateState.AwaitingPermission ?: return
        if (installer.canInstall()) state.value = UpdateState.ReadyToInstall(s.offer, s.file)
    }

    fun install() {
        val (offer, file) = when (val s = state.value) {
            is UpdateState.ReadyToInstall -> s.offer to s.file
            is UpdateState.AwaitingPermission -> s.offer to s.file
            else -> return
        }
        job = scope.launch {
            // Zustand vor dem Ersetzen der App sichern: nach dem Neustart wird die installierte Version verglichen.
            settings.update { it.copy(pendingUpdate = PendingUpdate(offer.manifest.versionCode, offer.manifest.versionName, now())) }
            when (val r = installer.install(file, offer.manifest.sha256)) {
                InstallStart.Launched -> state.value = UpdateState.InstallerLaunched(offer)
                InstallStart.NeedsPermission -> {
                    settings.update { it.copy(pendingUpdate = null) }
                    state.value = UpdateState.AwaitingPermission(offer, file)
                }
                InstallStart.FileChanged -> {
                    settings.update { it.copy(pendingUpdate = null) }
                    failAndDelete(UpdateError.HASH_MISMATCH, offer, file)
                }
                is InstallStart.Error -> {
                    settings.update { it.copy(pendingUpdate = null) }
                    state.value = UpdateState.Error(UpdateError.INSTALL_FAILED, offer, r.code)
                }
            }
        }
    }

    /**
     * Nur Debug-Builds: Hardware-Test des Installationswegs ohne GitHub. Erwartet eine per ADB in den
     * App-Speicher gelegte APK derselben App mit höherer versionCode und gleicher Signatur; Prüfung und Installation laufen
     * über dieselben Klassen wie beim echten Update.
     */
    fun installLocalTestApk(file: File) {
        if (!BuildConfig.DEBUG || !file.exists()) return
        job = scope.launch {
            val facts = verifier.factsOf(file)
            val sha = file.sha256()
            val manifest = UpdateManifest(1, BuildConfig.APPLICATION_ID, "test-${facts?.versionCode}", facts?.versionCode ?: 0, facts?.minSdk ?: 0, file.name, file.length(), sha)
            val offer = UpdateOffer(manifest, ReleaseAsset(file.name, file.length(), "file://local"), "Lokaler Installer-Test", "")
            when (verifier.verify(file, BuildConfig.APPLICATION_ID, manifest.versionCode)) {
                VerifyResult.Ok -> readyOrAwaiting(offer, file)
                else -> state.value = UpdateState.Error(UpdateError.SIGNATURE_MISMATCH, null, "LOCAL_TEST_VERIFY")
            }
        }
    }

    /** "Erneut versuchen": nach Abbruch/Ablehnung die bereits geprüfte Datei erneut anbieten, sonst neu laden/prüfen. */
    fun retry() {
        val s = state.value as? UpdateState.Error ?: return
        when {
            s.error in setOf(UpdateError.INSTALL_ABORTED, UpdateError.INSTALL_FAILED) && restoreSavedOffer() -> Unit
            s.offer != null && s.error in setOf(UpdateError.DOWNLOAD_FAILED, UpdateError.NO_SPACE, UpdateError.SIZE_MISMATCH, UpdateError.HASH_MISMATCH, UpdateError.INSTALL_ABORTED, UpdateError.INSTALL_FAILED) -> download()
            else -> check()
        }
    }

    fun dismiss() {
        when (val s = state.value) {
            is UpdateState.Installed, is UpdateState.Error -> state.value = if (s is UpdateState.Error && s.offer != null) UpdateState.Available(s.offer) else UpdateState.Idle
            else -> Unit
        }
    }
}
