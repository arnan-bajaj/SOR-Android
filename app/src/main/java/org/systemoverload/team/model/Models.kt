package org.systemoverload.team.model

import androidx.compose.ui.graphics.Color
import org.systemoverload.team.ui.Brand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A Firestore document as the web app writes it. Old documents miss fields, so every read has a default. */
typealias Doc = Map<String, Any?>

fun Doc.str(k: String): String = this[k] as? String ?: ""
fun Doc.num(k: String): Double = (this[k] as? Number)?.toDouble() ?: 0.0
fun Doc.bool(k: String): Boolean = this[k] as? Boolean ?: false
fun Doc.strs(k: String): List<String> = (this[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
@Suppress("UNCHECKED_CAST")
fun Doc.docs(k: String): List<Doc> = (this[k] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
@Suppress("UNCHECKED_CAST")
fun Doc.map(k: String): Doc? = this[k] as? Map<String, Any?>
fun Doc.ints(k: String): List<Int> = (this[k] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

/** Milliseconds since 1970, the way the web app stores every time (`Date.now()`). */
val nowMs: Long get() = System.currentTimeMillis()

// People

/** The roles, lowest first (users/{uid}.role). Same order as ROLES in mod-state. */
enum class Role(val label: String) {
    pending("Waiting for approval"), viewer("Viewer"), member("Member"), lead("Lead"), admin("Admin");
    val rank: Int get() = ordinal
    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } ?: pending }
}

data class Person(
    val id: String, val name: String, val email: String, val photoURL: String, val role: Role,
    val memberType: String, val subteamIds: List<String>, val onboardedAt: Double, val slackId: String,
) {
    constructor(id: String, d: Doc) : this(
        id, d.str("name").ifEmpty { d.str("email") }, d.str("email"), d.str("photoURL"), Role.of(d.str("role")),
        d.str("memberType"), d.strs("subteamIds"), d.num("onboardedAt"), d.str("slackId"),
    )
    val firstName: String get() = name.split(" ").firstOrNull()?.ifEmpty { null } ?: name
    val isStudent: Boolean get() = memberType !in listOf("parent", "mentor")
    val memberTypeLabel: String get() = when (memberType) {
        "student" -> "Student"; "lead" -> "Lead"; "board" -> "Board"; "mentor" -> "Mentor"; "parent" -> "Parent volunteer"
        else -> "Team member"
    }
}

// Boards

data class Subteam(val id: String, val name: String, val color: Color, val position: Double) {
    constructor(id: String, d: Doc) : this(id, d.str("name"), hexColor(d.str("color")) ?: Brand.blue, d.num("position"))
}

data class BoardColumn(val id: String, val name: String, val position: Double, val subteamIds: List<String>) {
    constructor(id: String, d: Doc) : this(id, d.str("name"), d.num("position"), d.strs("subteamIds"))
}

enum class Priority(val label: String, val rank: Int) {
    low("Low", 3), med("Medium", 2), high("High", 1), critical("Critical", 0);
    val tint: Color get() = when (this) { low -> Color.Gray; med -> Brand.blue; high -> Brand.amber; critical -> Brand.red }
    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } }
}

data class ChecklistItem(val text: String, val done: Boolean)

data class PhotoRef(val id: String, val exp: Double, val by: String)

data class TaskItem(
    val id: String, val title: String, val description: String, val columnId: String, val subteamId: String,
    val assigneeUids: List<String>, val backupUids: List<String>, val dueDate: String, val newDueDate: String,
    val childIds: List<String>, val startDate: String, val priority: Priority, val checklist: List<ChecklistItem>,
    val blockedBy: List<String>, val isGoal: Boolean, val flagged: Boolean, val archived: Boolean, val seasonId: String,
    val position: Double, val createdBy: String, val createdAt: Double, val updatedAt: Double, val commentCount: Int,
    val estHours: String, val subsystem: String, val dod: String, val reminders: List<Int>, val photos: List<PhotoRef>,
) {
    constructor(id: String, d: Doc) : this(
        id, d.str("title"), d.str("description"), d.str("columnId"), d.str("subteamId"),
        d.strs("assigneeUids"), d.strs("backupUids"), d.str("dueDate"), d.str("newDueDate"),
        d.strs("childIds"), d.str("startDate"), Priority.of(d.str("priority")) ?: Priority.med,
        d.docs("checklist").map { ChecklistItem(it.str("text"), it.bool("done")) },
        d.strs("blockedBy"), d.bool("isGoal"), d.bool("flagged"), d.bool("archived"), d.str("seasonId").ifEmpty { Season.HOME },
        d.num("position"), d.str("createdBy"), d.num("createdAt"), d.num("updatedAt"), d.num("commentCount").toInt(),
        (d["estHours"] as? Number)?.let { n -> if (n.toDouble() % 1.0 == 0.0) n.toLong().toString() else n.toString() } ?: d.str("estHours"),
        d.str("subsystem"), d.str("dod"), d.ints("reminders"),
        d.docs("photos").map { PhotoRef(it.str("id"), it.num("exp"), it.str("by")) },
    )
    /** The deadline that counts now: the second one if there is one. */
    val effectiveDue: String get() = newDueDate.ifEmpty { dueDate }
}

