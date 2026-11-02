package de.sofahub.app.integration.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Inhalt von update.json eines Releases. Wird aus der gebauten APK erzeugt (scripts/make-update-manifest.py). */
@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkAssetName: String,
    val apkSizeBytes: Long,
    val sha256: String,
)

/** [apiUrl]: API-Adresse des Assets (für private Repositories mit Token), [downloadUrl]: öffentlicher Browser-Download. */
data class ReleaseAsset(val name: String, val size: Long, val downloadUrl: String, val apiUrl: String? = null)

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** Ein geprüftes, installierbares Angebot. */
data class UpdateOffer(
    val manifest: UpdateManifest,
    val apk: ReleaseAsset,
    val notes: String,
    val publishedAt: String,
)

sealed interface ManifestProblem {
    data object Unparsable : ManifestProblem
    data object WrongSchema : ManifestProblem
    data object WrongApplication : ManifestProblem
    data object TagMismatch : ManifestProblem
    data object BadAssetName : ManifestProblem
    data object AssetMissing : ManifestProblem
    data object SizeMismatch : ManifestProblem
    data object BadHash : ManifestProblem
    data object BadVersion : ManifestProblem
    data class MinSdkTooHigh(val required: Int) : ManifestProblem
}

object ManifestValidator {
    const val SCHEMA = 1
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 16L * 1024
    private val ASSET_NAME = Regex("^SofaHub-v[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?-universal\\.apk$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): UpdateManifest? = runCatching { json.decodeFromString(UpdateManifest.serializer(), text) }.getOrNull()

    /**
     * Prüft das Manifest strikt gegen das Release, zu dem es gehört. Die APK-Adresse kommt ausschließlich aus den Assets
     * dieses Releases, nie aus der JSON.
     */
    fun validate(manifest: UpdateManifest, release: ReleaseInfo, applicationId: String, deviceSdk: Int): Result<UpdateOffer> {
        fun fail(p: ManifestProblem) = Result.failure<UpdateOffer>(ManifestException(p))
        if (manifest.schemaVersion != SCHEMA) return fail(ManifestProblem.WrongSchema)
        if (manifest.applicationId != applicationId) return fail(ManifestProblem.WrongApplication)
        if (manifest.versionCode <= 0 || manifest.versionName.isBlank() || manifest.versionName.length > 32) return fail(ManifestProblem.BadVersion)
        if (release.tag != "v${manifest.versionName}") return fail(ManifestProblem.TagMismatch)
        if (!ASSET_NAME.matches(manifest.apkAssetName) || manifest.apkAssetName != "SofaHub-v${manifest.versionName}-universal.apk") {
            return fail(ManifestProblem.BadAssetName)
        }
        if (!SHA256.matches(manifest.sha256)) return fail(ManifestProblem.BadHash)
        if (manifest.apkSizeBytes !in 1..MAX_APK_BYTES) return fail(ManifestProblem.SizeMismatch)
        val apk = release.assets.singleOrNull { it.name == manifest.apkAssetName } ?: return fail(ManifestProblem.AssetMissing)
        if (apk.size != manifest.apkSizeBytes) return fail(ManifestProblem.SizeMismatch)
        if (manifest.minSdk > deviceSdk) return fail(ManifestProblem.MinSdkTooHigh(manifest.minSdk))
        return Result.success(UpdateOffer(manifest, apk, release.body, release.publishedAt))
    }
}

class ManifestException(val problem: ManifestProblem) : Exception(problem.toString())
