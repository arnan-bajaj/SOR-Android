package org.systemoverload.team.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.systemoverload.team.model.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The helper: the website's Gemini chat (mod-assistant), in the app. The site server holds the key
 * (POST /api/assistant, site-server/lib/assistant.mjs) and checks who's asking. The app sends what it already shows
 * (open tasks, people, upcoming meetings, team-visible action items) and carries out the changes Gemini suggests,
 * but only after you tap Do it on its card. Every change is written as you, so firestore.rules decide what's allowed.
 * The function names and fields here must match TOOLS in lib/assistant.mjs (and RUN in index.html, and the iPhone app).
 */
class Helper(private val m: AppModel) {
    data class Plan(val title: String, val head: String, val lines: List<String>, val path: String? = null)
    sealed class CardState { data object Ask : CardState(); data object Doing : CardState(); data object Done : CardState(); data object No : CardState(); data class Failed(val msg: String) : CardState() }
    sealed class Item(val id: String) {
        class You(id: String, val text: String) : Item(id)
        class It(id: String, val text: String) : Item(id)
        class Note(id: String, val text: String) : Item(id)
        class Error(id: String, val text: String) : Item(id)
        class Card(id: String) : Item(id)
    }

    val items = mutableStateListOf<Item>()
    val cards = mutableStateMapOf<String, Pair<Plan, CardState>>()
    var busy by mutableStateOf(false)
    var off by mutableStateOf("")
    var onOpen: ((String) -> Unit)? = null

