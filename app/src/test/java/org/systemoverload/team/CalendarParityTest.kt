package org.systemoverload.team

import org.junit.Assert.assertEquals
import org.junit.Test
import org.systemoverload.team.model.TeamCalendar
import java.util.TimeZone

/**
 * Meeting ids must match the website (mod-calendar), the bot (lib/calendar.mjs) and the iPhone app: signups and
 * attendance are keyed by them. fixture.expected is the bot's parser run on fixture.ics (see README, "Calendar ids").
 */
class CalendarParityTest {
    @Test fun matchesTheBotsParser() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val ics = javaClass.classLoader!!.getResource("fixture.ics").readText()
        val expected = javaClass.classLoader!!.getResource("fixture.expected").readText().trim().lines()
        val now = java.time.Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
        val got = TeamCalendar.events(ics, now).map { e ->
            listOf(e.id, e.dayKey, e.start.toLong(), e.end.toLong(), e.title, e.place, if (e.allDay) 1 else 0).joinToString("|")
        }
        assertEquals(expected.joinToString("\n"), got.joinToString("\n"))
    }
}
