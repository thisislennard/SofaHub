package de.sofahub.app.integration.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data object Unparsable : VerifyResult
    data object WrongPackage : VerifyResult
    data object WrongVersion : VerifyResult
    data object NotNewer : VerifyResult
    data object MinSdkTooHigh : VerifyResult
    data object Unsigned : VerifyResult
    data object SignatureMismatch : VerifyResult
}

/** Ergebnis des Archiv-Parsings, getrennt vom Vergleich, damit die Regeln ohne Android testbar sind. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val signerSha256: Set<String>,
)

object VerifyRules {
    fun check(
        apk: ApkFacts?,
        expectedPackage: String,
        expectedVersionCode: Long,
        installedVersionCode: Long,
        installedSignerSha256: Set<String>,
        deviceSdk: Int,
    ): VerifyResult = when {
        apk == null -> VerifyResult.Unparsable
        apk.packageName != expectedPackage -> VerifyResult.WrongPackage
        apk.versionCode != expectedVersionCode -> VerifyResult.WrongVersion
        apk.versionCode <= installedVersionCode -> VerifyResult.NotNewer
        apk.minSdk > deviceSdk -> VerifyResult.MinSdkTooHigh
        apk.signerSha256.isEmpty() -> VerifyResult.Unsigned
        installedSignerSha256.isEmpty() || apk.signerSha256 != installedSignerSha256 -> VerifyResult.SignatureMismatch
        else -> VerifyResult.Ok
    }
}

/** Liest Paketname, Version, minSdk und Signaturzertifikate aus der heruntergeladenen APK und der installierten App. */
class ApkVerifier(private val context: Context) {
    private val pm = context.packageManager

    fun factsOf(file: File): ApkFacts? {
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        return ApkFacts(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            minSdk = info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
            signerSha256 = signers(info),
        )
    }

    fun installedSigners(): Set<String> {
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return signers(info)
    }

    fun installedVersionCode(): Long = pm.getPackageInfo(context.packageName, 0).longVersionCode

    fun verify(file: File, expectedPackage: String, expectedVersionCode: Long): VerifyResult = VerifyRules.check(
        apk = runCatching { factsOf(file) }.getOrNull(),
        expectedPackage = expectedPackage,
        expectedVersionCode = expectedVersionCode,
        installedVersionCode = installedVersionCode(),
        installedSignerSha256 = installedSigners(),
        deviceSdk = Build.VERSION.SDK_INT,
    )

    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        // Für Version 1 keine Schlüsselrotation: nur der aktuelle Signer zählt; mehrere Signer werden als Menge verglichen.
        val sigs: Array<Signature> = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory.takeLast(1).toTypedArray()
        return sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }
}
