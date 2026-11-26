package org.systemoverload.team.data

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QueryDocumentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.systemoverload.team.BuildConfig
import org.systemoverload.team.R
import org.systemoverload.team.model.*
import java.util.UUID
import kotlin.math.abs

class AppError(message: String) : Exception(message)

/** Somewhere the app was asked to go: an App Link (team.systemoverload.org/…), a tapped notification, or a QR code. */
sealed class AppLink {
    data class Task(val id: String) : AppLink()
    data class Action(val id: String) : AppLink()
    data class Meeting(val id: String) : AppLink()
    data class Web(val path: String) : AppLink()
}

/**
 * Everything the app knows, kept live by Firestore listeners, plus every write it makes.
 * Writes mirror the web app's shapes exactly (index.html), and firestore.rules decide what is allowed,
 * the same as on the website and in the iPhone app.
 */
class AppModel(val context: Context) {
    sealed class Phase {
        data object NotConfigured : Phase()
        data object Starting : Phase()
        data object SignedOut : Phase()
        data object LoadingProfile : Phase()
        data object Onboarding : Phase()
        data object Pending : Phase()
        data object Ready : Phase()
        data class Failed(val message: String) : Phase()
    }

    val scope: CoroutineScope = MainScope()

    var phase by mutableStateOf<Phase>(Phase.Starting)
    var uid by mutableStateOf("")
    var authName by mutableStateOf("")
    var authEmail by mutableStateOf("")
    var authPhoto by mutableStateOf("")

    var profile by mutableStateOf<Person?>(null)
    var users by mutableStateOf(listOf<Person>())
    var subteams by mutableStateOf(listOf<Subteam>())
    var columns by mutableStateOf(listOf<BoardColumn>())
    var allTasks by mutableStateOf(listOf<TaskItem>())
    var settings by mutableStateOf(mapOf<String, Doc>())
    var seasonDocs by mutableStateOf(listOf<Season>())
    var meetingExtras by mutableStateOf(mapOf<String, Doc>())
    var arrivals by mutableStateOf(listOf<Arrival>())
    var signups by mutableStateOf(listOf<Signup>())
    var myMarks by mutableStateOf(listOf<Mark>())
    var myFemale by mutableStateOf<Boolean?>(null)
    var actionItems by mutableStateOf(listOf<ActionItem>())
    var privateActionItems by mutableStateOf(listOf<ActionItem>())
    var agendas by mutableStateOf(listOf<Agenda>())
    var events by mutableStateOf(listOf<CalEvent>())
    var loaded by mutableStateOf(setOf<String>())
    var toast by mutableStateOf<String?>(null)
    /** Saving the first-time answers failed: let them in anyway, like the website does. */
    var askSkipped = false
    /** Your users/{uid} document as stored (push settings live in it). */
    var profileDoc by mutableStateOf<Doc>(emptyMap())
    var openLink by mutableStateOf<AppLink?>(null)
    var showHelper by mutableStateOf(false)
    var pushAllowed by mutableStateOf(true)
    var pushToken: String? = null
    var pendingPushToken: String? = null

    var boardChannel by mutableStateOf(listOf<ChannelMessage>())
    var agendaDocs by mutableStateOf(listOf<AgendaDoc>())
    var boardAgendaDocs by mutableStateOf(listOf<AgendaDoc>())
    var googleDocs by mutableStateOf(listOf<GoogleDoc>())
    var boardGoogleDocs by mutableStateOf(listOf<GoogleDoc>())
    var teamMarks by mutableStateOf(listOf<Mark>())
    var isHelperReviewer by mutableStateOf(false)
    var roster by mutableStateOf(listOf<RosterPerson>())
    var teamReminders by mutableStateOf(listOf<TeamReminder>())
    /** reports/{id}: admins only */
    var reports by mutableStateOf(listOf<Report>())
    /** People you've blocked, on this phone */
    var blockedUids by mutableStateOf(setOf<String>())
    /** Subteam documents as stored (for fields the app rarely needs, like slackChannel) */
    var subteamDocs by mutableStateOf(mapOf<String, Doc>())
    var femaleByUid by mutableStateOf(mapOf<String, Boolean>())
    val photoCache = mutableMapOf<String, android.graphics.Bitmap>()

    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var profileReg: ListenerRegistration? = null
    private val teamRegs = mutableListOf<ListenerRegistration>()
    private var lastIcs = ""
    private var creatingProfile = false
    private var autoAdmin = false
    private var arrivalsDay = ""
    private var boardRoomReg: ListenerRegistration? = null
    private var agendasReg: ListenerRegistration? = null
    /** Listeners started the first time a page needs them (Board Room, agenda documents, the team's attendance). */
    val lazyRegs = mutableMapOf<String, ListenerRegistration>()

