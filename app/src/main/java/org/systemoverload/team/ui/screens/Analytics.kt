package org.systemoverload.team.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*
import java.io.File
import java.time.temporal.WeekFields
import java.util.Locale

/** Analytics: how the team is doing, worked out live. Students (attendance scores), Tasks (where the work is). */
@Composable
fun AnalyticsScreen() {
    var tab by rememberSaveable { mutableStateOf("students") }
    LaunchedEffect(Unit) { M.wantTeamAttendance() }
    Page("Analytics", tint = Brand.teal) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(listOf("students" to "Students", "tasks" to "Tasks"), tab) { tab = it }
            if (tab == "students") Students() else TasksStats()
        }
    }
}

@Composable
private fun Students() {
    val rows = M.leaderboard
    when {
        "teamAttendance" !in M.loaded && !M.demo -> SkeletonRows(5)
        rows.isEmpty() -> Text("No attendance marked yet this season.", color = P.secondary)
        else -> {
            val avg = rows.map { it.score.score }.average()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${rows.size}", "Students", Icons.Default.Groups, Brand.blue, Modifier.weight(1f))
                StatTile("%.1f".format(avg), "Average score", Icons.Default.BarChart, Brand.teal, Modifier.weight(1f))
                StatTile("${rows.sumOf { it.score.unexcused }}", "Unexcused", Icons.Default.Cancel, Brand.red, Modifier.weight(1f))
            }
            Weekly()
            CardBox {
                Text("Scores", style = T.headline)
                val top = rows.take(15)
                val best = maxOf(top.maxOf { it.score.score }, 0.01)
                top.forEach { r ->
                    val mine = r.uid == M.uid
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(r.name.split(" ").first(), style = T.caption.copy(fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal), color = P.secondary, maxLines = 1, modifier = Modifier.width(76.dp))
                        Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.fillMaxWidth((r.score.score / best).toFloat().coerceIn(0.02f, 1f)).height(12.dp).clip(CircleShape).background(Brand.wash(if (mine) Brand.amber else Brand.teal)))
                        }
                        Text(fmtScore(r.score.score), style = T.captionStrong, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                    }
                }
            }
            CardBox(padding = 16.dp) {
                Text("Leaderboard", style = T.headline)
                rows.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${r.place}", style = T.subStrong, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp),
                            color = if (r.place <= 3) listOf(Brand.yellow, Brand.gray, Brand.orange)[r.place - 1] else P.secondary)
                        PersonAvatar(r.uid, 28.dp)
                        Text(r.name, style = T.sub.copy(fontWeight = if (r.uid == M.uid) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f))
                        if (r.score.unexcused > 0) Text("${r.score.unexcused}A", style = T.captionStrong, color = Brand.red)
                        Text(fmtScore(r.score.score), style = T.subStrong)
                    }
                    if (i < rows.size - 1) HorizontalDivider(color = P.hairline)
                }
            }
            Caption("Present counts 1, Partial ½, online a third; only counted meetings score, and the Board isn't ranked. The full grid and roster are on the website's Attendance page.")
        }
    }
}

/** Present or partial marks per week, the whole team. */
@Composable
private fun Weekly() {
    val wf = WeekFields.of(Locale.getDefault())
    val marks = M.teamMarks.filter { it.status in listOf("present", "partial") && it.date >= M.seasonFrom && it.kind !in listOf("parent", "mentor") }
    val byWeek = marks.groupBy { m -> Day.date(m.date)?.with(wf.dayOfWeek(), 1)?.toString() ?: m.date }
    val points = byWeek.map { it.key to it.value.size }.sortedBy { it.first }.takeLast(12)
    CardBox {
        Text("Check-ins by week", style = T.headline)
        val line = Brand.teal
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            if (points.isEmpty()) return@Canvas
            val maxV = maxOf(points.maxOf { it.second }, 1).toFloat()
            val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
            val pts = points.mapIndexed { i, p -> Offset(if (points.size > 1) i * stepX else size.width / 2, size.height - (p.second / maxV) * (size.height - 12f) - 6f) }
            val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            val fill = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.5f), line.copy(alpha = 0.02f))))
            drawPath(path, line, style = Stroke(5f))
            pts.forEach { drawCircle(line, 7f, it) }
        }
        if (points.isNotEmpty()) Row {
            Text(Day.fmt(points.first().first, "MMM d"), style = T.caption, color = P.secondary, modifier = Modifier.weight(1f))
            Text(Day.fmt(points.last().first, "MMM d"), style = T.caption, color = P.secondary)
        }
    }
}