data class Comment(val id: String, val authorId: String, val body: String, val createdAt: Double) {
    constructor(id: String, d: Doc) : this(id, d.str("authorId"), d.str("body"), d.num("createdAt"))
}

// Seasons, arrivals

data class Season(val id: String, val name: String, val start: String, val end: String, val archived: Boolean = false) {
    constructor(id: String, d: Doc) : this(id, d.str("name"), d.str("start"), d.str("end"), d.bool("archived"))
    companion object {
        const val HOME = "offseason-2026"
        val defaults = listOf(Season(HOME, "Offseason 2026", "2026-04-01", "2027-01-08"))
    }
}

data class Arrival(val id: String, val uid: String, val name: String, val photoURL: String, val at: Double) {
    constructor(id: String, d: Doc) : this(id, d.str("uid"), d.str("name"), d.str("photoURL"), d.num("at"))
}

// Days

/** Local calendar days as "YYYY-MM-DD", like dayISO() in the web app. */
object Day {
    val zone: ZoneId get() = ZoneId.systemDefault()
    fun iso(date: LocalDate = LocalDate.now()): String = date.toString()
    fun isoAt(ms: Double): String = Instant.ofEpochMilli(ms.toLong()).atZone(zone).toLocalDate().toString()
    fun inDays(n: Long): String = LocalDate.now().plusDays(n).toString()
    fun date(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()
    /** Whole days from today to `iso` (negative = past). */
    fun until(iso: String): Int? = date(iso)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() }
    fun pretty(iso: String): String {
        val d = date(iso) ?: return iso
        return when (val n = until(iso) ?: 99) {
            0 -> "Today"; 1 -> "Tomorrow"; -1 -> "Yesterday"
            in 2..6 -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> fmt(d, "MMM d")
        }
    }
    fun fmt(d: LocalDate, pattern: String): String = d.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    fun fmt(iso: String, pattern: String): String = date(iso)?.let { fmt(it, pattern) } ?: iso
    fun fmtMs(ms: Double, pattern: String): String =
        Instant.ofEpochMilli(ms.toLong()).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    /** "5 min ago", "yesterday" */
    fun relative(ms: Double): String {
        val diff = (System.currentTimeMillis() - ms) / 1000
        return when {
            diff < 60 -> "now"
            diff < 3600 -> "${(diff / 60).toInt()} min ago"
            diff < 86400 -> "${(diff / 3600).toInt()} hr ago"
            diff < 2 * 86400 -> "yesterday"
            diff < 7 * 86400 -> "${(diff / 86400).toInt()} days ago"
            else -> fmtMs(ms, "MMM d")
        }
    }
}

fun hexColor(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6) return null
    val v = s.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

// Meetings: signups, attendance, agendas, action items

data class Signup(
    val id: String, val meetingId: String, val uid: String, val name: String, val kind: String,
    val femaleAdult: Boolean, val leader: Boolean, val rosterId: String,
) {
    constructor(id: String, d: Doc) : this(id, d.str("meetingId"), d.str("uid"), d.str("name"), d.str("kind"), d.bool("femaleAdult"), d.bool("leader"), d.str("rosterId"))
    val isAdult: Boolean get() = kind == "parent" || kind == "mentor"
}

