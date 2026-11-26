@file:OptIn(ExperimentalMaterial3Api::class)

package org.systemoverload.team.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Action Items: Board, Build Committee and Operations. Items are handed out at meetings, so they're grouped by the
 * meeting day they were assigned at, newest first (never by Mon–Sun weeks).
 */
@Composable
fun ActionItemsScreen(boardRoom: Boolean = false, startTrack: Track = Track.board) {
    val nav = LocalNav.current
    var track by rememberSaveable { mutableStateOf(startTrack) }
    var part by rememberSaveable { mutableStateOf("todos") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    Page(if (boardRoom) "Board Room" else "Action Items", tint = Brand.teal, actions = {
        IconButton(onClick = { showDone = !showDone }) { Icon(if (showDone) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (showDone) "Hide done" else "Show done") }
        if (if (boardRoom) M.isBoardMember else M.canEdit) IconButton(onClick = { nav.push(Screen.NewActionItem(if (boardRoom) Track.board else track, boardRoom)) }) { Icon(Icons.Default.Add, "New action item") }
    }) {
        if (!boardRoom) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(Track.entries.map { it to it.label }, track) { track = it; if (it == Track.ops && part == "reminders") part = "todos" }
                Segmented(listOf("todos" to "To-dos", "agendas" to "Agendas") + if (track != Track.ops) listOf("reminders" to "Reminders") else emptyList(), part) { part = it } // Operations has no Slack channel
            }
        }
        when {
            boardRoom || part == "todos" -> ItemList(boardRoom, track, showDone)
            part == "agendas" -> AgendaDocsList(track)
            else -> TeamReminders(track)
        }
    }
}

@Composable
fun ItemList(boardRoom: Boolean, track: Track, showDone: Boolean) {
    val nav = LocalNav.current
    val list = if (boardRoom) M.boardRoomItems else M.items(track)
    val items = list.filter { showDone || !it.done }
    val groups = items.groupBy { it.day }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if ((if (boardRoom) "privateActionItems" else "actionItems") !in M.loaded && !M.demo) item { SkeletonRows(4) }
        else if (items.isEmpty()) item { EmptyNote(if (list.isEmpty()) "No action items yet this season." else "Everything's done.") }
        groups.keys.sortedDescending().forEach { day ->
            item(key = "h$day") { Text(Day.fmt(day, "EEEE, MMMM d"), style = T.subStrong, color = P.secondary, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
            items(groups.getValue(day).sortedWith(compareBy<ActionItem> { it.done }.thenBy { it.createdAt }), key = { it.id }) { a ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { nav.push(Screen.ActionItem(a.id, a.confidential)) }.glass(16.dp).padding(12.dp)) {
                    ActionItemRow(a)
                }
            }
        }
    }
}

@Composable
fun ActionItemRow(item: ActionItem, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.clip(CircleShape).clickable(enabled = M.canChange(item)) { M.setDone(item, !item.done) }) {
            Icon(if (item.done) Icons.Default.CheckCircle else Icons.Outlined.Circle, if (item.done) "Mark not done" else "Mark done",
                Modifier.size(24.dp), tint = if (item.done) Brand.green else P.secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.text, style = T.body, color = if (item.done) P.secondary else P.text, textDecoration = if (item.done) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.ownerUids.isNotEmpty()) {
                    AvatarStack(item.ownerUids, 18.dp)
                    Text(item.ownerUids.mapNotNull { M.person(it)?.firstName }.joinToString(", "), style = T.caption, color = P.secondary)
                }
                DuePill(item.dueDate, item.done)
            }
        }
    }
}

