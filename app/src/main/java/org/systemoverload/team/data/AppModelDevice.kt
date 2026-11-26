package org.systemoverload.team.data

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.systemoverload.team.model.*
import org.systemoverload.team.widget.WidgetSnapshot
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Push, links, check-in, widget, safety, team reminders, photos, roster: the rest of the iPhone app's AppModel.

// Push notifications

/** The token is saved to pushTokens/{token}, which only the bot can read. Android 13+ asks for permission (MainActivity). */
fun AppModel.setUpPush() {
    if (demo) return
    pushAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    FirebaseMessaging.getInstance().token.addOnSuccessListener { savePushToken(it) }
}

fun AppModel.notificationsPermissionNeeded(): Boolean =
    Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Messaging hands over a token (and a new one now and then). */
fun AppModel.savePushToken(token: String) {
    if (demo || uid.isEmpty() || !canRead) { pendingPushToken = token; return }
    pushToken = token
    db.document("pushTokens/$token").set(mapOf("uid" to uid, "platform" to "android", "updatedAt" to nowMs, "app" to version))
        .addOnFailureListener { Log.w(AppModel.TAG, "push token: ${it.message}") } // the rules may not be published yet
}

/** Signing out: this phone stops getting your notifications. */
suspend fun AppModel.forgetPushToken() {
    val t = pushToken ?: return
    if (demo) return
    runCatching { db.document("pushTokens/$t").delete().await() }
    pushToken = null
}

fun AppModel.pushOn(kind: PushKind): Boolean = profileDoc.map("push")?.get(kind.name) as? Boolean ?: true

fun AppModel.setPush(kind: PushKind, on: Boolean) {
    profileDoc = profileDoc + ("push" to ((profileDoc.map("push") ?: emptyMap()) + (kind.name to on)))
    if (demo) return
    db.document("users/$uid").update("push.${kind.name}", on).addOnFailureListener { toast = AppModel.friendly(it) }
}

// Links

/** team.systemoverload.org/task/abc and friends → where they go in the app. */
fun AppModel.open(uri: Uri) {
    if (uri.host != AppModel.SITE_HOST && uri.scheme != "sor") return
    open(uri.path ?: "")
}

fun AppModel.open(path: String) {
    val parts = path.split("/").filter { it.isNotEmpty() }
    val first = parts.firstOrNull() ?: return
    val arg = parts.getOrNull(1)?.let { Uri.decode(it) } ?: ""
    when {
        first == "task" && arg.isNotEmpty() -> openLink = AppLink.Task(arg)
        first == "action" && arg.isNotEmpty() -> openLink = AppLink.Action(arg)
        first == "signups" && arg.isNotEmpty() -> openLink = AppLink.Meeting(arg)
        first == "checkin" && arg.isNotEmpty() -> scope.launch { checkIn(arg) }
        else -> openLink = AppLink.Web(parts.joinToString("/"))
    }
}

// Checking in (mod-shifts checkInWithCode)

/** In person, from half an hour before the start until the end, and not marked yet (openShifts in mod-shifts). */
val AppModel.openShifts: List<Shift> get() {
    val now = nowMs.toDouble()
    val marked = myMarks.map { it.meetingId }.toSet()
    return events.filter { !it.allDay && it.start - 30 * 60_000 <= now && now <= it.end }
        .mapNotNull { shift(it.id) }
        .filter { !it.online && !it.cancelled && !it.isBoardMeeting && it.id !in marked && it.date >= seasonFrom }
}

/**
 * The code is checked by firestore.rules in the same write as the mark, so it never reaches the phone.
 * Two meetings can overlap, and only the code says which one you're at. Returns whether you're checked in.
 */
suspend fun AppModel.checkIn(raw: String): Boolean {
    val code = raw.filter { it.isDigit() }
    if (code.length != 4) { toast = "That isn't a check-in code."; return false }
    // Opened from a QR code at launch: wait for the calendar and your marks to arrive first
    repeat(60) { if (demo || ("calendar" in loaded && "attendance" in loaded)) return@repeat; delay(250) }
    val list = openShifts
    if (list.isEmpty()) {
        val already = myMarks.any { m -> events.any { it.id == m.meetingId && it.dayKey == Day.iso() } }
        toast = if (already) "You're already checked in." else "No meeting is open for check-in right now."
        return already
    }
    for (sh in list) {
        val statuses = if (nowMs >= sh.start + 25 * 60_000) listOf("partial") else listOf("present", "partial") // the rules go by the server's clock
        for (status in statuses) {
            if (sendCheckIn(sh, code, status)) {
                toast = if (status == "present") "Checked in to ${sh.title}. You're on time." else "Checked in to ${sh.title} (Partial: after the first 25 minutes)."
                return true
            }
        }
    }
    toast = "That code didn't work. Check the Monitor and try again."
    return false
}

