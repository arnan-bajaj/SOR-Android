package org.systemoverload.team.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Timeline: Pinboard (the big goals as sticky notes, in date order, with competitions where they fall)
 * and Schedule (every dated task by week, with what's running late).
 */
@Composable
fun TimelineScreen() {
    var mode by rememberSaveable { mutableStateOf("pinboard") }
    Page("Timeline", tint = Brand.violet) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(listOf("pinboard" to "Pinboard", "schedule" to "Schedule"), mode) { mode = it }
            Caption(if (mode == "pinboard") "The team's goals and each subteam's priorities, in date order." else "When everything is due, and what's running late.")
            if (mode == "pinboard") Pinboard() else Schedule()
        }
    }
}

private val noteTints = listOf(Brand.amber, Brand.blue, Brand.violet, Brand.green, Brand.pink)

private sealed class Entry { class G(val g: Goal, val n: Int) : Entry(); class C(val c: CalEvent) : Entry() }

@Composable
private fun Pinboard() {
    val goals = M.pinboardGoals
    val comps = M.upcoming.filter { it.isCompetition && !it.isNotCompeting }
    when {
        "tasks" !in M.loaded -> SkeletonRows(3)
        goals.isEmpty() -> Column(Modifier.fillMaxWidth().glass(22.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.PushPin, null, Modifier.size(36.dp), tint = Brand.amber)
            Text("No goals pinned up yet", style = T.headline)
            Text("Team goals and subteam priorities show up here as sticky notes. Open a task and turn on “This is an overall goal”.", style = T.sub, color = P.secondary)
        }
        else -> {
            // Competitions sit in the line where they fall.
            val out = mutableListOf<Entry>()
            var ci = 0
            goals.forEachIndexed { n, g ->
                while (ci < comps.size && g.task.dueDate.isNotEmpty() && comps[ci].dayKey <= g.task.dueDate) { out.add(Entry.C(comps[ci])); ci++ }
                out.add(Entry.G(g, n))
            }
            if (ci < comps.size && goals.last().task.dueDate.isNotEmpty()) out.add(Entry.C(comps[ci]))
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                out.forEachIndexed { i, e -> when (e) { is Entry.G -> Note(e.g, e.n, i % 2 == 0); is Entry.C -> CompBand(e.c) } }
            }
        }
    }
}

@Composable
private fun Note(g: Goal, n: Int, left: Boolean) {
    val nav = LocalNav.current
    val team = g.task.subteamId == "goals"
    val tint = if (team) noteTints[n % noteTints.size] else M.subteam(g.task.subteamId)?.color ?: Brand.blue
    val done = M.isDone(g.task)
    val shape = RoundedCornerShape(18.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier.fillMaxWidth().rotate(if (left) -1.2f else 1.2f).shadow(10.dp, shape).clip(shape).background(P.card)
                .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.34f), tint.copy(alpha = 0.14f)))).border(1.dp, tint.copy(alpha = 0.35f), shape)
                .clickable { nav.push(Screen.Task(g.task.id)) }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(n + 1), fontSize = 30.sp, fontWeight = FontWeight.Black, color = tint.copy(alpha = 0.9f), modifier = Modifier.weight(1f))
                Icon(Icons.Default.PushPin, null, Modifier.rotate(30f), tint = Brand.red)
            }
            Text(g.task.title, style = T.title3.copy(fontWeight = FontWeight.Bold), textDecoration = if (done) TextDecoration.LineThrough else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (team) "Team goal" else "${M.subteam(g.task.subteamId)?.name ?: "Subteam"} priority", style = T.caption, color = P.secondary)
                Text("·", style = T.caption, color = P.secondary)
                if (g.task.dueDate.isEmpty()) Text("no date yet", style = T.caption, color = P.secondary)
                else Text(if (done) "done" else "due ${Day.pretty(g.task.dueDate)}${if (g.late) " · late" else ""}", style = T.caption, color = if (g.late) Brand.red else P.secondary)
            }
            if (g.parts.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { g.done / g.parts.size.toFloat() }, Modifier.weight(1f), color = tint)
                Text("${g.done} of ${g.parts.size}", style = T.captionStrong, color = P.secondary)
            }
        }
        if (g.parts.isNotEmpty()) Column(Modifier.padding(start = 22.dp)) {
            g.parts.take(6).forEach { p -> PartRow(p) { nav.push(Screen.Task(p.id)) } }
            if (g.parts.size > 6) Text("+${g.parts.size - 6} more", style = T.caption, color = P.secondary, modifier = Modifier.padding(start = 30.dp, top = 4.dp))
        }
    }
}

