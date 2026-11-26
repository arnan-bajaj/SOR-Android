package org.systemoverload.team.data

import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import org.systemoverload.team.model.*
import java.util.UUID
import kotlin.math.max

// Shifts, signups, attendance, action items, approvals, archive: AppModel+Team in the iPhone app.

fun AppModel.shift(id: String): Shift? {
    val doc = meetingExtras[id]
    val cal = events.firstOrNull { it.id == id }
    if (doc == null && cal == null) return null
    val place = doc?.get("place") as? String ?: cal?.place ?: ""
    val online = doc?.get("online") as? Boolean ?: (cal?.mode == "online")
    val cancelled = doc?.bool("cancelled") ?: false
    val counted = (doc?.get("counted") as? Boolean ?: true) && !cancelled
    val weight = doc?.num("weight")?.takeIf { it > 0 } ?: 1.0
    return Shift(
        id, cal, cal?.title ?: doc?.str("title")?.ifEmpty { null } ?: "Meeting", cal?.dayKey ?: doc?.str("date") ?: "",
        if (cal?.allDay == true) 0.0 else cal?.start ?: 0.0, if (cal?.allDay == true) 0.0 else cal?.end ?: 0.0,
        place, online, cal?.allDay ?: false, cancelled, counted, weight,
    )
}

/** Everything you could sign up for, from today on, up to `weeks` ahead (all-day events too). */
fun AppModel.upcomingShifts(weeks: Int): List<Shift> {
    val today = Day.iso()
    val horizon = Day.inDays(weeks * 7L)
    val now = nowMs.toDouble()
    val ids = events.filter { (it.dayKey >= today && it.dayKey <= horizon) || (it.allDay && it.end > now && it.dayKey <= horizon) }.map { it.id }.toMutableList()
    // Shifts added on the website that aren't on the calendar
    for ((id, d) in meetingExtras) if (d.str("date") >= today && d.str("date") <= horizon && id !in ids && d.str("calendarEventId").isEmpty()) ids.add(id)
    return ids.mapNotNull { shift(it) }.sortedWith(compareBy<Shift> { it.date }.thenBy { it.start })
}

fun AppModel.signupsFor(id: String) = signups.filter { it.meetingId == id }
fun AppModel.amGoing(id: String) = signups.any { it.meetingId == id && it.uid == uid }

fun AppModel.coverage(sh: Shift, list: List<Signup>): Coverage {
    if (!sh.inPerson) return Coverage("")
    val adults = list.filter { it.isAdult }
    val short = max(0, 2 - adults.size)
    val noFemale = adults.none { it.femaleAdult }
    if (short > 0 && noFemale) return Coverage(if (short > 1) "Needs $short adults, one female" else "Needs 1 more adult, female")
    if (short > 0) return Coverage("Needs $short more adult${if (short > 1) "s" else ""}")
    if (noFemale) return Coverage("Needs a female adult")
    return Coverage("")
}

/** Who you are for signups (me() in mod-shifts). */
val AppModel.signupKind: String get() {
    val t = profile?.memberType ?: ""
    if (t in listOf("parent", "mentor")) return t
    if (t in listOf("student", "lead", "board")) return "student"
    return if (canEdit) "student" else "parent"
}
/** Students sign up in Slack; parents and mentors sign up here. */
val AppModel.signsUpInApp: Boolean get() = signupKind != "student"

private fun AppModel.signupDoc(sh: Shift, who: String, name: String, kind: String, female: Boolean): MutableMap<String, Any?> = mutableMapOf(
    "meetingId" to sh.id, "uid" to who, "name" to name, "kind" to kind, "femaleAdult" to female, "leader" to false,
    "title" to sh.title, "date" to sh.date, "start" to sh.start, "end" to sh.end, "place" to sh.place,
    "online" to sh.online, "inPerson" to sh.inPerson, "seasonId" to currentSeason.id, "at" to nowMs, "by" to uid,
)