@Composable
fun NewActionItemScreen(startTrack: Track, confidential: Boolean) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var track by rememberSaveable { mutableStateOf(startTrack) }
    var owners by rememberSaveable { mutableStateOf(listOf<String>()) }
    var due by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Page(if (confidential) "Confidential action item" else "New action item", tint = Brand.teal, actions = {
        if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 8.dp), strokeWidth = 2.dp)
        else TextButton(onClick = {
            saving = true
            scope.launch {
                try { M.addActionItem(text, track, owners, due, confidential); M.toast = "Action item added"; nav.pop() }
                catch (e: Exception) { M.toast = AppModel.friendly(e); saving = false }
            }
        }, enabled = text.isNotBlank()) { Text("Add", fontWeight = FontWeight.SemiBold) }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            FormSection {
                PlainField(text, { text = it }, "What needs doing?", style = T.title3)
            }
            FormSection {
                if (!confidential) { ChoiceRow("Meeting", track, Track.entries.map { it to it.label }) { track = it }; RowDivider() }
                ValueRow("Who", if (owners.isEmpty()) "You" else owners.mapNotNull { M.person(it)?.firstName }.joinToString(", "), { picking = true })
                RowDivider()
                DayRow("Due date", due, addDefault = { Day.inDays(7) }) { due = it }
            }
            Caption("It's filed under today's meeting.${if (confidential) " Confidential: only the Board and lead mentors see it." else ""}", modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        }
    }
    if (picking) PeoplePicker("Who", owners, { picking = false }) { owners = it }
}

/** One action item, like the website's sheet: words, who, due date, Slack reminders, done, make it a task, share, delete. */
@Composable
fun ActionItemDetailScreen(itemId: String, confidential: Boolean) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = (if (confidential) M.privateActionItems else M.actionItems).firstOrNull { it.id == itemId }
    var picking by remember { mutableStateOf(false) }
    var promoting by remember { mutableStateOf(false) }
    if (a == null) { Page("Action item", Brand.teal) { EmptyNote("This action item is gone.", Modifier.padding(top = 60.dp)) }; return }
    val edit = M.canChange(a)
    var text by remember(a.id) { mutableStateOf(a.text) }
    DisposableEffect(a.id) { onDispose { val v = text.trim(); if (v.isNotEmpty() && v != a.text && edit) M.patch(a, mapOf("text" to v)) } }
    Page(if (confidential) "Confidential item" else "Action item", Brand.teal) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            FormSection {
                PlainField(text, { text = it }, "What needs doing?", style = T.title3, enabled = edit)
                RowDivider()
                ToggleRow("Done", a.done, enabled = edit, icon = Icons.Default.CheckCircle) { M.setDone(a, it) }
            }
            FormSection {
                if (!confidential) { ChoiceRow("Meeting", Track.of(a.track) ?: Track.board, Track.entries.map { it to it.label }, edit) { M.patch(a, mapOf("track" to it.name)) }; RowDivider() }
                FormRow(onClick = { picking = true }, enabled = edit) {
                    Text("Who", style = T.body, modifier = Modifier.weight(1f))
                    if (a.ownerUids.isEmpty()) Text("Nobody", color = P.secondary)
                    else { AvatarStack(a.ownerUids, 20.dp); Text(a.ownerUids.mapNotNull { M.person(it)?.firstName }.joinToString(", "), color = P.secondary, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp)) }
                }
                RowDivider()
                DayRow("Due", a.dueDate, edit, addLabel = "Add a due date", addDefault = { Day.inDays(7) }) { M.patch(a, mapOf("dueDate" to it)) }
                RowDivider()
                ValueRow("Assigned", Day.fmt(a.day, "EEEE, MMM d"))
            }
            if (!confidential) FormSection("Remind in Slack",
                if (a.dueDate.isEmpty()) "Give it a due date to get reminders."
                else "Posted in the ${Track.of(a.track)?.label ?: "meeting"}'s Slack channel when that much of the time from the meeting to the due date has gone by. The people on it get a push too.") {
                ReminderChips(a.reminders, edit && a.dueDate.isNotEmpty()) { M.patch(a, mapOf("reminders" to it)) }
            }
            FormSection {
                val promoted = a.promotedTaskId.takeIf { it.isNotEmpty() }?.let { M.task(it) }
                if (promoted != null) {
                    FormRow(onClick = { nav.push(Screen.Task(promoted.id)) }) { Icon(Icons.Default.OpenInNew, null, tint = P.accent); Text("Open its task: ${promoted.title}", color = P.accent) }
                    RowDivider()
                } else if (M.canEdit && !confidential) {
                    FormRow(onClick = {
                        promoting = true
                        scope.launch {
                            try { M.promote(a); M.toast = "Added to the board" } catch (e: Exception) { M.toast = AppModel.friendly(e) }
                            promoting = false
                        }
                    }, enabled = !promoting) {
                        Icon(Icons.Default.Upload, null, tint = P.accent); Text("Make it a task", color = P.accent, modifier = Modifier.weight(1f))
                        if (promoting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    RowDivider()
                }
                FormRow(onClick = { shareText(context, "${AppModel.SITE}/action/${a.id}") }) { Icon(Icons.Default.Link, null, tint = P.accent); Text("Share link", color = P.accent) }
                if (M.canRemove(a)) {
                    RowDivider()
                    FormRow(onClick = { M.deleteItem(a); nav.pop() }) { Icon(Icons.Default.Delete, null, tint = Brand.red); Text("Delete", color = Brand.red) }
                }
            }
        }
    }
    if (picking) PeoplePicker("Who", a.ownerUids, { picking = false }) { M.patch(a, mapOf("ownerUids" to it, "ownerUid" to "")) }
}