private suspend fun AppModel.sendCheckIn(sh: Shift, code: String, status: String): Boolean {
    val id = "${sh.id}__$uid"
    val mark = mapOf(
        "meetingId" to sh.id, "seasonId" to currentSeason.id, "date" to sh.date, "title" to sh.title, "online" to sh.online,
        "uid" to uid, "rosterId" to "", "name" to (profile?.name ?: authName), "kind" to signupKind, "status" to status,
        "source" to "self", "at" to nowMs, "by" to uid,
    )
    if (demo) {
        if (code != "1234") return false
        myMarks = myMarks + Mark(id, mark)
        return true
    }
    val batch = db.batch()
    batch.set(db.document("checkins/$id"), mapOf("uid" to uid, "meetingId" to sh.id, "code" to code, "at" to nowMs))
    batch.set(db.document("attendance/$id"), mark)
    return runCatching { batch.commit().await() }.isSuccess
}

// Widget

private var widgetJob: kotlinx.coroutines.Job? = null

/** Writes what the Home Screen widget shows, at most once every couple of seconds. */
fun AppModel.updateWidget() {
    widgetJob?.cancel()
    widgetJob = scope.launch {
        delay(2000)
        if (phase != AppModel.Phase.Ready) return@launch
        val now = nowMs.toDouble()
        val upcoming = events.filter { it.end > now && !it.allDay && !isCancelled(it) }.take(4)
        val comp = nextCompetition
        val mine = myOpenTasks
        WidgetSnapshot(
            meetings = upcoming.map { WidgetSnapshot.Meeting(it.title, it.start.toLong(), it.end.toLong(), it.shortPlace, amGoing(it.id)) },
            competition = comp?.title ?: "", competitionDay = comp?.dayKey ?: "",
            openTasks = mine.size, dueSoon = mine.count { (Day.until(it.dueDate) ?: 99) <= 2 },
        ).save(context)
    }
}

// Blocking: hides that person's comments, photos and Board channel messages for you, on this phone.

private val AppModel.blockedKey get() = "sor-blocked:$uid"
fun AppModel.loadBlocked() { blockedUids = prefs.getStringSet(blockedKey, emptySet())?.toSet() ?: emptySet() }
fun AppModel.isBlocked(id: String) = id.isNotEmpty() && id != uid && id in blockedUids
fun AppModel.block(id: String) {
    if (id.isEmpty() || id == uid) return
    blockedUids = blockedUids + id
    prefs.edit().putStringSet(blockedKey, blockedUids).apply()
    toast = "Blocked ${person(id)?.firstName ?: "them"}. You won't see their comments, photos or messages."
}
fun AppModel.unblock(id: String) {
    blockedUids = blockedUids - id
    prefs.edit().putStringSet(blockedKey, blockedUids).apply()
}

// Reports

suspend fun AppModel.report(t: ReportTarget, reason: ReportReason, note: String) {
    val data = mapOf(
        "kind" to t.kind, "path" to t.path, "taskId" to t.taskId, "authorUid" to t.authorUid,
        "authorName" to (person(t.authorUid)?.name ?: ""), "text" to t.text.take(500),
        "reason" to reason.label, "note" to note.trim().take(1000),
        "reporterUid" to uid, "reporterName" to (profile?.name ?: authName), "status" to "open", "createdAt" to nowMs,
    )
    if (demo) { reports = listOf(Report(UUID.randomUUID().toString(), data)) + reports; return }
    db.collection("reports").add(data).await()
}