/** The same document the website's toggleSignup writes. */
suspend fun AppModel.toggleSignup(sh: Shift) {
    val id = "${sh.id}__$uid"
    try {
        if (amGoing(sh.id)) {
            if (demo) { signups = signups.filter { it.id != id }; return }
            db.document("signups/$id").delete().await()
        } else {
            val adult = signupKind != "student"
            val data = signupDoc(sh, uid, profile?.name ?: authName, signupKind, adult && (myFemale ?: false))
            if (demo) { signups = signups + Signup(id, data); return }
            db.document("signups/$id").set(data).await()
        }
    } catch (e: Exception) { toast = AppModel.friendly(e) }
}

/** Leads take someone off a shift. */
fun AppModel.removeSignup(s: Signup) {
    if (demo) { signups = signups.filter { it.id != s.id }; return }
    db.document("signups/${s.id}").delete()
        .addOnSuccessListener { toast = "Took ${s.name} off this shift" }
        .addOnFailureListener { toast = AppModel.friendly(it) }
}

// Your attendance

/** Your marks in the open season, newest first, without Board meetings (they take no attendance). */
val AppModel.mySeasonMarks: List<Mark> get() {
    val from = seasonFrom; val to = currentSeason.end
    return myMarks.filter { it.date >= from && it.date <= to }
        .filter { m -> !(shift(m.meetingId)?.isBoardMeeting ?: BOARD_RE.containsMatchIn(m.title)) }
        .sortedByDescending { it.date }
}

/** scoreOf in mod-shifts: TRUNC(online/3 + complete, 2). Present 1, Partial 0.5, only counted shifts score, multiplier applies. */
fun AppModel.score(marks: List<Mark>, skipBoard: Boolean): Score {
    val out = Score()
    var online = 0.0; var complete = 0.0
    for (m in marks) {
        val st = MarkStatus.of(m.status) ?: continue
        val sh = shift(m.meetingId)
        if (skipBoard && sh?.isBoardMeeting == true) continue
        if (st == MarkStatus.excused) out.excused++
        if (st == MarkStatus.unexcused) out.unexcused++
        if (!(sh?.counted ?: true)) continue
        out.counted++
        if (st == MarkStatus.partial) out.partial++
        if (st == MarkStatus.unproductive) out.unproductive++
        if (st == MarkStatus.present) out.present++
        val worth = st.value * (sh?.weight ?: 1.0)
        if (sh?.online ?: m.online) online += worth else complete += worth
    }
    out.score = kotlin.math.floor((online / 3 + complete) * 100) / 100
    return out
}
val AppModel.myScore: Score get() = score(mySeasonMarks, skipBoard = false)

// Action items

fun AppModel.items(track: Track): List<ActionItem> {
    val sid = currentSeason.id
    return actionItems.filter { a ->
        a.seasonId == sid && (a.track == track.name || (a.track.isEmpty() && events.firstOrNull { it.id == a.meetingId }?.track == track.name))
    }
}
val AppModel.boardRoomItems: List<ActionItem> get() = privateActionItems.filter { it.seasonId == currentSeason.id }
val AppModel.myOpenActionItems: List<ActionItem> get() = actionItems
    .filter { it.seasonId == currentSeason.id && !it.done && uid in it.ownerUids }
    .sortedWith(compareBy<ActionItem> { it.dueDate.isEmpty() }.thenBy { it.dueDate })
fun AppModel.canChange(a: ActionItem) = if (a.confidential) isBoardMember else canEdit
fun AppModel.canRemove(a: ActionItem) = if (a.confidential) isBoardMember else (isLead || (canEdit && a.createdBy == uid))

fun AppModel.setDone(a: ActionItem, done: Boolean) = patch(a, mapOf("done" to done))

