package de.sofahub.app.integration.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Sicherheitsregeln der Handy-Einrichtung, ohne Android testbar.
 * - Geheimnis: 24 zufällige Bytes (SecureRandom), nur im URL-Fragment des QR-Codes -> erscheint nicht in Request-Zeilen.
 * - Jeder API-Aufruf braucht das Geheimnis im Header X-Pairing-Secret (Vergleich in konstanter Zeit).
 * - Host-Header muss exakt ip:port sein (Schutz vor DNS-Rebinding), Origin falls vorhanden exakt http://ip:port
 *   (Schutz vor CSRF; ohne CORS-Freigabe können fremde Seiten weder den Header setzen noch JSON senden).
 * - Nach [MAX_FAILURES] falschen Geheimnissen wird gesperrt; das Fenster läuft nach [ttlMs] ab.
 */
class PairingSecurity(
    val host: String,
    val port: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60 * 1000L,
    random: SecureRandom = SecureRandom(),
) {
    val secret: String = ByteArray(24).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val expiresAtEpochMs: Long = now() + ttlMs
    private var failures = 0

    /** 4-stelliger Vergleichscode, den TV und Handy-Seite anzeigen. */
    val verificationCode: String = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).let {
        val n = ((it[0].toInt() and 0xff) shl 16) or ((it[1].toInt() and 0xff) shl 8) or (it[2].toInt() and 0xff)
        "%04d".format(n % 10000)
    }

    val authority: String get() = "$host:$port"
    val pageUrl: String get() = "http://$authority/#$secret"

    val locked: Boolean get() = failures >= MAX_FAILURES
    val expired: Boolean get() = now() > expiresAtEpochMs

    sealed interface Check {
        data object Ok : Check
        data object BadHost : Check
        data object BadOrigin : Check
        data object BadSecret : Check
        data object Locked : Check
        data object Expired : Check
    }

    fun checkPage(hostHeader: String?): Check = when {
        expired -> Check.Expired
        locked -> Check.Locked
        hostHeader != authority -> Check.BadHost
        else -> Check.Ok
    }

    @Synchronized
    fun checkApi(hostHeader: String?, origin: String?, providedSecret: String?): Check {
        if (expired) return Check.Expired
        if (locked) return Check.Locked
        if (hostHeader != authority) return Check.BadHost
        if (origin != null && origin != "http://$authority") return Check.BadOrigin
        if (providedSecret == null || !MessageDigest.isEqual(providedSecret.toByteArray(), secret.toByteArray())) {
            failures++
            return Check.BadSecret
        }
        return Check.Ok
    }

    companion object {
        const val MAX_FAILURES = 5
    }
}