/** Admins only: the reports list, newest first. */
fun AppModel.wantReports() {
    if (!isAdmin) return
    lazyListen("reports", db.collection("reports")) { d -> reports = d.map { Report(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.createdAt } }
}

val AppModel.openReports: List<Report> get() = reports.filter { it.isOpen }

fun AppModel.setReport(r: Report, status: String) {
    val patch = mapOf("status" to status, "resolvedBy" to uid, "resolvedByName" to (profile?.name ?: authName), "resolvedAt" to nowMs)
    if (demo) { reports = reports.map { if (it.id == r.id) it.copy(status = status, resolvedByName = profile?.name ?: "") else it }; return }
    db.document("reports/${r.id}").update(patch).addOnFailureListener { toast = AppModel.friendly(it) }
}

/** Whether this admin can take the reported thing down from here (the rules decide; Board channel messages only by their author). */
fun AppModel.canRemove(r: Report) = isLead && r.kind in listOf("comment", "photo", "task")

/** Takes the reported thing down, then marks the report removed. */
suspend fun AppModel.removeReported(r: Report) {
    try {
        when (r.kind) {
            "comment" -> if (!demo) {
                db.document(r.path).delete().await()
                task(r.taskId)?.let { t -> if (t.commentCount > 0) runCatching { db.document("tasks/${t.id}").update("commentCount", t.commentCount - 1).await() } }
            } else demoComments = demoComments + (r.taskId to (demoComments[r.taskId] ?: emptyList()).filter { "tasks/${r.taskId}/comments/${it.id}" != r.path })
            "photo" -> task(r.taskId)?.let { removePhoto(it, r.path.split("/").last()) }
            "task" -> task(r.taskId)?.let { archive(it) }
            else -> return
        }
        setReport(r, "removed")
        toast = "Removed"
    } catch (e: Exception) { toast = AppModel.friendly(e) }
}

// Team reminders (Action Items › Reminders)

fun AppModel.wantTeamReminders() {
    if (!canEdit) return
    lazyListen("teamReminders", db.collection("teamReminders")) { d -> teamReminders = d.map { TeamReminder(it.id, it.data ?: emptyMap()) } }
}

/** The Slack channel a track's reminders go to (subteams/{board|buildcom}.slackChannel). */
fun AppModel.reminderChannel(track: Track): String = (subteamDocs[if (track == Track.board) "board" else "buildcom"]?.str("slackChannel") ?: "").trim()

suspend fun AppModel.addTeamReminder(text: String, track: Track, at: java.time.LocalDateTime) {
    val sendAt = at.atZone(Day.zone).toInstant().toEpochMilli()
    if (sendAt <= nowMs + 60_000) throw AppError("Pick a day and time that haven't passed yet.")
    val data = mapOf("text" to text.trim(), "track" to track.name, "date" to at.toLocalDate().toString(),
        "time" to "%02d:%02d".format(at.hour, at.minute), "sendAt" to sendAt, "createdBy" to uid, "createdAt" to nowMs)
    if (demo) { teamReminders = teamReminders + TeamReminder(UUID.randomUUID().toString(), data); return }
    db.collection("teamReminders").add(data).await()
}

fun AppModel.deleteTeamReminder(r: TeamReminder) {
    if (demo) { teamReminders = teamReminders.filter { it.id != r.id }; return }
    db.document("teamReminders/${r.id}").delete().addOnFailureListener { toast = AppModel.friendly(it) }
}

// Action items → tasks (promoteAI in mod-views)

suspend fun AppModel.promote(a: ActionItem): String {
    val owner = a.ownerUids.firstOrNull()?.let { person(it) }
    val boardIds = boards.map { it.id }.toSet()
    val subteamId = owner?.subteamIds?.firstOrNull { it in boardIds } ?: profile?.subteamIds?.firstOrNull { it in boardIds } ?: boards.firstOrNull()?.id ?: ""
    val ev = events.firstOrNull { it.id == a.meetingId }
    val id = createTask(a.text, ev?.let { "From meeting: ${it.title} (${Day.pretty(it.dayKey)})" } ?: "", subteamId, null, a.ownerUids, a.dueDate, Priority.med)
    patch(a, mapOf("promotedTaskId" to id))
    return id
}

// Task photos (photos/{id}: JPEG bytes, ≤900 KB, 14 days unless kept)

suspend fun AppModel.photo(id: String): Bitmap? {
    photoCache[id]?.let { return it }
    if (demo) return null
    val bytes = runCatching { (db.document("photos/$id").get().await().get("data") as? Blob)?.toBytes() }.getOrNull() ?: return null
    val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
    photoCache[id] = bmp
    return bmp
}

/** Scales to at most 1920 px and ≤900 KB as JPEG (fitPhoto in mod-util), then saves it like the website. */
suspend fun AppModel.addPhoto(t: TaskItem, uri: Uri) {
    val now = nowMs
    if (t.photos.count { it.exp > now } >= 8) throw AppError("A task holds up to 8 photos. Remove one first.")
    val (small, data) = withContext(Dispatchers.Default) {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw AppError("Couldn't read that photo.")
        val scale = min(1.0, 1920.0 / max(src.width, src.height))
        val w = (src.width * scale).roundToInt(); val h = (src.height * scale).roundToInt()
        val small = if (scale < 1) Bitmap.createScaledBitmap(src, w, h, true) else src
        var q = 82
        var out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
        while (out.size > 900_000 && q > 30) { q -= 12; out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray() }
        if (out.size > 900_000) throw AppError("That photo is too big.")
        small to out
    }
    val exp = now + 14 * 86_400_000L
    val ref = db.collection("photos").document()
    val batch = db.batch()
    batch.set(ref, mapOf("taskId" to t.id, "data" to Blob.fromBytes(data), "type" to "image/jpeg", "w" to small.width, "h" to small.height,
        "size" to data.size, "createdBy" to uid, "createdAt" to now, "expiresAt" to Timestamp(Date(exp))))
    val cur = task(t.id) ?: t
    val list = cur.photos.map { mapOf("id" to it.id, "by" to it.by, "exp" to it.exp) } +
        listOf(mapOf("id" to ref.id, "by" to uid, "at" to now, "exp" to exp, "w" to small.width, "h" to small.height))
    batch.update(db.document("tasks/${t.id}"), "photos", list)
    batch.commit().await()
    photoCache[ref.id] = small
}

suspend fun AppModel.removePhoto(t: TaskItem, pid: String) {
    val cur = task(t.id) ?: t
    val batch = db.batch()
    batch.delete(db.document("photos/$pid"))
    batch.update(db.document("tasks/${t.id}"), "photos", cur.photos.filter { it.id != pid }.map { mapOf("id" to it.id, "by" to it.by, "exp" to it.exp) })
    try { batch.commit().await() } catch (e: Exception) { toast = AppModel.friendly(e) }
}

// Roster: leads putting someone on a shift (openAddPeople in mod-shifts), account or not

/** Who someone is for a signup (fromRoster): their account if the roster entry is linked, else r-<rosterId>. */
data class Who(val key: String, val uid: String, val rosterId: String, val name: String, val kind: String, val femaleAdult: Boolean)

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

fun AppModel.wantRoster() {
    lazyListen("roster", db.collection("roster")) { d -> roster = d.map { RosterPerson(it.id, it.data ?: emptyMap()) } }
    // Whether an adult counts as a female adult: only leads can read everyone's answers
    if (isLead) lazyListen("userDetails", db.collection("userDetails")) { d -> femaleByUid = d.associate { it.id to (it.data ?: emptyMap()).bool("female") } }
}

/** linkedUser in mod-shifts: the roster entry's account, or an approved account with the same name or alias. */
private fun AppModel.linked(r: RosterPerson): Person? {
    if (r.uid.isNotEmpty()) person(r.uid)?.let { return it }
    val names = (listOf(r.name) + r.aliases).map(::norm).filter { it.isNotEmpty() }.toSet()
    return users.firstOrNull { it.role != Role.pending && norm(it.name) in names }
}

val AppModel.rosterPeople: List<Who> get() = roster.filter { it.active }.sortedBy { it.name.lowercase() }.map { r ->
    val u = linked(r)
    val female = u?.let { femaleByUid[it.id] } ?: r.female
    Who(u?.id ?: "r-${r.id}", u?.id ?: "", r.id, r.name, r.kind, r.kind != "student" && female)
}

/** The signup that is this person's, by account or roster entry. */
fun AppModel.signupOf(w: Who, meetingId: String): Signup? =
    signups.firstOrNull { it.meetingId == meetingId && (it.uid == w.key || (w.rosterId.isNotEmpty() && it.rosterId == w.rosterId)) }

suspend fun AppModel.addToShift(w: Who, sh: Shift) {
    val id = "${sh.id}__${w.key}"
    val data = mutableMapOf<String, Any?>(
        "meetingId" to sh.id, "uid" to w.key, "name" to w.name, "kind" to w.kind, "femaleAdult" to w.femaleAdult, "leader" to false,
        "title" to sh.title, "date" to sh.date, "start" to sh.start, "end" to sh.end, "place" to sh.place,
        "online" to sh.online, "inPerson" to sh.inPerson, "seasonId" to currentSeason.id, "at" to nowMs, "by" to uid,
    )
    if (w.rosterId.isNotEmpty()) data["rosterId"] = w.rosterId
    if (demo) { signups = signups + Signup(id, data); return }
    try { db.document("signups/$id").set(data).await() } catch (e: Exception) { toast = AppModel.friendly(e) }
}


/** Coming back to the app: today's arrivals, a fresh calendar. */
fun AppModel.refreshCalendarOnResume() {
    if (phase != AppModel.Phase.Ready || demo) return
    watchArrivals()
    refreshCalendar(force = true)
}
