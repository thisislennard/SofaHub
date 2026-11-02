package de.sofahub.app.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface TokenResult {
    data class Present(val token: String) : TokenResult
    data object Missing : TokenResult
    /** Datei vorhanden, aber nicht entschlüsselbar (Keystore-Schlüssel verloren): erneute Authentifizierung nötig. */
    data object Unreadable : TokenResult
}

interface SecretStore {
    fun saveToken(token: String)
    fun readToken(): TokenResult
    fun clearToken()
}

/**
 * HA-Token mit AES-256-GCM, Schlüssel im Android Keystore (nicht exportierbar). Die verschlüsselte Datei liegt in
 * noBackupFilesDir; Sicherung ist zusätzlich im Manifest abgeschaltet. Der Token erscheint nie in Logs oder Exporten.
 */
class KeystoreSecretStore(
    context: Context,
    fileName: String = "ha_token.bin",
    private val alias: String = "sofahub_ha_token",
) : SecretStore {
    private val file = File(context.noBackupFilesDir, fileName)
    @Volatile private var cached: String? = null

    override fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Token konnte nicht gespeichert werden" }
        }
        cached = token
    }

    override fun readToken(): TokenResult {
        cached?.let { return TokenResult.Present(it) }
        if (!file.exists()) return TokenResult.Missing
        return try {
            val bytes = file.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return TokenResult.Unreadable, GCMParameterSpec(128, iv))
            val token = cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen).toString(Charsets.UTF_8)
            cached = token
            TokenResult.Present(token)
        } catch (_: Exception) {
            TokenResult.Unreadable
        }
    }

    override fun clearToken() {
        cached = null
        file.delete()
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Maskiert Geheimnisse für die Anzeige: nur die letzten 4 Zeichen bleiben sichtbar. */
fun maskSecret(secret: String): String =
    if (secret.length <= 4) "••••" else "•".repeat(16) + secret.takeLast(4)
