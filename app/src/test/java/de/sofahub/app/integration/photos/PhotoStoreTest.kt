package de.sofahub.app.integration.photos

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Echte Bilddekodierung (Robolectric Native Graphics): Upload speichern, verkleinert laden, Müll ablehnen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30]) // = Fire OS 8 (API 30) des Ziel-Sticks
class PhotoStoreTest {
    private val store = PhotoStore(ApplicationProvider.getApplicationContext())

    private fun jpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    @Test fun uploadedImageLoadsSampledToTargetSize() = runBlocking {
        val ref = store.saveUpload(jpeg(3840, 2160))
        assertNotNull(ref)
        val bitmap = store.load(ref!!, 1920, 1080)
        assertNotNull("Bild muss ladbar sein", bitmap)
        assertTrue(bitmap!!.width in 1920..3840)
        assertEquals(bitmap.width * 9, bitmap.height * 16)
    }

    @Test fun garbageIsRejected() = runBlocking {
        assertNull(store.saveUpload("kein bild".toByteArray()))
        assertNull(store.saveUpload(ByteArray(0)))
    }

    @Test fun deletedFileLoadsAsNullNotCrash() = runBlocking {
        val ref = store.saveUpload(jpeg(200, 100))!!
        store.delete(ref)
        assertNull(store.load(ref, 100, 100))
    }
}
