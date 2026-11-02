package de.sofahub.app.feature.dashboard

import de.sofahub.app.integration.homeassistant.CalendarEvent
import de.sofahub.app.integration.homeassistant.HaRestClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 1, 14, 0, 0, 0, zone)

    private fun timed(summary: String, start: String, end: String) = CalendarEvent("calendar.c", summary, start, end, false)
    private fun allDay(summary: String, date: String, end: String) = CalendarEvent("calendar.m", summary, date, end, true)

    @Test fun `today, ongoing and tomorrow are labelled and sorted`() {
        val lines = Agenda.lines(
            listOf(
                allDay("Gelbe Tonne", "2026-11-02", "2026-11-03"),
                timed("Training", "2026-11-01T18:00:00+01:00", "2026-11-01T19:30:00+01:00"),
                timed("Kaffee", "2026-11-01T13:30:00+01:00", "2026-11-01T14:30:00+01:00"),
            ),
            now,
        )
        assertEquals(listOf("Jetzt: Kaffee", "18:00 Training", "Morgen: Gelbe Tonne"), lines)
    }

    @Test fun `past events and the day after tomorrow are left out`() {
        val lines = Agenda.lines(
            listOf(
                timed("Vorbei", "2026-11-01T09:00:00+01:00", "2026-11-01T10:00:00+01:00"),
                allDay("Übermorgen", "2026-11-03", "2026-11-04"),
                allDay("Heute ganztägig", "2026-11-01", "2026-11-02"),
                timed("Morgen früh", "2026-11-02T08:15:00+01:00", "2026-11-02T09:00:00+01:00"),
            ),
            now,
        )
        assertEquals(listOf("Heute: Heute ganztägig", "Morgen 08:15 Morgen früh"), lines)
    }

    @Test fun `limits to three entries and survives broken dates`() {
        val events = (1..5).map { allDay("Termin $it", "2026-11-02", "2026-11-03") } + allDay("Kaputt", "kein-datum", "")
        val lines = Agenda.lines(events, now)
        assertEquals(3, lines.size)
        assertTrue(lines.none { "Kaputt" in it })
    }

    @Test fun `parses home assistant calendar response`() {
        val json = """[{"start":{"dateTime":"2026-11-01T18:00:00+01:00"},"end":{"dateTime":"2026-11-01T19:00:00+01:00"},"summary":"Training"},
            {"start":{"date":"2026-11-02"},"end":{"date":"2026-11-03"},"summary":"Gelbe Tonne"},{"foo":1}]"""
        val events = HaRestClient.parseEvents("calendar.x", Json.parseToJsonElement(json).jsonArray)
        assertEquals(2, events.size)
        assertEquals(false, events[0].allDay)
        assertEquals(true, events[1].allDay)
        assertEquals("Gelbe Tonne", events[1].summary)
    }
}
