package org.systemoverload.team.data

import com.google.firebase.FirebaseApp
import org.systemoverload.team.model.*
import java.time.LocalDate
import java.time.LocalDateTime

/** Invented people and work for demo mode. Never real team members. */

/** Back to the sign-in screen from the demo, with nothing invented left behind. */
fun AppModel.leaveDemo() {
    demo = false
    demoDocs.clear(); demoComments = emptyMap()
    profile = null; uid = ""; authName = ""; authEmail = ""
    users = emptyList(); subteams = emptyList(); columns = emptyList(); allTasks = emptyList(); settings = emptyMap(); events = emptyList()
    signups = emptyList(); myMarks = emptyList(); actionItems = emptyList(); privateActionItems = emptyList(); boardChannel = emptyList()
    reports = emptyList(); blockedUids = emptySet(); boardAgendaDocs = emptyList(); teamMarks = emptyList(); roster = emptyList()
    teamReminders = emptyList(); arrivals = emptyList(); loaded = emptySet()
    phase = if (FirebaseApp.getApps(context).isEmpty()) AppModel.Phase.NotConfigured else AppModel.Phase.SignedOut
}

fun AppModel.startDemo() {
    demo = true
    uid = "demo-you"
    authName = "Alex Rivera"
    authEmail = "alex@example.com"
    authPhoto = ""
    profile = Person(uid, mapOf("name" to "Alex Rivera", "email" to "alex@example.com", "role" to "admin", "memberType" to "board", "subteamIds" to listOf("prog"), "onboardedAt" to 1))

    val people = listOf(
        Triple("demo-you", "Alex Rivera", "lead"), Triple("u-sam", "Sam Okafor", "member"), Triple("u-maya", "Maya Chen", "member"),
        Triple("u-jordan", "Jordan Patel", "member"), Triple("u-priya", "Priya Nair", "lead"), Triple("u-leo", "Leo Martins", "member"),
    )
    users = people.map { Person(it.first, mapOf("name" to it.second, "email" to "${it.first}@example.com", "role" to it.third, "onboardedAt" to 1)) }

    val teams = listOf(
        Triple("prog", "Programming", "#34C759"), Triple("elec", "Electrical", "#FFCC00"), Triple("vision", "Vision", "#A2845E"),
        Triple("cad", "Design", "#007AFF"), Triple("mech", "Manufacturing", "#FF9500"), Triple("biz", "Business", "#AF52DE"), Triple("media", "Outreach", "#FF2D55"),
    )
    subteams = teams.mapIndexed { i, t -> Subteam(t.first, mapOf("name" to t.second, "color" to t.third, "position" to i.toDouble())) }
    columns = listOf(Triple("todo", "To Do", 0.0), Triple("doing", "In Progress", 1.0), Triple("done", "Done", 2.0))
        .map { BoardColumn(it.first, mapOf("name" to it.second, "position" to it.third)) } +
        BoardColumn("grants", mapOf("name" to "Grants", "position" to -1.0, "subteamIds" to listOf("biz")))
    val inTwo = LocalDateTime.now().plusDays(2)
    teamReminders = listOf(TeamReminder("n1", mapOf("text" to "Bring the budget draft to the Board meeting", "track" to "board", "date" to inTwo.toLocalDate().toString(),
        "time" to "16:00", "sendAt" to inTwo.atZone(Day.zone).toInstant().toEpochMilli(), "createdBy" to "demo-you")))

    fun day(n: Int) = Day.inDays(n.toLong())
    data class Seed(val title: String, val board: String, val col: String, val who: List<String>, val due: Int?, val pri: String, val list: List<Pair<String, Boolean>>)
    val seed = listOf(
        Seed("Tune swerve PID on the practice bot", "prog", "doing", listOf("demo-you", "u-sam"), 2, "high", listOf("Log module angles" to true, "Retune drive kP" to false, "Test on carpet" to false)),
        Seed("Auto path for the 3-piece routine", "prog", "todo", listOf("demo-you"), 6, "med", emptyList()),
        Seed("Vision pose estimate into odometry", "vision", "doing", listOf("u-maya", "demo-you"), -1, "critical", emptyList()),
        Seed("Wire the new CANivore bus", "elec", "todo", listOf("u-jordan"), 4, "med", listOf("Crimp connectors" to false)),
        Seed("Intake roller v3 in CAD", "cad", "doing", listOf("u-priya"), 9, "med", emptyList()),
        Seed("Cut gussets on the router", "mech", "todo", listOf("u-leo"), 3, "low", emptyList()),
        Seed("Sponsor thank-you letters", "biz", "todo", listOf("demo-you"), 12, "med", emptyList()),
        Seed("Robot reveal video", "media", "todo", emptyList(), null, "med", emptyList()),
        Seed("Bench test the climber", "mech", "done", listOf("u-leo", "demo-you"), -3, "med", emptyList()),
        Seed("Community foundation grant application", "biz", "grants", listOf("u-priya"), 21, "high", listOf("Draft budget" to true, "Mentor review" to false, "Submit" to false)),
    )
    demoDocs.clear()
    seed.forEachIndexed { i, s ->
        demoDocs["t$i"] = mapOf(
            "title" to s.title, "subteamId" to s.board, "columnId" to s.col, "assigneeUids" to s.who, "dueDate" to (s.due?.let { day(it) } ?: ""),
            "priority" to s.pri, "checklist" to s.list.map { mapOf("text" to it.first, "done" to it.second) }, "position" to (i * 1000).toDouble(),
            "createdBy" to "demo-you", "createdAt" to 0, "commentCount" to if (i == 0) 1 else 0,
        )
    }
    // A team goal made of three of those tasks, and a subteam priority
    demoDocs["g1"] = mapOf("title" to "Drivetrain ready for week 0", "subteamId" to "goals", "columnId" to "doing", "isGoal" to true, "childIds" to listOf("t0", "t3", "t8"),
        "dueDate" to day(10), "priority" to "high", "position" to 0.0, "createdBy" to "demo-you", "createdAt" to 0)
    demoDocs["g2"] = mapOf("title" to "Sponsor packet sent", "subteamId" to "biz", "columnId" to "todo", "isGoal" to true, "childIds" to listOf("t6"),
        "dueDate" to day(16), "priority" to "med", "position" to 1.0, "createdBy" to "demo-you", "createdAt" to 0)
    demoComments = mapOf("t0" to listOf(Comment("c1", "u-sam", "Drive kP feels high on carpet. Let's try 0.04 after school.", (nowMs - 3_600_000).toDouble())))
    allTasks = demoDocs.map { TaskItem(it.key, it.value) }

    settings = mapOf("calendar" to mapOf("ics" to demoICS()), "leadership" to mapOf("boardEmails" to listOf("alex@example.com")))
    val now = nowMs.toDouble()
    boardChannel = listOf(
        ChannelMessage("m1", mapOf("text" to "Kickoff venue: the library said yes for the 9th.", "authorId" to "u-priya", "authorName" to "Priya Nair", "createdAt" to now - 86_400_000)),
        ChannelMessage("m2", mapOf("text" to "Great. I'll put it on the calendar tonight.", "authorId" to "demo-you", "authorName" to "Alex Rivera", "createdAt" to now - 80_000_000)),
    )
    boardAgendaDocs = listOf(AgendaDoc("bd1", mapOf("track" to "board", "date" to day(3), "startTime" to "17:30", "items" to listOf(
        mapOf("topic" to "Action items check-in", "points" to "Review open items\nMeeting recap", "who" to "All", "minutes" to 20, "urgency" to "high"),
        mapOf("topic" to "Kickoff plans", "points" to "Venue, food, schedule", "who" to "Board", "minutes" to 15, "urgency" to "medium"),
    ))))
    actionItems = listOf(
        ActionItem("a1", mapOf("text" to "Confirm the kickoff venue", "ownerUids" to listOf("demo-you"), "dueDate" to day(4), "track" to "board", "assignedOn" to day(-2), "seasonId" to Season.HOME)),
        ActionItem("a2", mapOf("text" to "Order the spare swerve modules", "ownerUids" to listOf("u-jordan", "demo-you"), "dueDate" to day(7), "track" to "build", "assignedOn" to day(-1), "seasonId" to Season.HOME)),
    )
    privateActionItems = listOf(ActionItem("p1", mapOf("text" to "Draft next year's budget", "ownerUids" to listOf("demo-you"), "dueDate" to day(12), "track" to "board", "assignedOn" to day(-2), "seasonId" to Season.HOME), confidential = true))
    roster = listOf(
        listOf("r1", "Alex Rivera", "student", "demo-you"), listOf("r2", "Sam Okafor", "student", "u-sam"), listOf("r3", "Casey Brooks", "student", ""),
        listOf("r4", "Dana Brooks", "parent", ""), listOf("r5", "Morgan Lee", "mentor", ""),
    ).map { RosterPerson(it[0], mapOf("name" to it[1], "kind" to it[2], "uid" to it[3], "female" to (it[0] == "r4"))) }
    val names = listOf("demo-you" to "Alex Rivera", "u-sam" to "Sam Okafor", "u-maya" to "Maya Chen", "u-jordan" to "Jordan Patel", "u-leo" to "Leo Martins")
    teamMarks = (0 until 8).flatMap { w ->
        names.mapIndexed { i, n ->
            val st = if ((w + i) % 5 == 0) "partial" else if ((w * i) % 7 == 3) "unexcused" else "present"
            Mark("mk$w$i", mapOf("meetingId" to "demo-$w", "date" to day(-7 * (8 - w)), "title" to "Hangar Build Session", "uid" to n.first, "name" to n.second, "kind" to "student", "status" to st))
        }
    }
    events = TeamCalendar.events(demoICS())
    // A few people going to what's coming up, adults included, so Signups looks like a real week
    data class Going(val uid: String, val name: String, val kind: String, val female: Boolean)
    val going = listOf(Going("u-sam", "Sam Okafor", "student", false), Going("u-maya", "Maya Chen", "student", false),
        Going("demo-you", "Alex Rivera", "student", false), Going("u-pat", "Pat Brooks", "parent", true), Going("u-lee", "Morgan Lee", "mentor", false))
    signups = events.filter { it.end > now && !it.allDay }.take(5).flatMapIndexed { i, e ->
        going.take(if (i == 0) 5 else 2 + i % 3).map { p ->
            Signup("${e.id}__${p.uid}", mapOf("meetingId" to e.id, "uid" to p.uid, "name" to p.name, "kind" to p.kind, "femaleAdult" to p.female, "date" to e.dayKey))
        }
    }
    loadBlocked()
    reports = listOf(Report("rp1", mapOf("kind" to "comment", "path" to "tasks/t0/comments/c1", "taskId" to "t0", "authorUid" to "u-sam", "authorName" to "Sam Okafor",
        "text" to "Drive kP feels high on carpet. Let's try 0.04 after school.", "reason" to ReportReason.Spam.label,
        "note" to "Example report: nothing wrong with this one.", "reporterName" to "Maya Chen", "status" to "open", "createdAt" to now - 7_200_000)))
    loaded = setOf("users", "subteams", "columns", "tasks", "settings", "calendar", "actionItems", "privateActionItems", "boardChannel", "teamAttendance",
        "signups", "attendance", "agendas", "roster", "teamReminders", "reports", "meetingAgendas", "boardAgendas")
    phase = AppModel.Phase.Ready
}

