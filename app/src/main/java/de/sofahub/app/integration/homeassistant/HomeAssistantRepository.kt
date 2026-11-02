package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.ActionStatus
import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaConfig
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.storage.SecretStore
import de.sofahub.app.core.storage.SettingsRepository
import de.sofahub.app.core.storage.TokenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

interface HomeAssistantRepository {
    val snapshot: StateFlow<HaSnapshot>
    val isDemo: Boolean get() = false

    fun start()
    fun setForeground(foreground: Boolean)

    /**
     * Was im Hintergrund weiter beobachtet wird (für Meldungen über anderen Apps). Leer = im Hintergrund ganz trennen
     * wie bisher; sonst bleibt eine schlanke Verbindung nur für diese Entitäten offen.
     */
    fun setBackgroundWatch(ids: Set<String>)
    fun reconnectNow()

    /** Prüft Adresse und Token, ohne irgendetwas zu speichern. Liefert die gefundenen Entitäten. */
    suspend fun testConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>>

    /** Testet und speichert erst bei Erfolg; bestehende Konfiguration bleibt bei Fehlern unverändert. */
    suspend fun saveConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>>

    /** Token löschen, Verbindung schließen, Cache leeren. */
    suspend fun disconnect()

    fun setOn(entityId: String, on: Boolean)
    fun setBrightness(entityId: String, percent: Int)
    fun activate(entityId: String)
    fun media(entityId: String, action: MediaAction)
    fun selectSource(entityId: String, source: String)

    /**
     * Serviceaufruf direkt per REST, ohne laufende Live-Verbindung – für Automationen (z. B. beim Ausschalten, wenn die
     * App im Hintergrund ist). Wiederholt nichts automatisch.
     */
    suspend fun callDirect(call: ServiceCall): HaResult<Unit>

    /** Standbild einer Kamera-Entität (JPEG-Bytes). */
    suspend fun cameraImage(entityId: String): HaResult<ByteArray> = HaResult.Err(HaError.NotConnected)

    /** Zustand einer eigenen Entität in HA setzen (für die Meldung des Fire-TV-Zustands). */
    suspend fun setStateDirect(entityId: String, state: String, attributes: kotlinx.serialization.json.JsonObject): HaResult<Unit> =
        HaResult.Err(HaError.NotConnected)

    /** Zahlenverlauf der letzten [hours] Stunden (leer, wenn nicht verfügbar). */
    suspend fun history(entityId: String, hours: Int): List<Pair<Long, Float>> = emptyList()

    /** Termine der Kalender im Zeitraum (ISO-8601); fehlerhafte Kalender werden übersprungen. */
    suspend fun calendarEvents(entityIds: List<String>, start: String, end: String): List<CalendarEvent> = emptyList()
}

