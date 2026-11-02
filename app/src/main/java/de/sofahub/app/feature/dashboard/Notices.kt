package de.sofahub.app.feature.dashboard

import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.integration.homeassistant.HomeAssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** Kurze Meldung für die Statusinsel. */
data class Notice(val text: String, val sub: String? = null, val warn: Boolean = false)

/**
 * Erkennt Meldungen für die ganze App – auch, wenn Sofa Hub im Hintergrund ist. Angezeigt werden sie in der Insel
 * (Sofa Hub vorn) oder vom Hintergrunddienst über anderen Apps.
 */
class NoticeCenter(private val ha: HomeAssistantRepository, settings: StateFlow<AppSettings?>, scope: CoroutineScope) {
    private val tracker = NoticeTracker()
    private val flow = MutableSharedFlow<Notice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = flow

    init {
        scope.launch {
            // Höchstens alle 250 ms prüfen: im Vordergrund kommt jede Zustandsänderung im Haus hier an.
            combine(ha.snapshot, settings) { snap, s -> snap to s }.conflate().collect { (snap, s) ->
                if (s == null || ha.isDemo || snap.connection != HaConnection.Connected) return@collect
                tracker.update(snap, s.noticeEntities, System.currentTimeMillis()).forEach { flow.tryEmit(it) }
                delay(250)
            }
        }
    }

    /** Was im Hintergrund beobachtet werden muss: gewählte Entitäten und die Sensoren erkannter 3D-Drucker. */
    fun watchIds(snap: HaSnapshot, s: AppSettings): Set<String> = s.noticeEntities.toSet() + Printers.watchIds(snap)
}

/**
 * Erkennt meldenswerte Änderungen: Druckstatus und Meilensteine (25/50/75/90 %) der 3D-Drucker sowie Zustandswechsel
 * der in den Einstellungen gewählten Entitäten (z. B. „Waschmaschine: Fertig“). Der erste Blick merkt sich nur den
 * Zustand, meldet aber nichts; pro Entität höchstens eine Meldung pro Minute. Wechsel, die schon länger zurückliegen
 * (etwa während der Fernseher aus war), werden still übernommen statt nachträglich gemeldet.
 */
class NoticeTracker(private val minGapMs: Long = 60_000) {
    private val last = mutableMapOf<String, String>()
    private val shownAt = mutableMapOf<String, Long>()

    fun update(snap: HaSnapshot, watched: List<String>, now: Long): List<Notice> {
        val out = mutableListOf<Notice>()
        Printers.all(snap).forEach { job ->
            val milestone = if (job.status == "running") MILESTONES.lastOrNull { (job.progress ?: 0) >= it } ?: 0 else 0
            val key = "printer:${job.cameraId}"
            val state = "${job.status}|$milestone"
            val before = last.put(key, state) ?: return@forEach
            if (before == state) return@forEach
            val (prevStatus, prevMilestone) = before.split('|').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            val notice = when {
                job.status == prevStatus && milestone > prevMilestone -> Notice("${job.printer}: $milestone %", job.remainingMinutes?.let { Printers.remainingLabel(it) })
                job.status == prevStatus -> null
                stale(job.statusChanged, now) -> null
                job.status == "running" && prevStatus != "pause" -> Notice("${job.printer}: Druck gestartet", job.task)
                job.status == "running" -> Notice("${job.printer}: Druck läuft weiter", job.task)
                job.status == "pause" -> Notice("${job.printer}: Druck pausiert", job.task, warn = true)
                job.status == "finish" && prevStatus in ACTIVE -> Notice("${job.printer}: Druck fertig", job.task)
                job.status == "failed" -> Notice("${job.printer}: Druck fehlgeschlagen", job.task, warn = true)
                else -> null
            }
            notice?.let { if (allowed(key, now)) out += it }
        }
        watched.forEach { id ->
            val e = snap.entities[id] ?: return@forEach
            if (e.availability == Availability.UNKNOWN) return@forEach
            val before = last.put(id, e.state) ?: return@forEach
            if (before == e.state || stale(e.lastChanged, now)) return@forEach
            if (allowed(id, now)) out += Notice("${e.friendlyName}: ${EntityView.stateLabel(e)}", warn = e.availability == Availability.UNAVAILABLE)
        }
        return out
    }

    private fun allowed(key: String, now: Long): Boolean {
        val prev = shownAt[key]
        if (prev != null && now - prev < minGapMs) return false
        shownAt[key] = now
        return true
    }

    private fun stale(changed: String, now: Long): Boolean =
        runCatching { now - java.time.OffsetDateTime.parse(changed).toInstant().toEpochMilli() > STALE_MS }.getOrDefault(false)

    private companion object {
        const val STALE_MS = 10 * 60_000L
        val MILESTONES = listOf(25, 50, 75, 90)
        val ACTIVE = setOf("running", "prepare", "pause", "slicing")
    }
}