    private val history = mutableListOf<Map<String, Any?>>()
    private val decisions = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private var pendingText = ""
    private var running = false
    private val key get() = "sor-helper:${m.uid}"
    private fun newId() = UUID.randomUUID().toString()

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        items.add(Item.You(newId(), text))
        if (running) { pendingText += (if (pendingText.isEmpty()) "" else "\n") + text; return } // answered along with the cards
        history.add(mapOf("role" to "user", "parts" to listOf(mapOf("text" to text))))
        if (m.demo) { items.add(Item.It(newId(), "The helper isn't available in the demo.")); return }
        m.scope.launch { converse() }
    }

    fun decide(id: String, yes: Boolean) { decisions.remove(id)?.complete(yes) }

    fun clear() {
        if (running) return
        history.clear(); items.clear(); cards.clear()
        m.prefs.edit().remove(key).apply()
    }

    private suspend fun converse() {
        running = true; busy = true
        try {
            for (round in 0 until 5) {
                val parts = askServer()
                history.add(mapOf("role" to "model", "parts" to parts.ifEmpty { listOf(mapOf("text" to "")) }))
                val words = parts.filter { it["text"] is String && it["thought"] != true }.joinToString("") { it["text"] as String }.trim()
                if (words.isNotEmpty()) items.add(Item.It(newId(), words))
                @Suppress("UNCHECKED_CAST")
                val calls = parts.mapNotNull { it["functionCall"] as? Map<String, Any?> }
                if (calls.isEmpty()) break
                val reply = mutableListOf<Map<String, Any?>>()
                for (c in calls) {
                    val answer = handle(c)
                    val fr = mutableMapOf<String, Any?>("name" to (c["name"] as? String ?: ""), "response" to answer)
                    c["id"]?.let { fr["id"] = it }
                    reply.add(mapOf("functionResponse" to fr))
                }
                if (pendingText.isNotEmpty()) { reply.add(mapOf("text" to pendingText)); pendingText = "" }
                history.add(mapOf("role" to "user", "parts" to reply))
                busy = true
            }
        } catch (e: Exception) {
            items.add(Item.Error(newId(), e.message ?: "The helper couldn't answer. Try again."))
            // Back to the last time Gemini answered in words, so no half-finished exchange is left behind
            fun settled(c: Map<String, Any?>): Boolean {
                @Suppress("UNCHECKED_CAST") val parts = c["parts"] as? List<Map<String, Any?>> ?: emptyList()
                return c["role"] == "model" && parts.none { it["functionCall"] != null }
            }
            while (history.isNotEmpty() && !settled(history.last())) history.removeAt(history.size - 1)
        } finally {
            running = false; busy = false; pendingText = ""; save()
        }
    }

    private suspend fun askServer(): List<Map<String, Any?>> {
        val user = FirebaseAuth.getInstance().currentUser ?: throw AppError("Sign in again to use the helper.")
        val token = user.getIdToken(false).await().token ?: throw AppError("Sign in again to use the helper.")
        val body = JSONObject(mapOf("contents" to history, "context" to context())).toString()
        busy = true
        val (status, text) = withContext(Dispatchers.IO) {
            val c = URL("${AppModel.SITE}/api/assistant").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 60_000; c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("authorization", "Bearer $token")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to s
        }
        val out = runCatching { toMap(JSONObject(text)) }.getOrDefault(emptyMap())
        if (status !in 200..299) {
            if (out["off"] == true) off = out["error"] as? String ?: "The helper isn't available."
            throw AppError(out["error"] as? String ?: "The helper couldn't answer. Try again.")
        }
        @Suppress("UNCHECKED_CAST")
        return out["parts"] as? List<Map<String, Any?>> ?: emptyList()
    }

    // What Gemini gets to see (boardContext in mod-assistant)

    private fun context(): Map<String, Any?> {
        val name = { id: String -> m.person(id)?.name ?: "" }
        val shortNote = { s: String, n: Int -> val t = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "); if (t.length > n) t.take(n) + "…" else t }
        fun task(t: TaskItem): Map<String, Any?> {
            val d = mutableMapOf<String, Any?>("id" to t.id, "title" to t.title, "board" to t.subteamId, "column" to m.columnName(t.columnId),
                "people" to t.assigneeUids.map { "${name(it)} ($it)" })
            if (m.isDone(t)) d["done"] = true
            if (t.dueDate.isNotEmpty()) d["due"] = t.dueDate
            if (t.priority != Priority.med) d["priority"] = t.priority.name
            if (t.isGoal) d["goal"] = true
            val blocked = t.blockedBy.mapNotNull { m.task(it)?.title }
            if (blocked.isNotEmpty()) d["blockedBy"] = blocked
            if (t.description.isNotEmpty()) d["notes"] = shortNote(t.description, 220)
            if (t.commentCount > 0) d["comments"] = t.commentCount
            return d
        }
        val open = m.tasks.filter { !m.isDone(it) }.sortedBy { it.dueDate.ifEmpty { "9999" } }
        val done = m.tasks.filter { m.isDone(it) }.sortedByDescending { it.updatedAt }
        val now = nowMs.toDouble()
        val horizon = Day.inDays(28)
        val hm = { ms: Double -> Day.fmtMs(ms, "HH:mm") }
        val meetings = m.events.filter { it.end > now && it.dayKey <= horizon }.take(40).map { e ->
            val d = mutableMapOf<String, Any?>("id" to e.id, "title" to e.title, "day" to e.dayKey, "start" to if (e.allDay) "all day" else hm(e.start))
            if (!e.allDay) d["end"] = hm(e.end)
            if (e.place.isNotEmpty()) d["place"] = e.place else if (e.mode == "online") d["place"] = "Online"
            if (m.isCancelled(e)) d["cancelled"] = true
            if (m.amGoing(e.id)) d["youreGoing"] = true
            d
        }
        val items = m.actionItems.filter { it.seasonId == m.currentSeason.id }
            .sortedWith(compareBy<ActionItem> { it.done }.thenByDescending { it.assignedOn })
            .take(120).map { a ->
                val d = mutableMapOf<String, Any?>("id" to a.id, "text" to shortNote(a.text, 300), "track" to a.track,
                    "owners" to a.ownerUids.map { "${name(it)} ($it)" }, "assignedOn" to a.assignedOn)
                if (a.dueDate.isNotEmpty()) d["due"] = a.dueDate
                if (a.done) d["done"] = true
                d
            }
        val ctx = mutableMapOf<String, Any?>(
            "you" to mapOf("id" to m.uid, "name" to (m.profile?.name ?: ""), "subteams" to (m.profile?.subteamIds ?: emptyList()), "kind" to (m.profile?.memberType ?: "")),
            "onPage" to "the Android app",
            "season" to m.currentSeason.name,
            "boards" to m.boards.map { mapOf("id" to it.id, "name" to it.name) },
            "columns" to m.columns.sortedBy { it.position }.map { c ->
                val d = mutableMapOf<String, Any?>("id" to c.id, "name" to c.name)
                if (c.subteamIds.isNotEmpty()) d["onlyOn"] = c.subteamIds
                d
            },
            "people" to m.users.filter { it.role != Role.pending }.map { u ->
                val d = mutableMapOf<String, Any?>("id" to u.id, "name" to u.name, "canBeAssigned" to (u.role.rank >= Role.member.rank))
                if (u.subteamIds.isNotEmpty()) d["subteams"] = u.subteamIds
                if (u.memberType.isNotEmpty()) d["kind"] = u.memberType
                d
            },
            "openTasks" to open.take(260).map(::task),
            "recentlyDone" to done.take(40).map(::task),
            "upcomingMeetings" to meetings,
            "actionItems" to items,
        )
        m.nextCompetition?.let { ctx["nextCompetition"] = mapOf("title" to it.title, "day" to it.dayKey) }
        return ctx
    }

    // Changes it can make (RUN in mod-assistant)

    private suspend fun handle(call: Map<String, Any?>): Map<String, Any?> {
        val name = call["name"] as? String ?: ""
        @Suppress("UNCHECKED_CAST") val a = call["args"] as? Map<String, Any?> ?: emptyMap()
        if (name == "open_page") {
            val path = a.str("path")
            if (!Regex("^/[\\w\\-/]*$").matches(path)) return mapOf("ok" to false, "error" to "That isn't a page on the board.")
            items.add(Item.Note(newId(), "Opened $path"))
            onOpen?.invoke(path)
            return mapOf("ok" to true, "opened" to path)
        }
        val plan = try { plan(name, a) } catch (e: Exception) { return mapOf("ok" to false, "error" to e.message) }
        if (!m.canEdit) return mapOf("ok" to false, "error" to "This account can't make changes.")
        val id = newId()
        cards[id] = plan to CardState.Ask
        items.add(Item.Card(id))
        busy = false
        val wait = CompletableDeferred<Boolean>()
        decisions[id] = wait
        if (!wait.await()) { cards[id] = plan to CardState.No; return mapOf("ok" to false, "cancelled" to true, "note" to "The person said no. Don't do it.") }
        cards[id] = plan to CardState.Doing
        return try {
            val result = run(name, a)
            cards[id] = plan to CardState.Done
            result
        } catch (e: Exception) {
            val msg = AppModel.friendly(e)
            cards[id] = plan to CardState.Failed(msg)
            mapOf("ok" to false, "error" to msg)
        }
    }

    private fun assignable(ids: Any?): List<String> {
        val list = (ids as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return list.distinct().filter { (m.person(it)?.role?.rank ?: 0) >= Role.member.rank }
    }
    private fun okDay(v: Any?): String? {
        val s = v as? String ?: return null
        return if (s.isEmpty() || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) s else null
    }
    private fun needTask(id: String): TaskItem = m.task(id)?.takeIf { !it.archived } ?: throw AppError("I couldn't find that task.")
    private fun needItem(id: String): ActionItem = m.actionItems.firstOrNull { it.id == id } ?: throw AppError("I couldn't find that action item.")
    private fun names(ids: List<String>) = ids.mapNotNull { m.person(it)?.name }.joinToString(", ")

    private fun plan(name: String, a: Map<String, Any?>): Plan = when (name) {
        "create_task" -> {
            val board = m.boards.firstOrNull { it.id == a.str("subteamId") } ?: throw AppError("That board doesn't exist.")
            if (a.str("title").isBlank()) throw AppError("The task needs a title.")
            val people = assignable(a["assigneeIds"])
            val lines = mutableListOf(board.name)
            okDay(a["dueDate"])?.takeIf { it.isNotEmpty() }?.let { lines.add("Due ${Day.pretty(it)}") }
            if (people.isNotEmpty()) lines.add(names(people))
            Priority.of(a.str("priority"))?.takeIf { it != Priority.med }?.let { lines.add("${it.label} priority") }
            if (a.str("description").isNotEmpty()) lines.add(a.str("description").take(140))
            Plan("New task", a.str("title"), lines)
        }
        "update_task" -> {
            val t = needTask(a.str("taskId"))
            val lines = mutableListOf<String>()
            if (a.str("title").isNotEmpty() && a.str("title") != t.title) lines.add("Rename to “${a.str("title")}”")
            okDay(a["dueDate"])?.takeIf { it != t.dueDate }?.let { lines.add(if (it.isEmpty()) "No due date" else "Due ${Day.pretty(it)}") }
            Priority.of(a.str("priority"))?.takeIf { it != t.priority }?.let { lines.add("${it.label} priority") }
            val col = a.str("columnId")
            if (col.isNotEmpty() && col != t.columnId && m.columns.any { it.id == col }) lines.add("Move to ${m.columnName(col)}")
            if (a["assigneeIds"] is List<*>) { val p = assignable(a["assigneeIds"]); lines.add(if (p.isEmpty()) "Nobody on it" else "People: ${names(p)}") }
            (a["description"] as? String)?.takeIf { it != t.description }?.let { lines.add("New notes: ${it.take(120)}") }
            if (lines.isEmpty()) throw AppError("Nothing to change on that task.")
            Plan("Change task", t.title, lines, "/task/${t.id}")
        }
        "set_task_done" -> {
            val t = needTask(a.str("taskId"))
            Plan(if (a.bool("done")) "Mark done" else "Mark not done", t.title, listOf(m.subteam(t.subteamId)?.name ?: ""), "/task/${t.id}")
        }
        "comment_on_task" -> {
            val t = needTask(a.str("taskId"))
            if (a.str("text").isBlank()) throw AppError("The comment is empty.")
            Plan("Comment", t.title, listOf("“${a.str("text").take(240)}”"), "/task/${t.id}")
        }
        "create_action_item" -> {
            val track = Track.of(a.str("track")) ?: throw AppError("Action items go on Board, Build Committee or Operations.")
            if (a.str("text").isBlank()) throw AppError("The action item needs some words.")
            val owners = assignable(a["ownerIds"])
            val lines = mutableListOf(names(owners.ifEmpty { listOf(m.uid) }))
            okDay(a["dueDate"])?.takeIf { it.isNotEmpty() }?.let { lines.add("Due ${Day.pretty(it)}") }
            Plan("New action item · ${track.label}", a.str("text").take(200), lines)
        }
        "set_action_item_done" -> {
            val i = needItem(a.str("actionItemId"))
            Plan(if (a.bool("done")) "Tick off" else "Mark not done", i.text, listOf(Track.of(i.track)?.label ?: ""), "/action/${i.id}")
        }
        else -> throw AppError("No such function.")
    }

    private suspend fun run(name: String, a: Map<String, Any?>): Map<String, Any?> {
        when (name) {
            "create_task" -> {
                val col = m.columnsFor(a.str("subteamId")).firstOrNull { it.id == a.str("columnId") }?.id
                val id = m.createTask(a.str("title"), a.str("description"), a.str("subteamId"), col, assignable(a["assigneeIds"]),
                    okDay(a["dueDate"]) ?: "", Priority.of(a.str("priority")) ?: Priority.med)
                return mapOf("ok" to true, "taskId" to id)
            }
            "update_task" -> {
                val t = needTask(a.str("taskId"))
                val patch = mutableMapOf<String, Any?>()
                if (a.str("title").isNotEmpty()) patch["title"] = a.str("title").trim()
                okDay(a["dueDate"])?.let { patch["dueDate"] = it }
                Priority.of(a.str("priority"))?.let { patch["priority"] = it.name }
                val col = a.str("columnId")
                if (col.isNotEmpty() && col != t.columnId && m.columns.any { it.id == col }) {
                    patch["columnId"] = col
                    if (col == m.doneColumnId) patch["prevColumnId"] = t.columnId
                }
                if (a["assigneeIds"] is List<*>) patch["assigneeUids"] = assignable(a["assigneeIds"])
                (a["description"] as? String)?.let { patch["description"] = it }
                m.update(t, patch)
                return mapOf("ok" to true)
            }
            "set_task_done" -> {
                val t = needTask(a.str("taskId"))
                if (a.bool("done") == m.isDone(t)) return mapOf("ok" to true, "note" to "It was already like that.")
                if (a.bool("done")) m.update(t, mapOf("columnId" to m.doneColumnId, "prevColumnId" to t.columnId))
                else {
                    val cols = m.columnsFor(t.subteamId)
                    val back = cols.firstOrNull { it.id == "doing" } ?: cols.firstOrNull { it.id != m.doneColumnId }
                    m.update(t, mapOf("columnId" to (back?.id ?: t.columnId)))
                }
                return mapOf("ok" to true)
            }
            "comment_on_task" -> { m.addComment(needTask(a.str("taskId")), a.str("text").trim()); return mapOf("ok" to true) }
            "create_action_item" -> {
                m.addActionItem(a.str("text"), Track.of(a.str("track")) ?: Track.board, assignable(a["ownerIds"]), okDay(a["dueDate"]) ?: "", false)
                return mapOf("ok" to true)
            }
            "set_action_item_done" -> { m.setDone(needItem(a.str("actionItemId")), a.bool("done")); return mapOf("ok" to true) }
            else -> return mapOf("ok" to false, "error" to "No such function.")
        }
    }

    // Keeping the chat between visits (this phone only)

    private fun save() {
        val words = items.takeLast(60).mapNotNull {
            when (it) { is Item.You -> mapOf("k" to "you", "t" to it.text); is Item.It -> mapOf("k" to "it", "t" to it.text); else -> null }
        }
        val json = JSONObject(mapOf("history" to history.takeLast(40), "view" to words)).toString()
        m.prefs.edit().putString(key, json).apply()
    }

    fun restore() {
        if (items.isNotEmpty()) return
        val raw = m.prefs.getString(key, null) ?: return
        val obj = runCatching { toMap(JSONObject(raw)) }.getOrNull() ?: return
        @Suppress("UNCHECKED_CAST")
        history.clear(); history.addAll(obj["history"] as? List<Map<String, Any?>> ?: emptyList())
        @Suppress("UNCHECKED_CAST")
        (obj["view"] as? List<Map<String, Any?>> ?: emptyList()).forEach {
            items.add(if (it["k"] == "you") Item.You(newId(), it["t"] as? String ?: "") else Item.It(newId(), it["t"] as? String ?: ""))
        }
    }

    companion object {
        fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> unwrap(o.get(k)) }
        private fun unwrap(v: Any?): Any? = when (v) {
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}