/** An attendance mark (attendance/{meetingId__who}). No document means Not present. */
data class Mark(
    val id: String, val meetingId: String, val date: String, val title: String, val online: Boolean,
    val status: String, val uid: String, val rosterId: String, val name: String, val kind: String,
) {
    constructor(id: String, d: Doc) : this(id, d.str("meetingId"), d.str("date"), d.str("title"), d.bool("online"), d.str("status"),
        d.str("uid"), d.str("rosterId"), d.str("name"), d.str("kind"))
}

/** STATUSES in mod-shifts, in the sheet's order. */
enum class MarkStatus(val label: String, val value: Double) {
    present("Present", 1.0), partial("Partial", 0.5), unproductive("Unproductive", 0.0), excused("Absent excused", 0.0), unexcused("Absent unexcused", 0.0);
    val tint: Color get() = when (this) { present -> Brand.green; partial -> Brand.yellow; unproductive -> Brand.amber; excused -> Brand.blue; unexcused -> Brand.red }
    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } }
}

data class Agenda(
    val id: String, val title: String, val body: String, val date: String, val subteamIds: List<String>,
    val authorName: String, val permalink: String, val removed: Boolean, val taskIds: List<String>,
) {
    constructor(id: String, d: Doc) : this(id, d.str("title"), d.str("body"), d.str("date"), d.strs("subteamIds"), d.str("authorName"),
        d.str("permalink"), d.bool("removed"), d.strs("taskIds"))
}

enum class Track(val label: String) {
    board("Board"), build("Build Committee"), ops("Operations");
    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } }
}

data class ActionItem(
    val id: String, val text: String, val ownerUids: List<String>, val dueDate: String, val done: Boolean, val track: String,
    val meetingId: String, val assignedOn: String, val createdBy: String, val createdAt: Double, val seasonId: String,
    val confidential: Boolean, val reminders: List<Int>, val promotedTaskId: String,
) {
    constructor(id: String, d: Doc, confidential: Boolean = false) : this(
        id, d.str("text"),
        d.strs("ownerUids").ifEmpty { if (d.str("ownerUid").isEmpty()) emptyList() else listOf(d.str("ownerUid")) },
        d.str("dueDate"), d.bool("done"), d.str("track"), d.str("meetingId"), d.str("assignedOn"), d.str("createdBy"), d.num("createdAt"),
        d.str("seasonId").ifEmpty { Season.HOME }, confidential || d.bool("confidential"), d.ints("reminders"), d.str("promotedTaskId"),
    )
    val path: String get() = "${if (confidential) "privateActionItems" else "actionItems"}/$id"
    /** assignedDay in mod-views: assignedOn, else the day it was added. */
    val day: String get() = assignedOn.ifEmpty { Day.isoAt(createdAt) }
}

// Board Room and agenda documents

data class ChannelMessage(val id: String, val text: String, val authorId: String, val authorName: String, val createdAt: Double, val kind: String) {
    constructor(id: String, d: Doc) : this(id, d.str("text"), d.str("authorId"), d.str("authorName"), d.num("createdAt"), d.str("kind"))
}

/** A Board, Build Committee or Operations agenda written on the website (boardAgendas / meetingAgendas). */
data class AgendaDoc(
    val id: String, val track: String, val date: String, val startTime: String, val recording: String, val attended: List<String>,
    val purpose: String, val takeaways: String, val topics: String, val nextSteps: String, val items: List<Item>,
) {
    data class Item(val topic: String, val points: String, val who: String, val urgency: String, val minutes: Int)
    constructor(id: String, d: Doc) : this(
        id, d.str("track"), d.str("date"), d.str("startTime"), d.str("recording"), d.strs("attended"),
        d.str("purpose"), d.str("takeaways"), d.str("topics"), d.str("nextSteps"),
        d.docs("items").map { Item(it.str("topic"), it.str("points"), it.str("who"), it.str("urgency"), it.num("minutes").toInt()) },
    )
    val totalMinutes: Int get() = items.sumOf { it.minutes }
}

/** A Google Doc agenda made by /agenda in Slack (meetingDocs / boardMeetingDocs). */
data class GoogleDoc(val id: String, val track: String, val url: String, val title: String, val date: String) {
    constructor(id: String, d: Doc) : this(id, d.str("track"), d.str("url"), d.str("title"), d.str("date"))
}