/** Action Items › Reminders (Board and Build Committee): a note the bot posts in that meeting's Slack channel on a date. */
@Composable
fun TeamReminders(track: Track) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(Day.inDays(1)) }
    var time by rememberSaveable { mutableStateOf("16:00") }
    var saving by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { M.wantTeamReminders() }
    val now = nowMs.toDouble()
    val mine = M.teamReminders.filter { it.track == track.name }
    val upcoming = mine.filter { it.sentAt == 0.0 && it.sendAt > now - 7 * 86_400_000 }.sortedBy { it.sendAt }
    val sent = mine.filter { it.sentAt > 0 }.sortedByDescending { it.sendAt }.take(10)
    val channel = M.reminderChannel(track)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
        if (M.canEdit) FormSection("New",
            if (channel.isEmpty()) "${track.label} has no Slack channel yet, so reminders can't go out. An admin sets one on Team › Subteams."
            else "Posted once in ${if (channel.startsWith("C") && channel.length > 8) "its private Slack channel" else "#$channel"} at that time.") {
            PlainField(text, { text = it }, "Remind us to…")
            RowDivider()
            DayRow("Day", date) { if (it.isNotEmpty()) date = it }
            RowDivider()
            ValueRow("Time", LocalTime.parse(time).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")), { pickingTime = true })
            RowDivider()
            FormRow(onClick = {
                saving = true
                scope.launch {
                    try {
                        val at = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
                        M.addTeamReminder(text, track, at)
                        M.toast = "The bot will post it ${Day.fmt(at.toLocalDate(), "EEEE, MMM d")} at ${at.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))}."
                        text = ""
                    } catch (e: Exception) { M.toast = if (e is AppError) e.message else AppModel.friendly(e) }
                    saving = false
                }
            }, enabled = text.isNotBlank() && !saving) {
                Text("Add reminder", color = if (text.isNotBlank()) P.accent else P.secondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        FormSection("Coming up · ${upcoming.size}") {
            if (upcoming.isEmpty()) Text("Nothing scheduled.", color = P.secondary, modifier = Modifier.padding(14.dp))
            upcoming.forEach { ReminderRow(it) }
        }
        if (sent.isNotEmpty()) FormSection("Sent") { sent.forEach { ReminderRow(it, faded = true) } }
    }
    if (pickingTime) {
        val t = LocalTime.parse(time)
        val state = rememberTimePickerState(t.hour, t.minute, false)
        AlertDialog(onDismissRequest = { pickingTime = false },
            confirmButton = { TextButton(onClick = { time = "%02d:%02d".format(state.hour, state.minute); pickingTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReminderRow(r: TeamReminder, faded: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val canDelete = M.isLead || r.createdBy == M.uid
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { if (canDelete) menu = true }).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.text, style = T.body, color = if (faded) P.secondary else P.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (r.sentAt > 0) Icons.Default.CheckCircle else Icons.Default.Schedule, null, Modifier.size(14.dp), tint = P.secondary)
                Text(Day.fmtMs(r.sendAt, "EEE, MMM d, h:mm a") + (M.person(r.createdBy)?.let { " · ${it.firstName}" } ?: ""), style = T.caption, color = P.secondary)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Delete", color = Brand.red) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Brand.red) },
                onClick = { menu = false; M.deleteTeamReminder(r) })
        }
    }
}