@Composable
private fun PartRow(p: TaskItem, onClick: () -> Unit) {
    val done = M.isDone(p); val late = M.isLate(p)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (done) Icons.Default.CheckCircle else if (late) Icons.Default.Error else if (p.isGoal) Icons.Default.TrackChanges else Icons.Outlined.Circle, null,
            Modifier.size(20.dp), tint = if (done) Brand.green else if (late) Brand.red else M.subteam(p.subteamId)?.color ?: P.secondary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.title, style = T.sub.copy(fontWeight = FontWeight.Medium), color = if (done) P.secondary else P.text, textDecoration = if (done) TextDecoration.LineThrough else null)
            val who = p.assigneeUids.mapNotNull { M.person(it)?.firstName }
            Text(listOf(M.subteam(p.subteamId)?.name ?: "", who.take(2).joinToString(", "), if (p.dueDate.isEmpty()) "" else if (done) "done" else "due ${Day.pretty(p.dueDate)}")
                .filter { it.isNotEmpty() }.joinToString(" · "), style = T.caption, color = if (late) Brand.red else P.secondary)
        }
    }
}

@Composable
private fun CompBand(c: CalEvent) {
    val days = maxOf(0, Day.until(c.dayKey) ?: 0)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.wash(Brand.red)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.EmojiEvents, null, tint = Color.White)
        Column {
            Text(c.title, style = T.subStrong, color = Color.White)
            Text("${Day.fmt(c.day, "EEE, MMM d")} · in $days days", style = T.caption, color = Color.White)
        }
    }
}

@Composable
private fun Schedule() {
    val nav = LocalNav.current
    val dated = M.tasks.filter { it.effectiveDue.isNotEmpty() }.sortedWith(compareBy<TaskItem> { it.effectiveDue }.thenBy { it.position })
    val weeks = dated.groupBy { weekStart(it.effectiveDue) }
    val comps = M.events.filter { it.isCompetition && !it.isNotCompeting }
    val late = dated.filter { M.isLate(it) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${dated.count { !M.isDone(it) }}", "Dated, open", Icons.Default.CalendarToday, Brand.blue, Modifier.weight(1f))
            StatTile("${late.size}", "Running late", Icons.Default.Warning, if (late.isEmpty()) P.secondary else Brand.red, Modifier.weight(1f))
        }
        weeks.keys.sorted().forEach { w ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(weekLabel(w), style = T.headline, modifier = Modifier.weight(1f))
                    comps.filter { weekStart(it.dayKey) == w }.forEach { c ->
                        Icon(Icons.Default.EmojiEvents, null, Modifier.size(14.dp), tint = Brand.red)
                        Text(c.title, style = T.captionStrong, color = Brand.red, maxLines = 1)
                    }
                }
                weeks.getValue(w).forEach { t -> ScheduleRow(t) { nav.push(Screen.Task(t.id)) } }
            }
        }
        if (dated.isEmpty() && "tasks" in M.loaded) Text("No tasks have dates yet.", color = P.secondary)
    }
}

@Composable
private fun ScheduleRow(t: TaskItem, onClick: () -> Unit) {
    val done = M.isDone(t); val late = M.isLate(t)
    val c = M.subteam(t.subteamId)?.color ?: Brand.blue
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).glass(16.dp, if (late) Brand.red else null).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Day.fmt(t.effectiveDue, "EEE"), style = T.tiny, color = c)
            Text(Day.fmt(t.effectiveDue, "d"), style = T.headline.copy(fontWeight = FontWeight.Bold))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.title, style = T.subStrong, color = if (done) P.secondary else P.text, textDecoration = if (done || late) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubteamDot(c, 7.dp)
                Text(M.subteam(t.subteamId)?.name ?: "", style = T.caption, color = P.secondary)
                if (t.newDueDate.isNotEmpty()) Text("· moved from ${Day.pretty(t.dueDate)}", style = T.caption, color = P.secondary)
                if (late) Text(if (t.effectiveDue < Day.iso()) "· overdue" else "· waiting on a late task", style = T.caption, color = Brand.red)
            }
        }
        if (done) Icon(Icons.Default.CheckCircle, null, tint = Brand.green)
    }
}

private fun weekStart(iso: String): String {
    val d = Day.date(iso) ?: return iso
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    var x = d
    while (x.dayOfWeek != first) x = x.minusDays(1)
    return x.toString()
}

private fun weekLabel(iso: String): String {
    val thisWeek = weekStart(Day.iso())
    if (iso == thisWeek) return "This week"
    return "Week of ${Day.fmt(iso, "MMM d")}${if (iso < thisWeek) " · past" else ""}"
}

