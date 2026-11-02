package de.sofahub.app.core.model

import de.sofahub.app.core.storage.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GuestWifiTest {
    @Test fun `qr content follows the wifi qr format with escaping`() {
        assertEquals("WIFI:T:WPA;S:Gäste;P:geheim123;;", GuestWifi("Gäste", "geheim123").qrContent())
        assertEquals("""WIFI:T:WPA;S:a\;b\,c;P:p\:w\\x\"y;;""", GuestWifi("a;b,c", "p:w\\x\"y").qrContent())
        assertEquals("WIFI:T:nopass;S:Cafe;;", GuestWifi("Cafe", "egal", WifiSecurity.NONE).qrContent())
        assertEquals("WIFI:T:WEP;S:Alt;P:12345;;", GuestWifi("Alt", "12345", WifiSecurity.WEP).qrContent())
    }

    @Test fun `input problems are reported`() {
        assertNotNull(GuestWifi(" ", "12345678").problem())
        assertNotNull(GuestWifi("Gast", "kurz").problem())
        assertNotNull(GuestWifi("x".repeat(33), "12345678").problem())
        assertNull(GuestWifi("Gast", "12345678").problem())
        assertNull(GuestWifi("Gast", "", WifiSecurity.NONE).problem())
    }

    @Test fun `guest wifi survives storage round trip`() {
        val s = AppSettings(guestWifi = GuestWifi("Gast", "12345678", WifiSecurity.WPA))
        assertEquals(s.guestWifi, SettingsCodec.decode(SettingsCodec.encode(s)).guestWifi)
    }
}