@Composable
private fun TasksStats() {
    val all = M.tasks
    val open = all.filter { !M.isDone(it) }
    val overdue = open.filter { it.effectiveDue.isNotEmpty() && it.effectiveDue < Day.iso() }
    val fortnight = nowMs - 14 * 86_400_000.0
    val recentlyDone = all.filter { M.isDone(it) && it.updatedAt > fortnight }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("${open.size}", "Open", Icons.Default.RadioButtonUnchecked, Brand.blue, Modifier.weight(1f))
        StatTile("${overdue.size}", "Overdue", Icons.Default.Warning, if (overdue.isEmpty()) P.secondary else Brand.red, Modifier.weight(1f))
        StatTile("${recentlyDone.size}", "Done, 2 weeks", Icons.Default.CheckCircle, Brand.green, Modifier.weight(1f))
    }
    val colors = mapOf("todo" to Brand.blue, "doing" to Brand.amber, "done" to Brand.green, "grants" to Brand.violet)
    val byBoard = M.boards.map { b -> b to M.columnsFor(b.id).map { c -> c to all.count { it.subteamId == b.id && it.columnId == c.id } }.filter { it.second > 0 } }
    val most = maxOf(byBoard.maxOfOrNull { it.second.sumOf { p -> p.second } } ?: 1, 1)
    CardBox {
        Text("By board", style = T.headline)
        byBoard.forEach { (b, parts) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(b.name, style = T.caption, color = P.secondary, maxLines = 1, modifier = Modifier.width(96.dp))
                Row(Modifier.weight(1f).height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    parts.forEach { (c, n) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().clip(CircleShape).background(colors[c.id] ?: Brand.teal)) }
                    val rest = most - parts.sumOf { it.second }
                    if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
                    if (parts.isEmpty()) Spacer(Modifier.weight(1f))
                }
                Text("${parts.sumOf { it.second }}", style = T.captionStrong, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            (listOf("To Do" to Brand.blue, "In Progress" to Brand.amber, "Done" to Brand.green) +
                if (byBoard.any { it.second.any { p -> p.first.id == "grants" } }) listOf("Grants" to Brand.violet) else emptyList()).forEach { (n, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp)); Text(n, style = T.caption, color = P.secondary) }
            }
        }
    }
    val owners = open.flatMap { it.assigneeUids }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)
    if (owners.isNotEmpty()) CardBox {
        Text("Most on their plate", style = T.headline)
        owners.forEach { (uid, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonAvatar(uid, 28.dp)
                Text(M.person(uid)?.name ?: "Someone", style = T.sub, modifier = Modifier.weight(1f))
                Text("$n open", style = T.subStrong, color = P.secondary)
            }
        }
    }
}

/** Every message sent to the helper (Gemini), newest first. Only reviewers (assistantAccess/reviewers) can read it. */
@Composable
fun HelperChatsScreen() {
    var rows by remember { mutableStateOf<List<HelperLog>?>(null) }
    var failed by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableLongStateOf(200L) }
    val scope = rememberCoroutineScope()
    suspend fun load() { try { rows = M.helperLogs(limit) } catch (e: Exception) { failed = "Couldn't load the chats: ${AppModel.friendly(e)}" } }
    LaunchedEffect(limit) { load() }
    Page("Helper chats", tint = Brand.violet) {
        SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp), "Search by name or words")
        val r = rows
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                r == null && failed.isNotEmpty() -> item { Text(failed, color = P.secondary) }
                r == null -> item { SkeletonRows(5) }
                else -> {
                    val shown = if (query.isEmpty()) r else r.filter { listOf(it.name, it.said, it.reply).any { s -> s.contains(query, ignoreCase = true) } }
                    if (shown.isEmpty()) item { EmptyNote(if (r.isEmpty()) "No chats yet." else "Nothing matches.") }
                    shown.groupBy { it.day }.toSortedMap(compareByDescending { it }).forEach { (day, list) ->
                        item(key = "d$day") { Text(Day.fmt(day, "EEEE, MMM d"), style = T.subStrong, color = P.secondary, modifier = Modifier.padding(top = 8.dp)) }
                        items(list, key = { it.id }) { e ->
                            Column(Modifier.fillMaxWidth().glass(16.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(e.name.ifEmpty { "Someone" }, style = T.subStrong)
                                    Text(Day.fmtMs(e.at, "h:mm a"), style = T.caption, color = P.secondary)
                                    if (e.status != 0 && e.status != 200) Pill("Error ${e.status}", tint = Brand.red)
                                }
                                if (e.said.isNotEmpty()) Text(e.said)
                                if (e.reply.isNotEmpty()) Text(e.reply, style = T.sub, color = P.secondary)
                                if (e.calls.isNotEmpty()) Text("Suggested: ${e.calls.joinToString(", ")}", style = T.caption, color = Brand.violet)
                                if (e.error.isNotEmpty()) Text(e.error, style = T.caption, color = Brand.red)
                            }
                        }
                    }
                    if (r.size >= limit) item { TextButton(onClick = { limit += 200; scope.launch { load() } }, Modifier.fillMaxWidth()) { Text("Show older") } }
                }
            }
        }
    }
}

/** What the Slack bot keeps from #meeting-signups, as text to save or share. They name students: leads only. */
@Composable
fun SignupRecordsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    fun get(which: String) {
        busy = which
        scope.launch {
            try {
                val (name, text) = if (which == "log") {
                    val t = M.signupLogText() ?: run { M.toast = "Nothing in the log yet."; busy = ""; return@launch }
                    "sor-signup-log-${Day.iso()}.txt" to t
                } else {
                    val x = M.latestExportText() ?: run { M.toast = "No weekly export yet."; busy = ""; return@launch }
                    "sor-signups-attendance-${x.first}.txt" to x.second
                }
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "records").apply { mkdirs() }.resolve(name).apply { writeText(text) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
            } catch (e: Exception) {
                M.toast = AppModel.friendly(e).let { if ("permission" in it) "Only leads can download these." else it }
            }
            busy = ""
        }
    }
    Page("Signup records") {
        FormSection(footer = "Plain text files. They name students, so keep them off shared drives and out of Slack.") {
            RecordRow("Slack log", "Every message in #meeting-signups and every signup the bot made", Icons.AutoMirrored.Filled.Chat, Brand.blue, busy == "log", busy.isEmpty()) { get("log") }
            RowDivider()
            RecordRow("Weekly export", "All signups and attendance, as of the latest week", Icons.Default.TableChart, Brand.teal, busy == "export", busy.isEmpty()) { get("export") }
        }
    }
}

@Composable
private fun RecordRow(title: String, hint: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FormRow(onClick = onClick, enabled = enabled) {
        IconTile(icon, color, 34.dp)
        Column(Modifier.weight(1f)) { Text(title); Text(hint, style = T.caption, color = P.secondary) }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
