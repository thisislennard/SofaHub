package de.sofahub.app.integration.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecurityTest {
    private var now = 1_000_000L
    private fun sec() = PairingSecurity("192.168.178.20", 40123, now = { now })

    @Test fun secretIsLongRandomAndOnlyInFragment() {
        val a = sec()
        val b = sec()
        assertNotEquals(a.secret, b.secret)
        assertTrue(a.secret.length >= 32)
        assertEquals("http://192.168.178.20:40123/#${a.secret}", a.pageUrl)
        assertTrue(a.verificationCode.matches(Regex("\\d{4}")))
    }

    @Test fun apiRequiresSecretHostAndOrigin() {
        val s = sec()
        val host = "192.168.178.20:40123"
        assertEquals(PairingSecurity.Check.Ok, s.checkApi(host, null, s.secret))
        assertEquals(PairingSecurity.Check.Ok, s.checkApi(host, "http://$host", s.secret))
        // DNS-Rebinding: fremder Host-Header
        assertEquals(PairingSecurity.Check.BadHost, s.checkApi("evil.example:40123", null, s.secret))
        // CSRF: fremde Origin
        assertEquals(PairingSecurity.Check.BadOrigin, s.checkApi(host, "http://evil.example", s.secret))
        assertEquals(PairingSecurity.Check.BadSecret, s.checkApi(host, null, null))
        assertEquals(PairingSecurity.Check.BadSecret, s.checkApi(host, null, "falsch"))
    }

    @Test fun locksAfterTooManyWrongSecrets() {
        val s = sec()
        val host = "192.168.178.20:40123"
        repeat(PairingSecurity.MAX_FAILURES) { s.checkApi(host, null, "falsch$it") }
        assertTrue(s.locked)
        assertEquals(PairingSecurity.Check.Locked, s.checkApi(host, null, s.secret))
    }

    @Test fun expiresAfterTtl() {
        val s = sec()
        assertFalse(s.expired)
        now += 10 * 60 * 1000L + 1
        assertTrue(s.expired)
        assertEquals(PairingSecurity.Check.Expired, s.checkApi("192.168.178.20:40123", null, s.secret))
        assertEquals(PairingSecurity.Check.Expired, s.checkPage("192.168.178.20:40123"))
    }
}
