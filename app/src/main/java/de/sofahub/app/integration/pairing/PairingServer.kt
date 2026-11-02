package de.sofahub.app.integration.pairing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

sealed interface PairingState {
    data object Idle : PairingState
    data object NoNetwork : PairingState
    data class Waiting(val url: String, val code: String, val expiresAtEpochMs: Long) : PairingState
    /** Ein Handy hat sich gemeldet und wartet auf Bestätigung am Fernseher. */
    data class AwaitingApproval(val url: String, val code: String, val expiresAtEpochMs: Long) : PairingState
    data class Approved(val url: String, val code: String, val expiresAtEpochMs: Long, val lastMessage: String? = null) : PairingState
    data object Finished : PairingState
    data object Expired : PairingState
    data object Locked : PairingState
    data class Failed(val reason: String) : PairingState
}

/** Erlaubte Aktionen, die das Handy auslösen darf. Alles andere wird abgewiesen. */
interface PairingActions {
    /** Testet und speichert die HA-Verbindung; liefert eine kurze Meldung für Handy und TV. */
    suspend fun applyHomeAssistant(address: String, token: String, allowHttp: Boolean): Pair<Boolean, String>
    suspend fun applyNote(note: String): Pair<Boolean, String>
    suspend fun applyGuestWifi(ssid: String, password: String, security: String): Pair<Boolean, String>
    suspend fun applyGitHubToken(token: String): Pair<Boolean, String>
    suspend fun applyImage(bytes: ByteArray, asBackground: Boolean): Pair<Boolean, String>
    /** Einstellungen als Sicherungsdatei (JSON, ohne Tokens). */
    suspend fun exportSettings(): String
    suspend fun importSettings(json: String): Pair<Boolean, String>
}

/**
 * Lokaler, zeitlich begrenzter Einrichtungsdienst (nur aktiv, solange die Einrichtungsansicht offen ist). Minimaler
 * HTTP/1.1-Server ohne Logging von Inhalten. Klartext-http schützt den HA-Token nicht vor Mitlesern im selben Netz –
 * das wird auf der Seite und in der README erklärt; die Eingabe am Fernseher bleibt die sichere Standardalternative.
 */
class PairingServer(private val actions: PairingActions) {
    private val state = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var security: PairingSecurity? = null
    private var approved = false
    private var requested = false
    private var lastResult: JsonObject = buildJsonObject { }