/** A small ICS feed relative to today, so the real parser and repeat rules run in the demo. */
fun demoICS(): String {
    val today = LocalDate.now()
    fun stamp(d: LocalDate, h: Int, m: Int = 0) = "%04d%02d%02dT%02d%02d00".format(d.year, d.monthValue, d.dayOfMonth, h, m)
    fun dateOnly(d: LocalDate) = "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)
    val hour = LocalDateTime.now().hour
    val weekday = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[today.dayOfWeek.value - 1]
    val comp = today.plusDays(23)
    return """
BEGIN:VCALENDAR
X-WR-CALNAME:SOR Team Calendar
X-WR-TIMEZONE:America/Los_Angeles
BEGIN:VEVENT
UID:demo-hangar@example.com
DTSTART;TZID=America/Los_Angeles:${stamp(today, maxOf(0, hour - 1))}
DTEND;TZID=America/Los_Angeles:${stamp(today, minOf(23, hour + 2))}
RRULE:FREQ=WEEKLY;BYDAY=$weekday
SUMMARY:Hangar Build Session
LOCATION:Example Hangar, 100 Airport Way, Springfield, CA 94000, USA
END:VEVENT
BEGIN:VEVENT
UID:demo-ops@example.com
DTSTART;TZID=America/Los_Angeles:${stamp(today.plusDays(2), 19)}
DTEND;TZID=America/Los_Angeles:${stamp(today.plusDays(2), 20)}
RRULE:FREQ=WEEKLY;INTERVAL=2
SUMMARY:Operations Meeting
LOCATION:https://zoom.us/j/12345678901
END:VEVENT
BEGIN:VEVENT
UID:demo-comp@example.com
DTSTART;VALUE=DATE:${dateOnly(comp)}
DTEND;VALUE=DATE:${dateOnly(comp.plusDays(3))}
SUMMARY:Valley Robotics Showdown
LOCATION:Example Arena, Springfield, CA
END:VEVENT
END:VCALENDAR
""".trim()
}
