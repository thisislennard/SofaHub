package de.sofahub.app.integration.photos

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import de.sofahub.app.core.model.PhotoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Bilder für Diashow und Hintergrund: per Systemdialog gewählte URIs oder vom Handy hochgeladene Dateien im App-Speicher.
 * Laden mit Sampling auf Zielgröße, EXIF-Ausrichtung und begrenztem Speicher-Cache; Fehler pro Bild statt Absturz.
 */
class PhotoStore(private val context: Context) {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Gibt es einen Dateiauswahl-Dialog für Bilder? Viele Fire-TV-Firmwares liefern keinen mit. */
    fun pickerAvailable(): Boolean = listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT).any { action ->
        Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").resolveActivity(context.packageManager) != null
    }

    /** Dauerhafte Leserechte, falls der Anbieter sie gewährt; sonst bleibt der Zugriff nur bis zum Neustart. */
    fun persist(uri: Uri): PhotoRef {
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        return PhotoRef(uri = uri.toString(), persisted = persisted)
    }

    /** Speichert ein hochgeladenes Bild, wenn es sich als Bild dekodieren lässt. */
    suspend fun saveUpload(bytes: ByteArray): PhotoRef? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val name = UUID.randomUUID().toString() + ".img"
        File(dir, name).writeBytes(bytes)
        PhotoRef(localFile = name)
    }

    fun delete(ref: PhotoRef) {
        ref.localFile?.let { safeFile(it)?.delete() }
        ref.uri?.let { u -> runCatching { context.contentResolver.releasePersistableUriPermission(u.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        cache.evictAll()
    }

    suspend fun load(ref: PhotoRef, maxWidth: Int, maxHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${ref.localFile ?: ref.uri}@${maxWidth}x$maxHeight"
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching { decode(ref, maxWidth, maxHeight) }.getOrNull() ?: return@withContext null
        cache.put(key, bitmap)
        bitmap
    }

    private fun safeFile(name: String): File? {
        if (name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
        return File(dir, name)
    }

    private fun open(ref: PhotoRef): InputStream? = when {
        ref.localFile != null -> safeFile(ref.localFile)?.takeIf { it.exists() }?.inputStream()
        ref.uri != null -> context.contentResolver.openInputStream(ref.uri.toUri())
        else -> null
    }

    private fun decode(ref: PhotoRef, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Mit inJustDecodeBounds liefert decodeStream immer null; ausgewertet werden nur die Maße in bounds.
        val stream = open(ref) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val orientation = open(ref)?.use { runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull() }
            ?: ExifInterface.ORIENTATION_NORMAL
        val rotated = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        val (w, h) = if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        var sample = 1
        while (w / (sample * 2) >= maxWidth || h / (sample * 2) >= maxHeight) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = open(ref)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        // Sampling geht nur in Zweierschritten: ein 3000×2000-Foto bliebe sonst voll (24 MB). Auf die kleinste Größe
        // verkleinern, die den Zielbereich noch ganz füllt (Diashow/Hintergrund schneiden zu, nichts wird unscharf).
        val (dw, dh) = if (rotated) raw.height to raw.width else raw.width to raw.height
        val fit = maxOf(maxWidth.toFloat() / dw, maxHeight.toFloat() / dh)
        if (fit < 0.95f) matrix.postScale(fit, fit)
        if (matrix.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
    }
}