    /** Demo mode: invented people and tasks, nothing read from or written to the team's database. */
    var demo by mutableStateOf(false)
    val demoDocs = mutableMapOf<String, Doc>()
    var demoComments by mutableStateOf(mapOf<String, List<Comment>>())

    val prefs = context.getSharedPreferences("sor", Context.MODE_PRIVATE)

    companion object {
        /** The website. */
        const val SITE = "https://team.systemoverload.org"
        const val SITE_HOST = "team.systemoverload.org"
        const val TAG = "SOR"

        fun friendly(e: Throwable): String {
            if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return "You don't have permission to do that."
            if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE) return "No connection. It'll be saved when you're back online."
            return e.message ?: "Something went wrong."
        }
    }

    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val version: String get() = BuildConfig.VERSION_NAME

    // Permissions (can.* in mod-state)

    val role: Role get() = profile?.role ?: Role.pending
    val canRead: Boolean get() = role.rank >= Role.viewer.rank
    val canEdit: Boolean get() = role.rank >= Role.member.rank
    val isLead: Boolean get() = role.rank >= Role.lead.rank
    val isAdmin: Boolean get() = role == Role.admin
    fun canDelete(t: TaskItem) = isLead || (canEdit && t.createdBy == uid)

    /** holdsIn in mod-state: on the leadership roster's list for that group, by email or linked account. */
    private fun holds(group: String, stored: String): Boolean {
        val doc = settings["leadership"] ?: return false
        val emails = doc.strs(stored).map { it.lowercase() }.toMutableSet()
        for (p in doc.docs("positions").filter { it.str("group") == group }) {
            for (h in p.docs("holders")) {
                if (h.str("email").isNotEmpty()) emails.add(h.str("email").lowercase())
                if (h.str("uid").isNotEmpty() && h.str("uid") == uid) return true
            }
        }
        return authEmail.isNotEmpty() && authEmail in emails
    }
    /** Writes confidential action items (isBoardMember). */
    val isBoardMember: Boolean get() = if (demo) isAdmin else holds("Board", "boardEmails")
    val isLeadMentor: Boolean get() = if (demo) false else holds("Lead mentors", "leadMentorEmails")
    val canSeeBoardRoom: Boolean get() = isBoardMember || isLeadMentor
    val isParent: Boolean get() = profile?.memberType == "parent"

    // Lookups

    fun person(id: String): Person? = users.firstOrNull { it.id == id }
    fun subteam(id: String): Subteam? = subteams.firstOrNull { it.id == id }
    fun task(id: String): TaskItem? = allTasks.firstOrNull { it.id == id }

    val seasons: List<Season> get() {
        val list = seasonDocs.filter { !it.archived }.sortedBy { it.start }
        return list.ifEmpty { Season.defaults }
    }
    /** activeSeason() in mod-state: the team's chosen season, else the planned one for today, else the last. */
    val currentSeason: Season get() {
        val list = seasons
        val today = Day.iso()
        settings["season"]?.str("activeId")?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        return list.firstOrNull { it.start <= today && today <= it.end } ?: list.last()
    }
    /** seasonFrom in mod-state: the first day that can belong to the open season. */
    val seasonFrom: String get() {
        val s = currentSeason
        val team = settings["season"]
        if (team != null && team.str("activeId") == s.id && team.str("since").isNotEmpty() && team.str("since") < s.start) return team.str("since")
        return s.start
    }
    /** The open season's tasks that aren't archived. */
    val tasks: List<TaskItem> get() {
        val sid = currentSeason.id
        return allTasks.filter { it.seasonId == sid && !it.archived }
    }
    /** Boards that hold tasks: not Team Goals (the Priorities column) and not Board / Build Committee (action items). */
    val boards: List<Subteam> get() = subteams.filter { it.id !in listOf("goals", "board", "buildcom") }.sortedBy { it.position }
    fun columnsFor(subteam: String): List<BoardColumn> =
        columns.filter { it.subteamIds.isEmpty() || subteam == "all" || subteam in it.subteamIds }.sortedBy { it.position }
    val doneColumnId: String get() {
        val cols = columns.sortedBy { it.position }
        return cols.firstOrNull { it.id == "done" }?.id ?: cols.lastOrNull()?.id ?: "done"
    }
    fun isDone(t: TaskItem) = t.columnId == doneColumnId
    fun columnName(id: String) = columns.firstOrNull { it.id == id }?.name ?: id

    val myOpenTasks: List<TaskItem> get() = tasks.filter { (uid in it.assigneeUids || uid in it.backupUids) && !isDone(it) }
        .sortedWith { a, b ->
            when {
                a.dueDate.isNotEmpty() && b.dueDate.isEmpty() -> -1
                a.dueDate.isEmpty() && b.dueDate.isNotEmpty() -> 1
                a.dueDate.isNotEmpty() && a.dueDate != b.dueDate -> a.dueDate.compareTo(b.dueDate)
                else -> a.priority.rank - b.priority.rank
            }
        }
    val teammates: List<Person> get() = users.filter { it.role.rank >= Role.member.rank }.sortedBy { it.name.lowercase() }

    // Meetings

    fun isCancelled(e: CalEvent) = meetingExtras[e.id]?.bool("cancelled") ?: false
    val upcoming: List<CalEvent> get() { val now = nowMs.toDouble(); return events.filter { it.end > now } }
    val nextCompetition: CalEvent? get() = upcoming.firstOrNull { it.isCompetition && !it.isNotCompeting }
    /** A meeting in person happening now or within the hour: the "I'm here" button shows for it. */
    val meetingNow: CalEvent? get() {
        val now = nowMs.toDouble()
        return events.firstOrNull { !it.allDay && it.place.isNotEmpty() && !isCancelled(it) && it.start - 3_600_000 <= now && now <= it.end }
    }
    val imHere: Boolean get() = arrivals.any { it.id == "${Day.iso()}__$uid" }
    /** The Slack post that covers a meeting, from settings/slackSignups (kept by the bot). */
    fun slackSignupURL(meetingId: String): String? {
        val m = settings["slackSignups"]?.map("meetings")?.map(meetingId) ?: return null
        val ch = m.str("channel"); val ts = m.str("ts")
        if (!Regex("^[A-Z0-9]+$").matches(ch) || !Regex("^\\d+\\.\\d+$").matches(ts)) return null
        return "https://trivalleyrobotics.slack.com/archives/$ch/p${ts.replace(".", "")}"
    }

    // Start up and sign in

    fun start() {
        if (authListener != null) return
        if (FirebaseApp.getApps(context).isEmpty()) { phase = Phase.NotConfigured; return }
        val l = FirebaseAuth.AuthStateListener { authChanged(it.currentUser) }
        authListener = l
        FirebaseAuth.getInstance().addAuthStateListener(l)
    }

    /** Google is how the team signs in; email and password is the "Other sign-in" (accounts an admin makes in Firebase). */
    val signedInWithGoogle: Boolean get() = FirebaseAuth.getInstance().currentUser?.providerData?.any { it.providerId == "google.com" } ?: false

    suspend fun signIn(email: String, password: String): String? = try {
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await()
        null
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        "That email and password don't match an account."
    } catch (e: FirebaseAuthInvalidUserException) {
        "That email and password don't match an account."
    } catch (e: FirebaseAuthException) {
        when (e.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" -> "Email sign-in isn't turned on for this team yet."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many tries. Wait a minute and try again."
            else -> e.message
        }
    } catch (e: com.google.firebase.FirebaseNetworkException) {
        "No connection. Check your internet and try again."
    } catch (e: Exception) {
        e.message
    }

    private val webClientId: String get() = context.getString(R.string.default_web_client_id)

    /** Google's sign-in sheet (Credential Manager), then Firebase. Returns the Google ID token. */
    private suspend fun googleIdToken(activity: Activity, silent: Boolean = false): String? {
        val option = if (silent) GetGoogleIdOption.Builder().setServerClientId(webClientId).setFilterByAuthorizedAccounts(true).setAutoSelectEnabled(true).build()
        else GetSignInWithGoogleOption.Builder(webClientId).build()
        val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val res = CredentialManager.create(activity).getCredential(activity, req)
        val c = res.credential
        if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(c.data).idToken
        }
        return null
    }

    suspend fun signIn(activity: Activity) {
        try {
            val idToken = googleIdToken(activity) ?: throw AppError("Google didn't send a sign-in token. Try again.")
            FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: GetCredentialCancellationException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "sign in", e)
            toast = e.message ?: "Couldn't sign in."
        }
    }

    fun signOut() {
        if (demo) { leaveDemo(); return }
        scope.launch {
            forgetPushToken() // while still signed in, so the rules allow it
            runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
            FirebaseAuth.getInstance().signOut()
        }
    }

    private fun authChanged(user: FirebaseUser?) {
        if (demo && user == null) return // looking around the demo: nobody's signed in, and that's fine
        if (demo) leaveDemo()
        stopAll()
        if (user == null) { uid = ""; profile = null; phase = Phase.SignedOut; return }
        uid = user.uid
        authEmail = (user.email ?: "").lowercase()
        authName = user.displayName ?: user.email ?: ""
        // Firebase keeps the photo from someone's first sign-in; the Google entry is refreshed every sign-in.
        authPhoto = user.providerData.firstOrNull { it.providerId == "google.com" }?.photoUrl?.toString() ?: user.photoUrl?.toString() ?: ""
        phase = Phase.LoadingProfile
        scope.launch { watchProfile(user) }
    }

    private suspend fun watchProfile(user: FirebaseUser) {
        // Someone on the leadership roster's admin list is an admin from the first sign-in. (This repo is public, so
        // unlike the website and the iPhone app it carries no owner email: the rules' isAutoAdmin() still covers the owner.)
        autoAdmin = false
        runCatching { db.document("settings/leadership").get().await().data }.getOrNull()?.let { autoAdmin = authEmail in it.strs("adminEmails") }
        if (uid != user.uid) return
        profileReg = db.document("users/${user.uid}").addSnapshotListener { snap, err ->
            if (err != null) { phase = Phase.Failed("Couldn't load your profile: ${err.message}"); return@addSnapshotListener }
            if (snap != null) profileChanged(snap)
        }
    }

    private fun profileChanged(snap: DocumentSnapshot) {
        val data = snap.data
        if (data == null) {
            // First sign-in: create a pending profile, the same document the website makes. An admin approves it.
            if (creatingProfile) return
            creatingProfile = true
            db.document("users/$uid").set(mapOf(
                "name" to authName, "email" to authEmail, "photoURL" to authPhoto,
                "role" to if (autoAdmin) "admin" else "pending", "subteamIds" to emptyList<String>(), "createdAt" to nowMs,
            )).addOnFailureListener { phase = Phase.Failed("Couldn't create your profile: ${it.message}") }
            return
        }
        val p = Person(snap.id, data)
        profile = p
        profileDoc = data
        if (autoAdmin && p.role != Role.admin) db.document("users/$uid").update("role", "admin")
        // Keep the Google name and photo in step, unless an admin renamed them (nameEdited).
        val sync = mutableMapOf<String, Any>()
        if (authName.isNotEmpty() && p.name != authName && !data.bool("nameEdited")) sync["name"] = authName
        if (authPhoto.isNotEmpty() && p.photoURL != authPhoto) sync["photoURL"] = authPhoto
        if (sync.isNotEmpty()) db.document("users/$uid").update(sync)

        if (p.onboardedAt == 0.0 && !askSkipped) { stopTeam(); phase = Phase.Onboarding; return }
        if (!canRead) { stopTeam(); phase = Phase.Pending; return }
        if (teamRegs.isEmpty()) startTeam()
        if (phase != Phase.Ready) {
            loadBlocked()
            phase = Phase.Ready
            setUpPush()
            pendingPushToken?.let { pendingPushToken = null; savePushToken(it) }
        }
    }

    // Live data

    private fun listen(name: String, query: Query, apply: (List<QueryDocumentSnapshot>) -> Unit) {
        teamRegs.add(query.addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "listener $name: ${err.message}"); return@addSnapshotListener }
            val docs = snap?.documents?.filterIsInstance<QueryDocumentSnapshot>() ?: return@addSnapshotListener
            apply(docs)
            loaded = loaded + name
        })
    }

    private fun startTeam() {
        listen("users", db.collection("users")) { d -> users = d.map { Person(it.id, it.data) } }
        listen("subteams", db.collection("subteams")) { d ->
            subteams = d.map { Subteam(it.id, it.data) }
            subteamDocs = d.associate { it.id to it.data }
        }
        listen("columns", db.collection("columns")) { d -> columns = d.map { BoardColumn(it.id, it.data) } }
        listen("tasks", db.collection("tasks")) { d -> allTasks = d.map { TaskItem(it.id, it.data) }; updateWidget() }
        listen("seasons", db.collection("seasons")) { d -> seasonDocs = d.map { Season(it.id, it.data) } }
        listen("meetings", db.collection("meetings")) { d -> meetingExtras = d.associate { it.id to it.data } }
        // The website's window: the last 150 days, or the season's start if that's earlier.
        val back = Day.inDays(-150)
        listen("signups", db.collection("signups").whereGreaterThanOrEqualTo("date", minOf(back, seasonFrom))) { d ->
            signups = d.map { Signup(it.id, it.data) }
            updateWidget()
        }
        listen("attendance", db.collection("attendance").whereEqualTo("uid", uid)) { d -> myMarks = d.map { Mark(it.id, it.data) } }
        listen("actionItems", db.collection("actionItems")) { d -> actionItems = d.map { ActionItem(it.id, it.data) } }
        // Your private answers (only you and leads can read them): whether a signup counts as a female adult.
        teamRegs.add(db.document("userDetails/$uid").addSnapshotListener { snap, _ -> myFemale = snap?.data?.bool("female") })
        listen("settings", db.collection("settings")) { d ->
            settings = d.associate { it.id to it.data }
            refreshCalendar()
            watchBoardRoom()
        }
        watchArrivals()
        scope.launch { checkHelperReviewer() }
    }

    /** Today's "I'm here" check-ins. Re-pointed at the new day when the app comes back after midnight. */
    fun watchArrivals() {
        val day = Day.iso()
        if (day == arrivalsDay || teamRegs.isEmpty()) return
        arrivalsDay = day
        listen("arrivals", db.collection("arrivals").whereEqualTo("day", day)) { d ->
            arrivals = d.map { Arrival(it.id, it.data) }.sortedByDescending { it.at }
        }
    }

    /** The calendar the bot keeps in settings/calendar, expanded around today. */
    fun refreshCalendar(force: Boolean = false) {
        val ics = settings["calendar"]?.str("ics") ?: return
        if (ics.isEmpty() || (!force && ics == lastIcs)) return
        lastIcs = ics
        scope.launch {
            val list = withContext(Dispatchers.Default) { TeamCalendar.events(ics) }
            events = list
            loaded = loaded + "calendar"
            updateWidget()
        }
    }

    /** Confidential action items: only the Board and lead mentors can load them (the rules refuse anyone else). */
    private fun watchBoardRoom() {
        if (!canSeeBoardRoom || boardRoomReg != null || demo) return
        val reg = db.collection("privateActionItems").addSnapshotListener { snap, _ ->
            privateActionItems = snap?.documents?.map { ActionItem(it.id, it.data ?: emptyMap(), confidential = true) } ?: emptyList()
            loaded = loaded + "privateActionItems"
        }
        boardRoomReg = reg
        teamRegs.add(reg)
    }

    /** Hangar agendas load the first time someone opens them (the website's want("agendas")). */
    fun wantAgendas() {
        if (agendasReg != null || teamRegs.isEmpty() || demo) return
        val reg = db.collection("agendas").addSnapshotListener { snap, _ ->
            agendas = snap?.documents?.map { Agenda(it.id, it.data ?: emptyMap()) } ?: emptyList()
            loaded = loaded + "agendas"
        }
        agendasReg = reg
        teamRegs.add(reg)
    }

    /** For the pages that load on first use. */
    fun lazyListen(name: String, q: Query, apply: (List<DocumentSnapshot>) -> Unit) {
        if (lazyRegs[name] != null || demo || phase != Phase.Ready) return
        lazyRegs[name] = q.addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "listener $name: ${err.message}"); return@addSnapshotListener }
            apply(snap?.documents ?: emptyList())
            loaded = loaded + name
        }
    }

    private fun stopTeam() {
        boardRoomReg = null; agendasReg = null
        lazyRegs.values.forEach { it.remove() }
        lazyRegs.clear()
        teamRegs.forEach { it.remove() }
        teamRegs.clear()
        arrivalsDay = ""
    }

    private fun stopAll() {
        profileReg?.remove(); profileReg = null
        stopTeam()
        creatingProfile = false
        users = emptyList(); subteams = emptyList(); columns = emptyList(); allTasks = emptyList(); settings = emptyMap(); arrivals = emptyList()
        signups = emptyList(); myMarks = emptyList(); events = emptyList(); loaded = emptySet()
        actionItems = emptyList(); privateActionItems = emptyList(); agendas = emptyList(); myFemale = null
        boardChannel = emptyList(); agendaDocs = emptyList(); boardAgendaDocs = emptyList(); googleDocs = emptyList(); boardGoogleDocs = emptyList()
        teamMarks = emptyList(); roster = emptyList(); teamReminders = emptyList(); reports = emptyList()
        lastIcs = ""
    }

    // Writes

    /** The questions everyone answers once (mod-app saveAnswers). */
    fun skipAnswers() {
        askSkipped = true
        val p = profile ?: return
        phase = if (p.role.rank >= Role.viewer.rank) Phase.Ready else Phase.Pending
        if (phase == Phase.Ready && teamRegs.isEmpty()) startTeam()
    }

    suspend fun saveAnswers(female: Boolean, memberType: String) {
        val at = nowMs
        val batch = db.batch()
        batch.set(db.document("userDetails/$uid"), mapOf("female" to female, "at" to at))
        batch.update(db.document("users/$uid"), mapOf("memberType" to memberType, "onboardedAt" to at))
        batch.commit().await()
    }

    fun newTaskPosition(columnId: String): Double = (tasks.filter { it.columnId == columnId }.maxOfOrNull { it.position } ?: 0.0) + 1000

    /** The same document the website's task editor makes. */
    suspend fun createTask(title: String, description: String, subteamId: String, columnId: String?, assignees: List<String>,
                           dueDate: String, priority: Priority): String {
        val cols = columnsFor(subteamId)
        val col = columnId ?: cols.firstOrNull { it.id == "todo" }?.id ?: cols.firstOrNull()?.id ?: "todo"
        val data: Map<String, Any?> = mapOf(
            "title" to title.trim(), "description" to description, "columnId" to col, "subteamId" to subteamId,
            "assigneeUids" to assignees, "backupUids" to emptyList<String>(), "dueDate" to dueDate, "startDate" to "", "estHours" to "",
            "flagged" to false, "dod" to "", "priority" to priority.name, "subsystem" to "", "checklist" to emptyList<Doc>(),
            "blockedBy" to emptyList<String>(), "childIds" to emptyList<String>(), "isGoal" to false, "reminders" to listOf(90),
            "seasonId" to currentSeason.id, "position" to newTaskPosition(col), "createdBy" to uid, "createdAt" to nowMs, "commentCount" to 0,
            "updatedAt" to nowMs, "updatedBy" to uid,
        )
        if (demo) {
            val id = "demo-${UUID.randomUUID().toString().take(8)}"
            demoDocs[id] = data
            allTasks = allTasks + TaskItem(id, data)
            return id
        }
        return db.collection("tasks").add(data).await().id
    }

    fun update(t: TaskItem, patch: Map<String, Any?>) {
        val p = patch + mapOf("updatedAt" to nowMs, "updatedBy" to uid)
        if (demo) {
            val d = (demoDocs[t.id] ?: emptyMap()) + p
            demoDocs[t.id] = d
            allTasks = allTasks.map { if (it.id == t.id) TaskItem(t.id, d) else it }
            return
        }
        db.document("tasks/${t.id}").update(p).addOnFailureListener { toast = friendly(it) }
    }

    fun move(t: TaskItem, columnId: String) {
        val patch = mutableMapOf<String, Any?>("columnId" to columnId, "position" to newTaskPosition(columnId))
        if (columnId == doneColumnId && t.columnId != doneColumnId) patch["prevColumnId"] = t.columnId
        update(t, patch)
    }

    fun archive(t: TaskItem) = update(t, mapOf("archived" to true, "archivedAt" to nowMs))
    fun unarchive(t: TaskItem) = update(t, mapOf("archived" to false, "archivedAt" to 0))

    fun setChecklist(t: TaskItem, list: List<ChecklistItem>) = update(t, mapOf("checklist" to list.map { mapOf("text" to it.text, "done" to it.done) }))

    suspend fun addComment(t: TaskItem, body: String) {
        if (demo) {
            demoComments = demoComments + (t.id to (demoComments[t.id] ?: emptyList()) + Comment(UUID.randomUUID().toString(), uid, body, nowMs.toDouble()))
            update(t, mapOf("commentCount" to t.commentCount + 1))
            return
        }
        db.collection("tasks/${t.id}/comments").add(mapOf("authorId" to uid, "body" to body, "createdAt" to nowMs)).await()
        val count = task(t.id)?.commentCount ?: t.commentCount
        runCatching { db.document("tasks/${t.id}").update("commentCount", count + 1).await() }
    }

    /** Live comments on a task. Call the returned function to stop listening. */
    fun comments(taskId: String, onChange: (List<Comment>) -> Unit): () -> Unit {
        if (demo) { onChange(demoComments[taskId] ?: emptyList()); return {} }
        val reg = db.collection("tasks/$taskId/comments").addSnapshotListener { snap, _ ->
            onChange(snap?.documents?.map { Comment(it.id, it.data ?: emptyMap()) }?.sortedBy { it.createdAt } ?: emptyList())
        }
        return { reg.remove() }
    }

    /** "I'm here": one document per person per day (mod-arrivals announceArrival). The meeting-room Monitor rings for it. */
    suspend fun announceArrival() {
        val day = Day.iso()
        val doc = mapOf("uid" to uid, "name" to (profile?.name ?: authName), "photoURL" to authPhoto, "day" to day, "at" to nowMs)
        if (demo) { arrivals = listOf(Arrival("${day}__$uid", doc)) + arrivals; toast = "The team knows you're here."; return }
        try {
            db.document("arrivals/${day}__$uid").set(doc).await()
            toast = "The team knows you're here."
        } catch (e: Exception) { toast = friendly(e) }
    }

    // Deleting your account

    /**
     * Deletes what's yours, then the sign-in itself. Google asks you to sign in again first, because removing
     * an account needs a fresh sign-in. Team records (tasks you made, attendance, comments) stay with the team.
     */
    suspend fun deleteAccount(activity: Activity) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (signedInWithGoogle) {
            val idToken = googleIdToken(activity) ?: throw AppError("Google didn't confirm the sign-in.")
            user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
        } else {
            val last = user.metadata?.lastSignInTimestamp ?: 0
            if (System.currentTimeMillis() - last > 300_000) throw AppError("For your safety, sign out and sign back in, then delete your account.")
        }
        val me = uid
        forgetPushToken()
        profileReg?.remove(); profileReg = null
        stopTeam()
        // Each can be refused until the rules let people delete their own documents; the account still goes.
        runCatching { db.document("arrivals/${Day.iso()}__$me").delete().await() }
        runCatching { db.document("userDetails/$me").delete().await() }
        runCatching { db.document("users/$me").delete().await() }
        user.delete().await()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** A fresh Google ID token for the website's sign-in (window.sorAppToken), so pages open already signed in. */
    suspend fun googleTokenForWeb(activity: Activity): String? {
        if (demo || !signedInWithGoogle) return null
        return runCatching { googleIdToken(activity, silent = true) }.getOrNull()
    }

    /** "Nearest meeting today" for a new action item, like the Action Items page. */
    fun nearestMeetingToday(track: Track): CalEvent? {
        val today = Day.iso(); val now = nowMs.toDouble()
        return events.filter { it.dayKey == today && it.track == track.name }.minByOrNull { abs(it.start - now) }
    }
}