fun AppModel.patch(a: ActionItem, p: Map<String, Any?>) {
    if (demo) {
        val base: Doc = mapOf("text" to a.text, "ownerUids" to a.ownerUids, "dueDate" to a.dueDate, "done" to a.done, "track" to a.track,
            "assignedOn" to a.assignedOn, "createdBy" to a.createdBy, "seasonId" to a.seasonId, "reminders" to a.reminders,
            "promotedTaskId" to a.promotedTaskId, "meetingId" to a.meetingId, "createdAt" to a.createdAt)
        val item = ActionItem(a.id, base + p, a.confidential)
        if (a.confidential) privateActionItems = privateActionItems.map { if (it.id == a.id) item else it }
        else actionItems = actionItems.map { if (it.id == a.id) item else it }
        return
    }
    db.document(a.path).update(p).addOnFailureListener { toast = AppModel.friendly(it) }
}

fun AppModel.deleteItem(a: ActionItem) {
    if (demo) { actionItems = actionItems.filter { it.id != a.id }; privateActionItems = privateActionItems.filter { it.id != a.id }; return }
    db.document(a.path).delete()
        .addOnSuccessListener { toast = "Deleted “${a.text}”" }
        .addOnFailureListener { toast = AppModel.friendly(it) }
}

/** Assigned at the nearest meeting of that kind today, like the Action Items page does. */
suspend fun AppModel.addActionItem(text: String, track: Track, owners: List<String>, dueDate: String, confidential: Boolean) {
    val today = Day.iso()
    val ev = nearestMeetingToday(track)
    val data = mutableMapOf<String, Any?>(
        "text" to text.trim(), "ownerUids" to owners.ifEmpty { listOf(uid) },
        "dueDate" to dueDate, "done" to false, "track" to track.name, "meetingId" to (ev?.id ?: ""), "assignedOn" to today,
        "promotedTaskId" to "", "createdBy" to uid, "createdAt" to nowMs, "seasonId" to currentSeason.id, "reminders" to listOf(90),
    )
    if (confidential) data["confidential"] = true
    if (demo) {
        val a = ActionItem(UUID.randomUUID().toString(), data, confidential)
        if (confidential) privateActionItems = privateActionItems + a else actionItems = actionItems + a
        return
    }
    db.collection(if (confidential) "privateActionItems" else "actionItems").add(data).await()
}

// Hangar agendas

val AppModel.seasonAgendas: List<Agenda> get() {
    val from = seasonFrom; val to = currentSeason.end
    return agendas.filter { !it.removed && it.date >= from && it.date <= to }.sortedByDescending { it.date }
}

// Team (admins approve people)

val AppModel.pendingPeople: List<Person> get() = users.filter { it.role == Role.pending && it.onboardedAt > 0 }

/** approveRole in mod-views: what their member type is offered on approval. */
fun approveRole(p: Person): Role = when (p.memberType) { "lead", "mentor" -> Role.lead; "parent" -> Role.viewer; else -> Role.member }

fun AppModel.setRole(p: Person, role: Role) {
    if (demo) { users = users.map { if (it.id == p.id) it.copy(role = role) else it }; return }
    db.document("users/${p.id}").update("role", role.name)
        .addOnSuccessListener { toast = "${p.firstName} is in as ${role.label.lowercase()}" }
        .addOnFailureListener { toast = AppModel.friendly(it) }
}

fun AppModel.decline(p: Person) {
    if (demo) { users = users.filter { it.id != p.id }; return }
    db.document("users/${p.id}").delete()
        .addOnSuccessListener { toast = "Declined ${p.firstName}" }
        .addOnFailureListener { toast = AppModel.friendly(it) }
}

val AppModel.archivedTasks: List<TaskItem> get() = allTasks.filter { it.archived && it.seasonId == currentSeason.id }.sortedByDescending { it.updatedAt }

// Board Room

