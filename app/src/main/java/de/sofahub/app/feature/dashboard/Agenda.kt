package de.sofahub.app.feature.dashboard

import de.sofahub.app.integration.homeassistant.CalendarEvent
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Kurze Terminzeile für die Startseite: was heute noch ansteht und was morgen kommt (z. B. „Morgen: Gelbe Tonne“). */
object Agenda {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    private data class Item(val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean, val summary: String)

    /** Abfragezeitraum: jetzt bis Ende von morgen. */
    fun window(now: ZonedDateTime): Pair<String, String> =
        now.toLocalDate().atStartOfDay(now.zone).toOffsetDateTime().toString() to
            now.toLocalDate().plusDays(2).atStartOfDay(now.zone).toOffsetDateTime().toString()

    fun lines(events: List<CalendarEvent>, now: ZonedDateTime, max: Int = 3): List<String> {
        val zone = now.zone
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        return events.asSequence()
            .mapNotNull { parse(it, zone) }
            .filter { it.end.isAfter(now) && it.start.toLocalDate() <= tomorrow }
            .distinctBy { it.summary to it.start }
            .sortedWith(compareBy({ if (it.start.isBefore(now)) now else it.start }, { !it.allDay }))
            .map { e ->
                val day = e.start.toLocalDate()
                when {
                    e.allDay && day <= today -> "Heute: ${e.summary}"
                    e.allDay -> "Morgen: ${e.summary}"
                    !e.start.isAfter(now) -> "Jetzt: ${e.summary}"
                    day == today -> "${e.start.format(hm)} ${e.summary}"
                    else -> "Morgen ${e.start.format(hm)} ${e.summary}"
                }
            }
            .take(max)
            .toList()
    }

    private fun parse(e: CalendarEvent, zone: ZoneId): Item? = runCatching {
        if (e.allDay) {
            val start = LocalDate.parse(e.start)
            val end = runCatching { LocalDate.parse(e.end) }.getOrDefault(start.plusDays(1)).let { if (it <= start) start.plusDays(1) else it }
            Item(start.atStartOfDay(zone), end.atStartOfDay(zone), true, e.summary)
        } else {
            val start = OffsetDateTime.parse(e.start).atZoneSameInstant(zone)
            val end = runCatching { OffsetDateTime.parse(e.end).atZoneSameInstant(zone) }.getOrDefault(start)
            Item(start, if (end.isAfter(start)) end else start.plusMinutes(1), false, e.summary)
        }
    }.getOrNull()
}