/** One message to the helper (assistantLogs), for its reviewers. */
data class HelperLog(
    val id: String, val name: String, val said: String, val reply: String, val role: String, val day: String, val error: String,
    val at: Double, val status: Int, val calls: List<String>,
) {
    constructor(id: String, d: Doc) : this(id, d.str("name"), d.str("said"), d.str("reply"), d.str("role"), d.str("day"), d.str("error"),
        d.num("at"), d.num("status").toInt(), d.docs("calls").map { it.str("name").replace("_", " ") })
}

/** Someone on the team's roster (roster/{id}): students, parents and mentors, with or without a board account. */
data class RosterPerson(val id: String, val name: String, val kind: String, val uid: String, val aliases: List<String>, val active: Boolean, val female: Boolean) {
    constructor(id: String, d: Doc) : this(id, d.str("name"), d.str("kind"), d.str("uid"), d.strs("aliases"), d["active"] as? Boolean ?: true, d.bool("female"))
}

/** A note the Slack bot posts in the Board's or Build Committee's channel on a date (teamReminders/{id}). */
data class TeamReminder(
    val id: String, val text: String, val track: String, val date: String, val time: String, val createdBy: String,
    val sendAt: Double, val sentAt: Double,
) {
    constructor(id: String, d: Doc) : this(id, d.str("text"), d.str("track"), d.str("date"), d.str("time"), d.str("createdBy"), d.num("sendAt"), d.num("sentAt"))
}

/** Something a person reported (reports/{id}): anyone approved files one, only admins read and resolve them. */
data class Report(
    val id: String, val kind: String, val path: String, val taskId: String, val authorUid: String, val authorName: String,
    val text: String, val reason: String, val note: String, val reporterName: String, val status: String, val createdAt: Double,
    val resolvedByName: String,
) {
    constructor(id: String, d: Doc) : this(id, d.str("kind"), d.str("path"), d.str("taskId"), d.str("authorUid"), d.str("authorName"),
        d.str("text"), d.str("reason"), d.str("note"), d.str("reporterName"), d.str("status").ifEmpty { "open" }, d.num("createdAt"), d.str("resolvedByName"))
    val isOpen: Boolean get() = status == "open"
    val kindLabel: String get() = mapOf("comment" to "Comment", "photo" to "Photo", "message" to "Board channel message", "task" to "Task")[kind] ?: "Content"
}

/** What the Report sheet is about. */
data class ReportTarget(val kind: String, val path: String, val taskId: String = "", val authorUid: String, val text: String)

enum class ReportReason(val label: String) {
    Offensive("Offensive or inappropriate"), Harassment("Harassment or bullying"), PrivateInfo("Shares private information"),
    Spam("Spam or off-topic"), Other("Something else"),
}

/** A meeting as Signups and Attendance see it (shiftOf in mod-shifts): the calendar event plus meetings/{id}. */
data class Shift(
    val id: String, val event: CalEvent?, val title: String, val date: String, val start: Double, val end: Double, val place: String,
    val online: Boolean, val allDay: Boolean, val cancelled: Boolean, val counted: Boolean, val weight: Double,
) {
    val inPerson: Boolean get() = !online && place.isNotEmpty()
    val isBoardMeeting: Boolean get() = event?.track == "board" || BOARD_RE.containsMatchIn(title)
}

val BOARD_RE = Regex("board (meeting|mtg)", RegexOption.IGNORE_CASE)

data class Score(
    var score: Double = 0.0, var present: Int = 0, var partial: Int = 0, var unproductive: Int = 0,
    var excused: Int = 0, var unexcused: Int = 0, var counted: Int = 0,
)

/** Adult cover for a shift (coverage in mod-shifts): 2 adults, at least 1 female, in person only. It warns, never blocks. */
data class Coverage(val label: String) { val ok: Boolean get() = label.isEmpty() }

/** The kinds of notification someone can turn off (users/{uid}.push.<kind> = false). The bot reads the same names. */
enum class PushKind(val label: String, val hint: String) {
    tasks("Put on a task", "When someone makes you an owner of a task."),
    comments("Comments on your tasks", "When someone comments on a task you own."),
    actions("Action items for you", "When you're given an action item at a meeting."),
    reminders("Due-date reminders", "Along the way to a due date, and the morning after it's missed."),
    meetings("Meetings you signed up for", "An hour before a meeting you signed up for."),
    team("Approvals", "You're approved, or (for admins) someone is waiting to be."),
}