    fun start() {
        stop()
        val address = localIpv4()
        if (address == null) {
            state.value = PairingState.NoNetwork
            return
        }
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, 0), 8)
            }
        } catch (e: Exception) {
            state.value = PairingState.Failed("Port konnte nicht geöffnet werden")
            return
        }
        server = socket
        val sec = PairingSecurity(address.hostAddress ?: return, socket.localPort)
        security = sec
        approved = false
        requested = false
        lastResult = buildJsonObject { }
        state.value = PairingState.Waiting(sec.pageUrl, sec.verificationCode, sec.expiresAtEpochMs)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { acceptLoop(socket) }
        s.launch {
            delay(sec.expiresAtEpochMs - System.currentTimeMillis())
            stop(PairingState.Expired)
        }
    }

    /** Fernseher-Bestätigung für das anfragende Handy. */
    fun approve(allow: Boolean) {
        val sec = security ?: return
        if (state.value !is PairingState.AwaitingApproval) return
        if (allow) {
            approved = true
            state.value = PairingState.Approved(sec.pageUrl, sec.verificationCode, sec.expiresAtEpochMs)
        } else {
            stop(PairingState.Failed("Am Fernseher abgelehnt"))
        }
    }

    fun stop(final: PairingState = PairingState.Idle) {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
        security = null
        approved = false
        state.value = final
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope?.isActive == true) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            }
            scope?.launch { client.use { handle(it) } }
        }
    }

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val remote = socket.inetAddress?.hostAddress ?: "?"
        // Browser versuchen teils automatisch https: TLS-ClientHello (0x16) sofort beenden, damit sie schnell auf http zurückfallen.
        input.mark(1)
        val first = runCatching { input.read() }.getOrDefault(-1)
        if (first == 0x16 || first < 0) {
            debugLog("$remote ${if (first < 0) "leer" else "TLS-Versuch"} -> geschlossen")
            return
        }
        input.reset()
        val req = try {
            readRequest(input)
        } catch (e: Exception) {
            debugLog("$remote Lesefehler ${e.javaClass.simpleName}")
            null
        } ?: return respond(out, 400, "text/plain", "Bad Request".toByteArray())
        debugLog("$remote ${req.method} ${req.path.substringBefore('?')} host=${req.headers["host"]} origin=${req.headers["origin"]} secret=${if (req.headers["x-pairing-secret"] != null) "ja" else "nein"}")
        val sec = security ?: return respond(out, 410, "text/plain", "Beendet".toByteArray())
        val path = req.path.substringBefore('?')

        if (req.method == "GET" && path == "/") {
            return when (sec.checkPage(req.headers["host"])) {
                PairingSecurity.Check.Ok -> respond(out, 200, "text/html; charset=utf-8", PairingPage.html.toByteArray())
                PairingSecurity.Check.Expired -> respond(out, 410, "text/plain; charset=utf-8", "Einrichtung abgelaufen".toByteArray())
                else -> respond(out, 403, "text/plain", "Forbidden".toByteArray())
            }
        }
        if (!path.startsWith("/api/")) return respond(out, 404, "text/plain", "Not Found".toByteArray())
        if (req.method != "POST") return respond(out, 405, "text/plain", "Method Not Allowed".toByteArray())
        when (sec.checkApi(req.headers["host"], req.headers["origin"], req.headers["x-pairing-secret"])) {
            PairingSecurity.Check.Ok -> Unit
            PairingSecurity.Check.Locked -> {
                respondJson(out, 429, buildJsonObject { put("error", "locked") })
                stop(PairingState.Locked)
                return
            }
            PairingSecurity.Check.Expired -> return respondJson(out, 410, buildJsonObject { put("error", "expired") })
            else -> {
                if (sec.locked) stop(PairingState.Locked)
                return respondJson(out, 403, buildJsonObject { put("error", "forbidden") })
            }
        }

        when (path) {
            "/api/hello" -> {
                if (!approved && !requested) {
                    requested = true
                    state.value = PairingState.AwaitingApproval(sec.pageUrl, sec.verificationCode, sec.expiresAtEpochMs)
                }
                respondJson(out, 200, status(sec))
            }
            "/api/status" -> respondJson(out, 200, status(sec))
            "/api/finish" -> {
                respondJson(out, 200, buildJsonObject { put("finished", true) })
                stop(PairingState.Finished)
            }
            "/api/backup/export" -> {
                if (!approved) return respondJson(out, 403, buildJsonObject { put("error", "not_approved") })
                respond(out, 200, "application/json; charset=utf-8", runBlocking { actions.exportSettings() }.toByteArray())
            }
            "/api/ha", "/api/note", "/api/wifi", "/api/image", "/api/github", "/api/backup/import" -> {
                if (!approved) return respondJson(out, 403, buildJsonObject { put("error", "not_approved") })
                val (ok, message) = runBlocking {
                    when (path) {
                        "/api/ha" -> {
                            val o = parseJson(req.body) ?: return@runBlocking false to "Ungültige Eingabe"
                            actions.applyHomeAssistant(o.str("address") ?: "", o.str("token") ?: "", o.bool("allowHttp"))
                        }
                        "/api/note" -> {
                            val o = parseJson(req.body) ?: return@runBlocking false to "Ungültige Eingabe"
                            actions.applyNote(o.str("note") ?: "")
                        }
                        "/api/wifi" -> {
                            val o = parseJson(req.body) ?: return@runBlocking false to "Ungültige Eingabe"
                            actions.applyGuestWifi(o.str("ssid") ?: "", o.str("password") ?: "", o.str("security") ?: "")
                        }
                        "/api/github" -> {
                            val o = parseJson(req.body) ?: return@runBlocking false to "Ungültige Eingabe"
                            actions.applyGitHubToken(o.str("token") ?: "")
                        }
                        "/api/backup/import" -> actions.importSettings(req.body.toString(Charsets.UTF_8))
                        else -> {
                            if (req.headers["content-type"]?.startsWith("image/") != true) return@runBlocking false to "Nur Bilddateien"
                            actions.applyImage(req.body, req.path.contains("target=background"))
                        }
                    }
                }
                lastResult = buildJsonObject { put("ok", ok); put("message", message) }
                (state.value as? PairingState.Approved)?.let { state.value = it.copy(lastMessage = message) }
                respondJson(out, if (ok) 200 else 422, lastResult)
            }
            else -> respondJson(out, 404, buildJsonObject { put("error", "unknown") })
        }
    }

    private fun status(sec: PairingSecurity) = buildJsonObject {
        put("approved", approved)
        put("code", sec.verificationCode)
        put("expiresInSeconds", ((sec.expiresAtEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }

    private fun readRequest(input: InputStream): Req? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            if (headerBytes.size() > MAX_HEADER_BYTES) return null
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toLongOrNull() ?: 0
        val limit = when {
            parts[1].startsWith("/api/image") -> MAX_IMAGE_BYTES
            parts[1].startsWith("/api/backup") -> MAX_BACKUP_BYTES
            else -> MAX_JSON_BYTES
        }
        if (length < 0 || length > limit) return null
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) return null
            read += n
        }
        return Req(parts[0], parts[1], headers, body)
    }

    /** Nur Debug-Builds: Verbindungsdiagnose ohne Inhalte/Geheimnisse (adb logcat -s SofaHubPairing). */
    private fun debugLog(msg: String) {
        if (de.sofahub.app.BuildConfig.DEBUG) android.util.Log.i("SofaHubPairing", msg)
    }

    private fun respondJson(out: OutputStream, code: Int, body: JsonObject) =
        respond(out, code, "application/json; charset=utf-8", body.toString().toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        debugLog("  -> $code")
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 410 -> "Gone"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "Status" }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; form-action 'none'; frame-ancestors 'none'\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    private fun parseJson(bytes: ByteArray): JsonObject? = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    companion object {
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_JSON_BYTES = 16L * 1024
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_BACKUP_BYTES = 512L * 1024

        /** Private IPv4-Adresse des WLAN/Ethernet-Interfaces (kein Loopback, keine öffentliche Adresse). */
        fun localIpv4(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()
    }
}