fun AppModel.wantBoardRoom() {
    if (!canSeeBoardRoom) return
    lazyListen("boardChannel", db.collection("boardChannel")) { d -> boardChannel = d.map { ChannelMessage(it.id, it.data ?: emptyMap()) }.sortedBy { it.createdAt } }
    lazyListen("boardAgendas", db.collection("boardAgendas")) { d -> boardAgendaDocs = d.map { AgendaDoc(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.date } }
    lazyListen("boardMeetingDocs", db.collection("boardMeetingDocs")) { d -> boardGoogleDocs = d.map { GoogleDoc(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.date } }
}

/** Only the Board posts; lead mentors read. */
suspend fun AppModel.postToBoard(text: String) {
    val t = text.trim()
    if (t.isEmpty() || !isBoardMember) return
    val msg = mapOf("text" to t, "authorId" to uid, "authorName" to (profile?.name ?: ""), "createdAt" to nowMs)
    if (demo) { boardChannel = boardChannel + ChannelMessage(UUID.randomUUID().toString(), msg); return }
    try { db.collection("boardChannel").add(msg).await() } catch (e: Exception) { toast = AppModel.friendly(e) }
}

/** Moves the whole team to another season (switchTeamSeason in mod-state), with a line in the Board channel. */
suspend fun AppModel.switchSeason(season: Season) {
    if (!canSeeBoardRoom) return
    val at = nowMs
    val name = profile?.name ?: ""
    val batch = db.batch()
    batch.set(db.document("settings/season"), mapOf("activeId" to season.id, "since" to Day.iso(), "by" to uid, "byName" to name, "at" to at))
    batch.set(db.collection("boardChannel").document(), mapOf("kind" to "season", "seasonId" to season.id, "seasonName" to season.name,
        "text" to "$name switched the team to ${season.name}", "authorId" to uid, "authorName" to name, "createdAt" to at))
    try { batch.commit().await(); toast = "The team is on ${season.name}" } catch (e: Exception) { toast = AppModel.friendly(e) }
}

// Agenda documents (Action Items)

fun AppModel.wantAgendaDocs() {
    lazyListen("meetingAgendas", db.collection("meetingAgendas")) { d -> agendaDocs = d.map { AgendaDoc(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.date } }
    lazyListen("meetingDocs", db.collection("meetingDocs")) { d -> googleDocs = d.map { GoogleDoc(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.date } }
    if (canSeeBoardRoom) wantBoardRoom()
}

fun AppModel.docsFor(track: Track): Pair<List<AgendaDoc>, List<GoogleDoc>> =
    if (track == Track.board) boardAgendaDocs to boardGoogleDocs
    else agendaDocs.filter { it.track == track.name } to googleDocs.filter { it.track == track.name }

// The team's attendance (Analytics)

fun AppModel.wantTeamAttendance() {
    lazyListen("teamAttendance", db.collection("attendance").whereGreaterThanOrEqualTo("date", seasonFrom)) { d -> teamMarks = d.map { Mark(it.id, it.data ?: emptyMap()) } }
}

data class Standing(val id: String, val name: String, val uid: String, val score: Score, val place: Int)

/** leaderboard() in mod-shifts: students only (not adults, not the Board), counted shifts up to today. Ties share a place. */
val AppModel.leaderboard: List<Standing> get() {
    val today = Day.iso(); val end = currentSeason.end; val from = seasonFrom
    val marks = teamMarks.filter { it.date >= from && it.date <= end && it.date <= today && it.kind !in listOf("parent", "mentor") }
    val people = linkedMapOf<String, Triple<String, String, MutableList<Mark>>>()
    for (m in marks) {
        val key = m.uid.ifEmpty { "r-${m.rosterId}" }
        if (key == "r-") continue
        people.getOrPut(key) { Triple(m.name, m.uid, mutableListOf()) }.third.add(m)
    }
    val board = users.filter { it.memberType == "board" }.map { it.id }.toSet()
    val rows = people.filter { it.value.second !in board }.map { (key, v) ->
        Standing(key, person(v.second)?.name ?: v.first, v.second, score(v.third, skipBoard = true), 0)
    }.sortedWith(compareByDescending<Standing> { it.score.score }.thenBy { it.score.unexcused }.thenBy { it.name })
    var place = 0
    return rows.mapIndexed { i, r ->
        place = if (i > 0 && rows[i - 1].score.score == r.score.score) place else i + 1
        r.copy(place = place)
    }
}

// Helper chats (reviewers) and signup records (leads)

suspend fun AppModel.checkHelperReviewer() {
    if (demo) return
    val d = runCatching { db.document("assistantAccess/reviewers").get().await().data }.getOrNull() ?: return
    isHelperReviewer = uid in d.strs("uids")
}

suspend fun AppModel.helperLogs(limit: Long): List<HelperLog> =
    db.collection("assistantLogs").orderBy("at", Query.Direction.DESCENDING).limit(limit).get().await().documents.map { HelperLog(it.id, it.data ?: emptyMap()) }

/** The bot's text log of #meeting-signups, oldest first. Names students, so only leads can read it. */
suspend fun AppModel.signupLogText(): String? {
    val docs = db.collection("signupLog").get().await().documents.sortedBy { it.id }
    if (docs.isEmpty()) return null
    val head = "SOR Team Board: #meeting-signups log\nDownloaded ${Day.fmtMs(nowMs.toDouble(), "MMM d, yyyy h:mm a")} by ${profile?.name ?: ""}. ${docs.size} entries, oldest first.\n"
    return head + "\n" + docs.joinToString("\n\n") { (it.data ?: emptyMap()).str("text") } + "\n"
}

/** The newest weekly export of every signup and attendance mark. */
suspend fun AppModel.latestExportText(): Pair<String, String>? {
    val all = db.collection("signupExports").get().await().documents
    val latest = all.maxByOrNull { (it.data ?: emptyMap()).str("day") } ?: return null
    val day = (latest.data ?: emptyMap()).str("day").ifEmpty { latest.id }
    val parts = db.collection("signupExports/$day/parts").get().await().documents
        .sortedBy { ((it.data ?: emptyMap())["i"] as? Number)?.toInt() ?: it.id.toIntOrNull() ?: 0 }
    return day to parts.joinToString("\n") { (it.data ?: emptyMap()).str("text") }
}

// Timeline

data class Goal(val task: TaskItem, val parts: List<TaskItem>, val late: Boolean, val done: Int)

/** The Pinboard's big notes: team goals, plus subteam priorities that aren't part of a team goal, in date order. */
val AppModel.pinboardGoals: List<Goal> get() {
    val live = tasks
    val goals = live.filter { it.isGoal }
    val inTeamGoal = goals.filter { it.subteamId == "goals" }.flatMap { it.childIds }.toSet()
    val fortnight = nowMs - 14 * 86_400_000.0
    val today = Day.iso()
    return goals
        .filter { (it.subteamId == "goals" || it.id !in inTeamGoal) && (!isDone(it) || it.updatedAt > fortnight) }
        .sortedWith(compareBy<TaskItem> { it.dueDate.ifEmpty { "9999" } }.thenBy { it.position })
        .map { g ->
            val parts = g.childIds.mapNotNull { id -> live.firstOrNull { it.id == id } }
            Goal(g, parts, !isDone(g) && g.dueDate.isNotEmpty() && g.dueDate < today, parts.count { isDone(it) })
        }
}

/** Running late: past its date, or waiting on something unfinished that's due after it (the Schedule's forecast, simplified). */
fun AppModel.isLate(t: TaskItem): Boolean {
    if (isDone(t) || t.effectiveDue.isEmpty()) return false
    if (t.effectiveDue < Day.iso()) return true
    return t.blockedBy.mapNotNull { task(it) }.any { !isDone(it) && it.effectiveDue.isNotEmpty() && it.effectiveDue >= t.effectiveDue }
}
