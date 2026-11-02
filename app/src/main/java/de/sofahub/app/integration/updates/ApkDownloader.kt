package de.sofahub.app.integration.updates

import de.sofahub.app.core.network.BlockedRequestException
import de.sofahub.app.integration.homeassistant.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface DownloadResult {
    data class Done(val file: File) : DownloadResult
    data object Network : DownloadResult
    data object Blocked : DownloadResult
    data object SizeMismatch : DownloadResult
    data object HashMismatch : DownloadResult
    data object NoSpace : DownloadResult
    data class Http(val code: Int) : DownloadResult
}

/**
 * Streamt die APK in den appinternen Cache (nicht global lesbar), zählt Bytes mit Obergrenze und berechnet SHA-256
 * beim Schreiben. Unvollständige Dateien werden immer gelöscht; der Hash gilt nur über die vollständige Datei.
 */
class ApkDownloader(private val client: OkHttpClient, private val dir: File, private val userAgent: String) {

    suspend fun download(
        url: String,
        headers: Map<String, String> = emptyMap(),
        expectedSize: Long,
        expectedSha256: String,
        targetName: String,
        onProgress: (Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (dir.usableSpace < expectedSize * 2) return@withContext DownloadResult.NoSpace
        val part = File(dir, "$targetName.part")
        val target = File(dir, targetName)
        part.delete()
        target.delete()
        try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/octet-stream")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return@withContext DownloadResult.Http(response.code)
                val body = response.body
                if (body.contentLength() > expectedSize) return@withContext DownloadResult.SizeMismatch
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > expectedSize) return@withContext DownloadResult.SizeMismatch
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            onProgress(total)
                        }
                        out.fd.sync()
                    }
                }
                if (total != expectedSize) return@withContext DownloadResult.SizeMismatch
                if (digest.digest().toHex() != expectedSha256) return@withContext DownloadResult.HashMismatch
                if (!part.renameTo(target)) return@withContext DownloadResult.Network
                DownloadResult.Done(target)
            }
        } catch (_: BlockedRequestException) {
            DownloadResult.Blocked
        } catch (_: IOException) {
            DownloadResult.Network
        } finally {
            part.delete()
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().toHex()
}