class RealHomeAssistantRepository(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
) : HomeAssistantRepository {
    // Bis die gespeicherte Konfiguration geladen ist, gilt "verbinde" – sonst blitzt beim Start "nicht eingerichtet" auf.
    private val state = MutableStateFlow(HaSnapshot(connection = HaConnection.Connecting))
    override val snapshot: StateFlow<HaSnapshot> = state.asStateFlow()
    @Volatile private var configLoaded = false

    private var connectionJob: Job? = null
    private var session: HaWebSocketSession? = null
    @Volatile private var foreground = true
    @Volatile private var backgroundWatch: Set<String> = emptySet()
    private var config: HaConfig? = null
    private var started = false
    private val brightnessJobs = ConcurrentHashMap<String, Job>()
    /**
     * Verlauf und Termine kurz zwischenspeichern: Die Startseite wird bei jeder Rückkehr (Einstellungen, Smart Home,
     * andere App) neu aufgebaut – ohne Cache hieße das jedes Mal neue, teils große Abrufe.
     */
    private val historyCache = ConcurrentHashMap<String, Pair<Long, List<Pair<Long, Float>>>>()
    private val calendarCache = ConcurrentHashMap<String, Pair<Long, List<CalendarEvent>>>()
    private val actionClearJobs = ConcurrentHashMap<String, Job>()

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.settings.map { it.ha }.distinctUntilChanged().collect { ha ->
                config = ha
                configLoaded = true
                restart()
            }
        }
    }

    override fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        if (!configLoaded) return
        // Im Hintergrund wird der Socket kontrolliert geschlossen; bei Rückkehr folgt ein vollständiger Neuabgleich.
        restart()
    }

    override fun setBackgroundWatch(ids: Set<String>) {
        if (backgroundWatch == ids) return
        backgroundWatch = ids
        if (configLoaded && !foreground) restart()
    }

    override fun reconnectNow() = restart()

    @Synchronized
    private fun restart() {
        val previous = connectionJob
        session?.close()
        // JSON-Verarbeitung großer Snapshots nicht auf dem Hauptthread.
        connectionJob = scope.launch(Dispatchers.Default) {
            previous?.cancel()
            previous?.join()
            connectLoop()
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (true) {
            val cfg = config
            if (cfg == null) {
                state.value = HaSnapshot(connection = HaConnection.NotConfigured)
                return
            }
            val watch = if (foreground) null else backgroundWatch
            if (watch != null && watch.isEmpty()) {
                state.update { it.copy(connection = HaConnection.Disconnected, stale = true) }
                return
            }
            val token = when (val t = secrets.readToken()) {
                is TokenResult.Present -> t.token
                TokenResult.Missing -> {
                    state.update { it.copy(connection = HaConnection.AuthFailed, stale = true) }
                    return
                }
                TokenResult.Unreadable -> {
                    state.update { it.copy(connection = HaConnection.TokenUnreadable, stale = true) }
                    return
                }
            }
            val url = (HaUrl.parse(cfg.baseUrl) as? HaUrlResult.Valid)
            if (url == null || (url.isHttp && !cfg.allowHttp)) {
                state.update { it.copy(connection = HaConnection.Retrying(HaError.InvalidUrl, Long.MAX_VALUE), stale = true) }
                return
            }
            state.update { it.copy(connection = HaConnection.Connecting) }
            val s = HaWebSocketSession(client, HaUrl.websocket(url.url), token, watch = watch)
            session = s
            val end = s.run { event ->
                when (event) {
                    SessionEvent.Authenticated -> Unit
                    // Hintergrund: beobachtete Entitäten in den letzten Stand einmischen, der Rest bleibt (veraltet) stehen.
                    is SessionEvent.Snapshot if watch != null -> {
                        attempt = 0
                        state.update { it.copy(connection = HaConnection.Connected, entities = it.entities + event.entities, stale = true) }
                    }
                    is SessionEvent.Changed if watch != null -> state.update { it.copy(entities = it.entities + event.entities) }
                    is SessionEvent.Snapshot -> {
                        attempt = 0
                        state.update {
                            it.copy(
                                connection = HaConnection.Connected,
                                entities = event.entities,
                                deleted = event.deleted,
                                stale = false,
                                lastSyncEpochMs = System.currentTimeMillis(),
                            )
                        }
                    }
                    is SessionEvent.Changed -> state.update {
                        it.copy(entities = event.entities, deleted = event.deleted, lastSyncEpochMs = System.currentTimeMillis())
                    }
                    is SessionEvent.Registry -> state.update {
                        it.copy(areas = event.areas, entityAreas = event.entityAreas, registryAvailable = true)
                    }
                    SessionEvent.RegistryUnavailable -> state.update { it.copy(registryAvailable = false) }
                }
            }
            session = null
            when (end) {
                SessionEnd.AuthInvalid -> {
                    state.update { it.copy(connection = HaConnection.AuthFailed, stale = true) }
                    return
                }
                is SessionEnd.Lost -> {
                    if (!foreground && backgroundWatch.isEmpty()) continue
                    if (end.error == HaError.Unauthorized) {
                        state.update { it.copy(connection = HaConnection.AuthFailed, stale = true) }
                        return
                    }
                    val wait = backoffMs(attempt++)
                    state.update { it.copy(connection = HaConnection.Retrying(end.error, System.currentTimeMillis() + wait), stale = true) }
                    delay(wait)
                }
            }
        }
    }

    override suspend fun testConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>> {
        val url = when (val parsed = HaUrl.parse(address)) {
            is HaUrlResult.Invalid -> return HaResult.Err(HaError.InvalidUrl)
            is HaUrlResult.Valid -> parsed
        }
        if (url.isHttp && !allowHttp) return HaResult.Err(HaError.HttpNotConfirmed)
        if (token.isBlank()) return HaResult.Err(HaError.Unauthorized)
        val rest = HaRestClient(url.url, token.trim(), client)
        return when (val check = rest.checkApi()) {
            is HaResult.Err ->
                // Fire OS 8 löst mDNS-Namen nicht auf: verständlicher Hinweis statt "nicht erreichbar".
                if (check.error == HaError.Network && url.url.host.endsWith(".local", ignoreCase = true)) HaResult.Err(HaError.LocalNameUnsupported) else check
            is HaResult.Ok -> rest.states()
        }
    }

    override suspend fun saveConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>> {
        val result = testConnection(address, token, allowHttp)
        if (result is HaResult.Ok) {
            val url = (HaUrl.parse(address) as HaUrlResult.Valid).normalized
            secrets.saveToken(token.trim())
            val previousUrl = config?.baseUrl
            settings.update {
                // Neuer Server: alte Zuordnungen bleiben erhalten, aber ein Token geht nie automatisch an einen anderen Host.
                it.copy(ha = HaConfig(url, allowHttp = allowHttp && url.startsWith("http://")))
            }
            if (previousUrl == url) restart()
        }
        return result
    }

    override suspend fun disconnect() {
        secrets.clearToken()
        settings.update { it.copy(ha = null) }
        session?.close()
        state.value = HaSnapshot(connection = HaConnection.NotConfigured)
    }

    private fun rest(): HaRestClient? {
        val cfg = config ?: return null
        val url = (HaUrl.parse(cfg.baseUrl) as? HaUrlResult.Valid)?.url ?: return null
        val token = (secrets.readToken() as? TokenResult.Present)?.token ?: return null
        return HaRestClient(url, token, client)
    }

    private fun perform(entityId: String, call: ServiceCall) {
        val snap = state.value
        if (snap.connection != HaConnection.Connected) return setAction(entityId, ActionStatus.Failed(HaError.NotConnected))
        val entity = snap.entities[entityId]
        if (entity == null || entity.availability == Availability.UNAVAILABLE) {
            return setAction(entityId, ActionStatus.Failed(HaError.EntityUnavailable))
        }
        val rest = rest() ?: return setAction(entityId, ActionStatus.Failed(HaError.NotConnected))
        setAction(entityId, ActionStatus.Pending)
        scope.launch {
            // Bewusst kein automatischer Wiederholversuch: bei Szenen/Skripten könnte das doppelt auslösen.
            when (val r = rest.callService(call.domain, call.service, call.data)) {
                is HaResult.Ok -> setAction(entityId, ActionStatus.Acknowledged)
                is HaResult.Err -> setAction(entityId, ActionStatus.Failed(r.error))
            }
        }
    }

    private fun setAction(entityId: String, status: ActionStatus) {
        state.update { it.copy(actions = it.actions + (entityId to status)) }
        actionClearJobs.remove(entityId)?.cancel()
        if (status !is ActionStatus.Pending) {
            actionClearJobs[entityId] = scope.launch {
                delay(if (status is ActionStatus.Failed) 6_000 else 2_500)
                state.update { s -> if (s.actions[entityId] == status) s.copy(actions = s.actions - entityId) else s }
            }
        }
    }

    override fun setOn(entityId: String, on: Boolean) = perform(entityId, HaServices.setOn(entityId, on))

    override fun setBrightness(entityId: String, percent: Int) {
        // Schnelle Tastendrücke bündeln: nur der letzte Wert nach kurzer Ruhe wird gesendet.
        brightnessJobs.remove(entityId)?.cancel()
        setAction(entityId, ActionStatus.Pending)
        brightnessJobs[entityId] = scope.launch {
            delay(350)
            brightnessJobs.remove(entityId)
            perform(entityId, HaServices.setBrightness(entityId, percent))
        }
    }

    override fun activate(entityId: String) = when (entityId.substringBefore('.')) {
        "scene" -> perform(entityId, HaServices.activateScene(entityId))
        "script" -> perform(entityId, HaServices.runScript(entityId))
        else -> perform(entityId, HaServices.turnOn(entityId))
    }

    override fun media(entityId: String, action: MediaAction) = perform(entityId, HaServices.media(entityId, action))
    override fun selectSource(entityId: String, source: String) = perform(entityId, HaServices.selectSource(entityId, source))

    override suspend fun callDirect(call: ServiceCall): HaResult<Unit> {
        // Konfiguration direkt lesen: im Hintergrund (Dienst nach dem Einschalten) läuft die Live-Verbindung evtl. nicht.
        val cfg = config ?: settings.current().ha ?: return HaResult.Err(HaError.NotConnected)
        val url = (HaUrl.parse(cfg.baseUrl) as? HaUrlResult.Valid)?.takeIf { !it.isHttp || cfg.allowHttp }?.url
            ?: return HaResult.Err(HaError.InvalidUrl)
        val token = (secrets.readToken() as? TokenResult.Present)?.token ?: return HaResult.Err(HaError.Unauthorized)
        return HaRestClient(url, token, client).callService(call.domain, call.service, call.data)
    }

    override suspend fun setStateDirect(entityId: String, state: String, attributes: kotlinx.serialization.json.JsonObject): HaResult<Unit> {
        val cfg = config ?: settings.current().ha ?: return HaResult.Err(HaError.NotConnected)
        val url = (HaUrl.parse(cfg.baseUrl) as? HaUrlResult.Valid)?.takeIf { !it.isHttp || cfg.allowHttp }?.url
            ?: return HaResult.Err(HaError.InvalidUrl)
        val token = (secrets.readToken() as? TokenResult.Present)?.token ?: return HaResult.Err(HaError.Unauthorized)
        return HaRestClient(url, token, client).postState(entityId, state, attributes)
    }

    override suspend fun history(entityId: String, hours: Int): List<Pair<Long, Float>> {
        val key = "$entityId|$hours"
        val now = System.currentTimeMillis()
        historyCache[key]?.let { (at, points) -> if (now - at < HISTORY_FRESH_MS) return points }
        val rest = rest() ?: return emptyList()
        val start = java.time.OffsetDateTime.now().minusHours(hours.toLong()).withNano(0).toString()
        val raw = (rest.history(entityId, start) as? HaResult.Ok)?.value ?: return historyCache[key]?.second.orEmpty()
        // HA liefert für 24 h oft tausende Werte – abseits der Oberfläche auf ~120 Mittelwerte verdichten.
        val points = kotlinx.coroutines.withContext(Dispatchers.Default) { downsample(raw, 120) }
        historyCache[key] = now to points
        return points
    }

    override suspend fun calendarEvents(entityIds: List<String>, start: String, end: String): List<CalendarEvent> {
        val key = "${entityIds.joinToString(",")}|$start|$end"
        val now = System.currentTimeMillis()
        calendarCache[key]?.let { (at, events) -> if (now - at < CALENDAR_FRESH_MS) return events }
        val rest = rest() ?: return emptyList()
        val events = entityIds.flatMap { id -> (rest.calendarEvents(id, start, end) as? HaResult.Ok)?.value.orEmpty() }
        if (calendarCache.size > 8) calendarCache.clear()
        calendarCache[key] = now to events
        return events
    }

    override suspend fun cameraImage(entityId: String): HaResult<ByteArray> {
        if (state.value.connection != HaConnection.Connected) return HaResult.Err(HaError.NotConnected)
        val rest = rest() ?: return HaResult.Err(HaError.NotConnected)
        return rest.cameraImage(entityId)
    }

    companion object {
        private const val HISTORY_FRESH_MS = 10 * 60_000L
        private const val CALENDAR_FRESH_MS = 5 * 60_000L

        /** Werte in [buckets] gleich breite Zeitabschnitte mitteln (für die kleine Verlaufskurve). */
        fun downsample(points: List<Pair<Long, Float>>, buckets: Int): List<Pair<Long, Float>> {
            if (points.size <= buckets) return points
            val t0 = points.first().first
            val span = (points.last().first - t0).coerceAtLeast(1)
            return points.groupBy { ((it.first - t0) * (buckets - 1) / span).toInt() }.toSortedMap().map { (_, g) ->
                g.map { it.first }.average().toLong() to g.map { it.second }.average().toFloat()
            }
        }

        /** Begrenztes exponentielles Backoff mit Jitter: 1 s, 2 s, 4 s … höchstens 60 s, ±20 %. */
        fun backoffMs(attempt: Int, random: Random = Random.Default): Long {
            val base = min(60_000L, 1_000L shl min(attempt, 6))
            val jitter = (base * 0.2 * (random.nextDouble() * 2 - 1)).toLong()
            return (base + jitter).coerceAtLeast(500)
        }
    }
}
