package org.systemoverload.team.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One dated occurrence on the team calendar.
 * The `id` must match the web app's (mod-calendar `expand`), the bot's (lib/calendar.mjs) and the iPhone app's:
 * signups, attendance and settings/slackSignups are keyed by it.
 */
data class CalEvent(
    val id: String, val uid: String, val title: String, val place: String, val link: String, val service: String,
    val code: String, val mode: String, val description: String, val allDay: Boolean, val start: Double, val end: Double,
    val dayKey: String,
) {
    /** The day it's on. All-day events are stored at UTC midnight, so they're placed by `dayKey`, never by `start`. */
    val day: LocalDate get() = LocalDate.parse(dayKey)

    val isCompetition: Boolean get() = COMPETITION_RE.containsMatchIn(title)
    val isNotCompeting: Boolean get() = title.contains("not competing", ignoreCase = true)
    val isHangar: Boolean get() = title.contains("hangar", ignoreCase = true)
    /** trackFromTitle in mod-state */
    val track: String get() = when {
        title.contains("build committee", ignoreCase = true) -> "build"
        BOARD_RE.containsMatchIn(title) -> "board"
        title.contains("operations", ignoreCase = true) -> "ops"
        else -> ""
    }

    /** "6:00 – 9:00 PM", "All day", "Mar 6 – Mar 8" */
    val whenLabel: String get() {
        if (allDay) {
            val days = ((end - start) / TeamCalendar.DAY).roundToInt()
            if (days <= 1) return "All day"
            val f = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).withZone(ZoneOffset.UTC)
            return "${f.format(Instant.ofEpochMilli(start.toLong()))} – ${f.format(Instant.ofEpochMilli((end - TeamCalendar.DAY).toLong()))}"
        }
        val z = ZoneId.systemDefault()
        val a = Instant.ofEpochMilli(start.toLong()).atZone(z)
        val b = Instant.ofEpochMilli(max(end, start).toLong()).atZone(z)
        val t = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
        val ta = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        return if (a.hour / 12 == b.hour / 12 && a.toLocalDate() == b.toLocalDate()) "${a.format(t)} – ${b.format(ta)}" else "${a.format(ta)} – ${b.format(ta)}"
    }

    /** "Five Rivers Aviation, 700 Terminal Cir, Livermore, CA 94551" → "Five Rivers Aviation, Livermore" */
    val shortPlace: String get() {
        val parts = place.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return place
        return if (parts[0].firstOrNull()?.isDigit() == true) "${parts[0]}, ${parts[1]}" else "${parts[0]}, ${parts[2]}"
    }

    companion object {
        private val COMPETITION_RE = Regex(
            "throwdown|classic|showdown|regional|district|championship|\\bchamps\\b|competition|scrimmage|invitational|qualifier|\\bweek 0\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** A port of the web app's ICS reader (mod-calendar): parse, then expand repeating events. */
object TeamCalendar {
    const val DAY = 86_400_000.0

    data class Prop(val name: String, val params: Map<String, String>, val value: String)
    private class Parsed { var tz = "America/Los_Angeles"; var name = ""; val events = mutableListOf<Map<String, List<Prop>>>() }

    /** Every occurrence from 90 days back to 240 ahead, like the web app keeps. */
    fun events(ics: String, now: Long = System.currentTimeMillis()): List<CalEvent> {
        val ms = now.toDouble()
        return expand(parse(ics), ms - 90 * DAY, ms + 240 * DAY)
    }

    private fun parseLine(line: String): Prop? {
        var inQuotes = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes else if (c == ':' && !inQuotes) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(";")
        val params = mutableMapOf<String, String>()
        for (kv in head.drop(1)) {
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            var v = kv.substring(eq + 1)
            if (v.startsWith("\"")) v = v.drop(1)
            if (v.endsWith("\"")) v = v.dropLast(1)
            params[kv.substring(0, eq).uppercase()] = v
        }
        return Prop(head.first().uppercase(), params, line.substring(colon + 1))
    }

    fun unescape(v: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                val n = v[i + 1]
                when (n) {
                    'n', 'N' -> out.append('\n')
                    '\\', ';', ',' -> out.append(n)
                    else -> { out.append(c); out.append(n) }
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString().trim()
    }

    private fun parse(text: String): Parsed {
        val unfolded = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n ", "").replace("\n\t", "")
        val cal = Parsed()
        val stack = ArrayDeque<String>()
        var ev: MutableMap<String, MutableList<Prop>>? = null
        for (line in unfolded.split("\n")) {
            if (line.isEmpty()) continue
            val p = parseLine(line) ?: continue
            when {
                p.name == "BEGIN" -> { stack.addLast(p.value); if (p.value == "VEVENT") ev = mutableMapOf() }
                p.name == "END" -> { if (stack.removeLastOrNull() == "VEVENT") { ev?.let { cal.events.add(it) }; ev = null } }
                stack.lastOrNull() == "VEVENT" && ev != null -> ev.getOrPut(p.name) { mutableListOf() }.add(p)
                stack.lastOrNull() == "VCALENDAR" -> {
                    if (p.name == "X-WR-TIMEZONE") cal.tz = p.value.trim()
                    if (p.name == "X-WR-CALNAME") cal.name = unescape(p.value)
                }
            }
        }
        return cal
    }

    private fun prop(ev: Map<String, List<Prop>>, name: String) = ev[name]?.firstOrNull()?.value ?: ""

    private data class PDate(val allDay: Boolean, val tz: String, val parts: List<Int>, val ms: Double)

    private fun zone(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrElse { ZoneId.of("America/Los_Angeles") }
    private fun instant(p: List<Int>, tz: String): Double =
        ZonedDateTime.of(p[0], p[1], p[2], p[3], p[4], p[5], 0, zone(tz)).toInstant().toEpochMilli().toDouble()
    private fun utc(y: Int, m: Int, d: Int): Double = instant(listOf(y, m, d, 0, 0, 0), "UTC")

    private fun parseDate(p: Prop?, calTz: String): PDate? {
        if (p == null) return null
        val v = p.value.trim()
        fun n(a: Int, b: Int) = if (b <= v.length) v.substring(a, b).toIntOrNull() else null
        if (v.length < 8) return null
        val y = n(0, 4) ?: return null
        val mo = n(4, 6) ?: return null
        val d = n(6, 8) ?: return null
        if (p.params["VALUE"] == "DATE" || v.length == 8) return PDate(true, "UTC", listOf(y, mo, d, 0, 0, 0), utc(y, mo, d))
        if (v.length < 15 || v[8] != 'T') return null
        val h = n(9, 11) ?: return null
        val mi = n(11, 13) ?: return null
        val s = n(13, 15) ?: return null
        val parts = listOf(y, mo, d, h, mi, s)
        if (v.length == 16 && v[15] == 'Z') return PDate(false, "UTC", parts, instant(parts, "UTC"))
        val tz = p.params["TZID"] ?: calTz
        return runCatching { PDate(false, tz, parts, instant(parts, tz)) }.getOrNull()
    }

    private val DOW = mapOf("SU" to 0, "MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6)

    private fun utcDate(dayMs: Double): LocalDate = Instant.ofEpochMilli(dayMs.toLong()).atZone(ZoneOffset.UTC).toLocalDate()
    private fun utcWeekday(dayMs: Double): Int = utcDate(dayMs).dayOfWeek.value % 7 // Sunday = 0
    private fun daysIn(y: Int, m: Int): Int = LocalDate.of(y, m, 1).lengthOfMonth()

    /** Occurrence start instants. Steps in wall-clock days, so a 6 pm meeting stays at 6 pm across DST. */
    private fun occurrences(start: PDate, rule: Map<String, String>, from: Double, to: Double): List<Double> {
        val (y, mo, d, h, mi, s) = start.parts
        val baseDay = utc(y, mo, d)
        val until = rule["UNTIL"]?.let { parseDate(Prop("", emptyMap(), it), "UTC")?.ms } ?: Double.POSITIVE_INFINITY
        val count = rule["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
        val interval = max(1, rule["INTERVAL"]?.toIntOrNull() ?: 1)
        fun at(day: Double): Double {
            if (start.allDay) return day
            val u = utcDate(day)
            return instant(listOf(u.year, u.monthValue, u.dayOfMonth, h, mi, s), start.tz)
        }
        val freq = rule["FREQ"] ?: ""
        val period = if (freq == "WEEKLY") 7.0 else if (freq == "DAILY") 1.0 else 0.0
        var i = if (period > 0 && count == Int.MAX_VALUE) max(0, floor((from - baseDay) / (period * interval * DAY)).toInt() - 1) else 0
        var n = 0
        val out = mutableListOf<Double>()
        repeat(3000) {
            val days: List<Double> = when (freq) {
                "WEEKLY" -> {
                    val weekStart = baseDay - utcWeekday(baseDay) * DAY + (i * interval * 7) * DAY
                    val byday = rule["BYDAY"]?.split(",")?.mapNotNull { DOW[it.takeLast(2)] } ?: listOf(utcWeekday(baseDay))
                    byday.sorted().map { weekStart + it * DAY }
                }
                "DAILY" -> listOf(baseDay + (i * interval) * DAY)
                "MONTHLY", "YEARLY" -> {
                    val yy: Int; val mm: Int
                    if (freq == "MONTHLY") { val total = (mo - 1) + i * interval; yy = y + total / 12; mm = total % 12 + 1 } else { yy = y + i * interval; mm = mo }
                    if (d > daysIn(yy, mm)) emptyList() else listOf(utc(yy, mm, d)) // e.g. no Feb 30
                }
                else -> return listOf(start.ms) // unsupported rule: just the first one
            }
            i++
            for (dd in days) {
                if (dd < baseDay) continue
                val ms = at(dd)
                if (ms > until || n >= count || ms > to) return out
                n++
                out.add(ms)
            }
        }
        return out
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private val URL_RE = Regex("https?://[^\\s<>\"')]+", RegexOption.IGNORE_CASE)
    private val VIDEO_RE = Regex("zoom\\.us|meet\\.google\\.com|teams\\.microsoft\\.com|teams\\.live\\.com|webex\\.com|discord\\.(gg|com)", RegexOption.IGNORE_CASE)

    private fun urls(s: String) = URL_RE.findAll(s).map { it.value }.toList()

    private fun serviceName(link: String): String {
        val l = link.lowercase()
        return when {
            "zoom.us" in l -> "Zoom"
            "meet.google.com" in l -> "Google Meet"
            "teams.microsoft.com" in l || "teams.live.com" in l -> "Microsoft Teams"
            "webex.com" in l -> "Webex"
            "discord." in l -> "Discord"
            else -> "Meeting link"
        }
    }

    private fun meetingCode(link: String): String {
        Regex("zoom\\.us/j/(\\d{9,11})", RegexOption.IGNORE_CASE).find(link)?.let { m ->
            val n = m.groupValues[1]
            fun s(a: Int, b: Int) = n.substring(a, minOf(b, n.length))
            return if (n.length == 11) "Meeting ID ${s(0, 3)} ${s(3, 7)} ${s(7, 11)}" else "Meeting ID ${s(0, 3)} ${s(3, 6)} ${s(6, n.length)}"
        }
        Regex("meet\\.google\\.com/([a-z]{3}-[a-z]{4}-[a-z]{3})", RegexOption.IGNORE_CASE).find(link)?.let { return it.groupValues[1] }
        return ""
    }

    private val KEY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm").withZone(ZoneOffset.UTC)

    private fun expand(cal: Parsed, from: Double, to: Double): List<CalEvent> {
        // one-off edits of a repeating event (RECURRENCE-ID) replace that date of the series
        val edited = mutableMapOf<String, MutableSet<Double>>()
        for (ev in cal.events) {
            val rid = parseDate(ev["RECURRENCE-ID"]?.firstOrNull(), cal.tz) ?: continue
            edited.getOrPut(prop(ev, "UID")) { mutableSetOf() }.add(rid.ms)
        }
        val out = mutableListOf<CalEvent>()
        for (ev in cal.events) {
            if (prop(ev, "STATUS").uppercase() == "CANCELLED") continue
            val start = parseDate(ev["DTSTART"]?.firstOrNull(), cal.tz) ?: continue
            val end = parseDate(ev["DTEND"]?.firstOrNull(), cal.tz)
            val duration = end?.let { max(0.0, it.ms - start.ms) } ?: if (start.allDay) DAY else 3_600_000.0
            val uidRaw = prop(ev, "UID")
            val uid = uidRaw.ifEmpty { "${prop(ev, "SUMMARY")}-${start.ms.toLong()}" }
            val uidKey = uid.split("@").first().filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }.take(32)

            val title = unescape(prop(ev, "SUMMARY")).ifEmpty { "Untitled event" }
            val location = unescape(prop(ev, "LOCATION"))
            val description = unescape(prop(ev, "DESCRIPTION"))
            val found = urls(location) + urls(description) + listOf(prop(ev, "URL")).filter { it.isNotEmpty() }
            val link = found.firstOrNull { VIDEO_RE.containsMatchIn(it) } ?: urls(location).firstOrNull() ?: ""
            var place = URL_RE.replace(location, "").trim { it in " \t\n,;|–-" }
            place = place.replace(Regex(",\\s*USA$", RegexOption.IGNORE_CASE), "")
            val mode = if (link.isNotEmpty() && place.isNotEmpty()) "hybrid" else if (link.isNotEmpty()) "online" else if (place.isNotEmpty()) "in-person" else ""

            fun add(ms: Double, keyMs: Double) {
                if (ms + duration < from || ms > to) return
                val full = KEY_FMT.format(Instant.ofEpochMilli(keyMs.toLong()))
                val key = if (start.allDay) full.take(8) else full
                val dayKey = if (start.allDay) utcDate(ms).toString() else Day.isoAt(ms)
                out.add(CalEvent("cal-$uidKey-$key", uid, title, place, link, if (link.isEmpty()) "" else serviceName(link),
                    if (link.isEmpty()) "" else meetingCode(link), mode, description, start.allDay, ms, ms + duration, dayKey))
            }

            val rid = parseDate(ev["RECURRENCE-ID"]?.firstOrNull(), cal.tz)
            if (rid != null) { add(start.ms, rid.ms); continue }
            val rrule = prop(ev, "RRULE")
            if (rrule.isEmpty()) { add(start.ms, start.ms); continue }

            val skip = (edited[uid] ?: emptySet()).toMutableSet()
            for (p in ev["EXDATE"] ?: emptyList()) {
                for (v in p.value.split(",")) parseDate(Prop("", p.params, v), cal.tz)?.let { skip.add(it.ms) }
            }
            val rule = mutableMapOf<String, String>()
            for (kv in rrule.split(";")) {
                val pair = kv.split("=", limit = 2)
                if (pair.size == 2) rule[pair[0].uppercase()] = pair[1]
            }
            for (ms in occurrences(start, rule, from, to)) if (ms !in skip) add(ms, ms)
        }
        return out.sortedWith(compareBy<CalEvent> { it.start }.thenBy { it.title })
    }
}