private object PairingPage {
    val html = """
<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sofa Hub einrichten</title>
<style>
:root{color-scheme:dark}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#181715;color:#f4efe6}
main{max-width:520px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:26px;margin:8px 0 4px}h2{font-size:19px;margin:0 0 12px}
p{color:#c9c2b7;line-height:1.45}
.card{background:rgba(255,247,235,.06);border:1px solid rgba(255,247,235,.1);border-radius:20px;padding:18px;margin:16px 0}
label{display:block;font-size:14px;color:#c9c2b7;margin:12px 0 6px}
input[type=text],input[type=url],input[type=password],textarea{box-sizing:border-box;width:100%;padding:12px;border-radius:12px;border:1px solid rgba(255,247,235,.18);background:rgba(0,0,0,.3);color:#f4efe6;font:16px ui-monospace,monospace}
textarea{min-height:90px;font-family:inherit}
button{margin-top:14px;height:48px;padding:0 22px;border-radius:999px;border:0;background:#f4efe6;color:#1a1917;font-size:16px;font-weight:600}
button.sec{background:rgba(255,247,235,.1);color:#f4efe6}
.code{font:600 30px ui-monospace,monospace;letter-spacing:.2em}
.msg{margin-top:10px;font-size:15px}.ok{color:#5eead4}.err{color:#ff9a7a}
.warn{border-color:rgba(255,162,76,.4);background:rgba(255,162,76,.08)}
.hidden{display:none}
.row{display:flex;gap:8px;align-items:center;margin-top:10px}.row input{width:auto}
</style></head><body><main>
<h1>Sofa Hub einrichten</h1>
<p>Vergleiche den Code mit dem Fernseher: <span class="code" id="code">····</span></p>
<div id="wait" class="card"><p id="waitText">Verbinde …</p></div>
<div id="forms" class="hidden">
<div class="card">
<h2>Home Assistant</h2>
<label for="addr">Server-Adresse</label><input id="addr" type="url" placeholder="http://192.168.178.20:8123" autocomplete="off">
<label for="tok">Langlebiges Zugangstoken</label><input id="tok" type="password" autocomplete="off">
<div class="row"><input id="http" type="checkbox"><label for="http" style="margin:0">Unverschlüsseltes http für meine lokale Instanz erlauben</label></div>
<p style="font-size:14px;color:#a9a297;margin:8px 0 0">Bitte die IP-Adresse verwenden – Fire TV kann Namen mit .local nicht auflösen.</p>
<p class="warn card" style="font-size:14px;margin:12px 0 0">Diese Seite läuft ohne Verschlüsselung (http). Wer im selben WLAN mitliest, könnte den Token sehen. In einem fremden oder öffentlichen Netz den Token lieber direkt am Fernseher eingeben.</p>
<button id="sendHa">Verbindung testen &amp; übernehmen</button><div class="msg" id="haMsg"></div>
</div>
<div class="card">
<h2>Bilder</h2>
<label for="bg">Hintergrundbild</label><input id="bg" type="file" accept="image/*">
<button class="sec" id="sendBg">Als Hintergrund setzen</button><div class="msg" id="bgMsg"></div>
<label for="ph">Fotos für die Diashow</label><input id="ph" type="file" accept="image/*" multiple>
<button class="sec" id="sendPh">Fotos hinzufügen</button><div class="msg" id="phMsg"></div>
</div>
<div class="card">
<h2>Updates (GitHub-Token)</h2>
<p style="font-size:14px">Optional – nur nötig, wenn Updates aus einem privaten Repository kommen: Fine-grained-Token mit Lesezugriff (Contents: Read-only) nur auf dieses Repository.</p>
<label for="gh">GitHub-Token</label><input id="gh" type="password" autocomplete="off" placeholder="github_pat_…">
<button class="sec" id="sendGh">Token prüfen &amp; speichern</button><div class="msg" id="ghMsg"></div>
</div>
<div class="card">
<h2>Einstellungen sichern</h2>
<p style="font-size:14px">Favoriten, Gerätezuordnung, Automationen, Kalender, Design und Notiz als Datei – ohne Zugangstoken und ohne Fotos. Nach einem Zurücksetzen oder auf einem zweiten Fire TV wiederherstellen.</p>
<button class="sec" id="backupExport">Sicherung herunterladen</button>
<label for="backupFile">Sicherung wiederherstellen</label><input id="backupFile" type="file" accept="application/json,.json">
<button class="sec" id="backupImport">Wiederherstellen</button><div class="msg" id="backupMsg"></div>
</div>
<div class="card">
<h2>Gäste-WLAN</h2>
<p style="font-size:14px">Erscheint als QR-Code-Kachel auf der Startseite – Gäste scannen ihn mit der Handykamera.</p>
<label for="wssid">WLAN-Name</label><input id="wssid" autocomplete="off" maxlength="32">
<label for="wsec">Verschlüsselung</label><select id="wsec"><option value="WPA">WPA/WPA2/WPA3</option><option value="WEP">WEP</option><option value="NONE">Offen (ohne Passwort)</option></select>
<label for="wpw">Passwort</label><input id="wpw" autocomplete="off" maxlength="63">
<button class="sec" id="sendWifi">Gäste-WLAN speichern</button><div class="msg" id="wifiMsg"></div>
</div>
<div class="card">
<h2>Notiz</h2>
<textarea id="note" maxlength="500" placeholder="Persönliche Notiz für die Startseite"></textarea>
<button class="sec" id="sendNote">Notiz speichern</button><div class="msg" id="noteMsg"></div>
</div>
<button id="finish">Fertig – Einrichtung schließen</button>
</div>
</main>
<script>
(function(){
var secret=location.hash.slice(1);
history.replaceState(null,'',location.pathname);
function api(path,body,type){return fetch(path,{method:'POST',headers:{'X-Pairing-Secret':secret,'Content-Type':type||'application/json'},body:body===undefined?'{}':body,cache:'no-store'}).then(function(r){return r.json().then(function(j){j._status=r.status;return j})})}
function msg(id,ok,text){var e=document.getElementById(id);e.textContent=text;e.className='msg '+(ok?'ok':'err')}
function show(approved){document.getElementById('wait').className=approved?'hidden':'card';document.getElementById('forms').className=approved?'':'hidden'}
function poll(){api('/api/status').then(function(j){if(j._status!==200){document.getElementById('waitText').textContent='Einrichtung nicht mehr aktiv. Am Fernseher neu starten.';return}
document.getElementById('code').textContent=j.code;show(j.approved);if(!j.approved)setTimeout(poll,1500)}).catch(function(){document.getElementById('waitText').textContent='Fernseher nicht erreichbar. Gleiches WLAN? Gast-WLAN trennt Geräte oft voneinander.'})}
if(!secret){document.getElementById('waitText').textContent='Bitte den QR-Code am Fernseher erneut scannen.';return}
api('/api/hello').then(function(j){if(j._status!==200){document.getElementById('waitText').textContent=j._status===429?'Zu viele Fehlversuche – Einrichtung gesperrt.':'Zugriff verweigert. QR-Code erneut scannen.';return}
document.getElementById('code').textContent=j.code;document.getElementById('waitText').textContent='Bitte am Fernseher bestätigen.';show(j.approved);if(!j.approved)setTimeout(poll,1500)})
.catch(function(){document.getElementById('waitText').textContent='Fernseher nicht erreichbar. Gleiches WLAN? Gast-WLAN trennt Geräte oft voneinander.'});
document.getElementById('sendHa').onclick=function(){msg('haMsg',true,'Teste Verbindung …');api('/api/ha',JSON.stringify({address:document.getElementById('addr').value,token:document.getElementById('tok').value,allowHttp:document.getElementById('http').checked})).then(function(j){msg('haMsg',j.ok,j.message||'Fehler');if(j.ok)document.getElementById('tok').value=''}).catch(function(){msg('haMsg',false,'Keine Verbindung zum Fernseher')})};
function upload(inputId,msgId,bg){var files=document.getElementById(inputId).files;if(!files.length){msg(msgId,false,'Bitte ein Bild wählen');return}
var list=Array.prototype.slice.call(files),done=0,failed=0;msg(msgId,true,'Lade hoch …');
(function next(){if(!list.length){msg(msgId,failed===0,done+' übertragen'+(failed?', '+failed+' fehlgeschlagen':''));return}var f=list.shift();
api('/api/image?target='+(bg?'background':'photo'),f,f.type||'image/jpeg').then(function(j){j.ok?done++:failed++;next()}).catch(function(){failed++;next()})})()}
document.getElementById('sendBg').onclick=function(){upload('bg','bgMsg',true)};
document.getElementById('sendPh').onclick=function(){upload('ph','phMsg',false)};
document.getElementById('sendGh').onclick=function(){msg('ghMsg',true,'Prüfe …');api('/api/github',JSON.stringify({token:document.getElementById('gh').value})).then(function(j){msg('ghMsg',j.ok,j.message||'Fehler');if(j.ok)document.getElementById('gh').value=''}).catch(function(){msg('ghMsg',false,'Keine Verbindung zum Fernseher')})};
document.getElementById('sendWifi').onclick=function(){api('/api/wifi',JSON.stringify({ssid:document.getElementById('wssid').value,password:document.getElementById('wpw').value,security:document.getElementById('wsec').value})).then(function(j){msg('wifiMsg',j.ok,j.message||'Fehler')}).catch(function(){msg('wifiMsg',false,'Keine Verbindung zum Fernseher')})};
document.getElementById('sendNote').onclick=function(){api('/api/note',JSON.stringify({note:document.getElementById('note').value})).then(function(j){msg('noteMsg',j.ok,j.message||'Fehler')})};
document.getElementById('backupExport').onclick=function(){fetch('/api/backup/export',{method:'POST',headers:{'X-Pairing-Secret':secret},cache:'no-store'}).then(function(r){if(!r.ok)throw 0;return r.blob()}).then(function(b){var a=document.createElement('a');a.href=URL.createObjectURL(b);a.download='sofahub-sicherung-'+new Date().toISOString().slice(0,10)+'.json';document.body.appendChild(a);a.click();a.remove();msg('backupMsg',true,'Sicherung gespeichert')}).catch(function(){msg('backupMsg',false,'Sicherung fehlgeschlagen')})};
document.getElementById('backupImport').onclick=function(){var f=document.getElementById('backupFile').files[0];if(!f){msg('backupMsg',false,'Bitte eine Sicherungsdatei wählen');return}
if(!confirm('Aktuelle Einstellungen durch die Sicherung ersetzen?'))return;f.text().then(function(t){return api('/api/backup/import',t)}).then(function(j){msg('backupMsg',j.ok,j.message||'Fehler')}).catch(function(){msg('backupMsg',false,'Keine Verbindung zum Fernseher')})};
document.getElementById('finish').onclick=function(){api('/api/finish').then(function(){document.getElementById('forms').className='hidden';document.getElementById('wait').className='card';document.getElementById('waitText').textContent='Fertig. Du kannst diese Seite schließen.'})};
})();
</script></body></html>
""".trimIndent()
}
