package org.systemoverload.team.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*

@Composable
fun TaskDetailScreen(taskId: String) {
    val t = M.task(taskId)
    if (t == null) {
        Page("Task") { EmptyNote("This task is gone. Someone archived or deleted it.", Modifier.padding(top = 60.dp)) }
        return
    }
    TaskDetail(t)
}

/** Saves a text field when you leave it, like the website's editor. */
@Composable
private fun EditField(value: String, placeholder: String, enabled: Boolean, style: androidx.compose.ui.text.TextStyle = T.body, minLines: Int = 1,
                      keyboard: KeyboardOptions = KeyboardOptions.Default, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    PlainField(text, { text = it }, placeholder, Modifier.onFocusChanged {
        if (focused && !it.isFocused && text != value) onSave(text)
        focused = it.isFocused
    }, style = style, enabled = enabled, minLines = minLines, keyboard = keyboard)
    DisposableEffect(Unit) { onDispose { if (text != value) onSave(text) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskDetail(t: TaskItem) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edit = M.canEdit
    var comments by remember { mutableStateOf(listOf<Comment>()) }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var newItem by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) } // owners | backups | deps | parts
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }

    DisposableEffect(t.id) {
        M.wantAgendas()
        val stop = M.comments(t.id) { comments = it }
        onDispose { stop() }
    }
    if (M.demo) LaunchedEffect(M.demoComments) { comments = M.demoComments[t.id] ?: emptyList() }

    Page(M.subteam(t.subteamId)?.name ?: "Task", tint = M.subteam(t.subteamId)?.color ?: Brand.blue) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 32.dp)) {
            FormSection {
                EditField(t.title, "Title", edit, T.title3) { v -> if (v.isNotBlank() && v.trim() != t.title) M.update(t, mapOf("title" to v.trim())) }
                M.subteam(t.subteamId)?.let { s ->
                    Row(Modifier.padding(start = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SubteamDot(s.color); Spacer(Modifier.width(6.dp)); Text(s.name, style = T.sub, color = P.secondary)
                    }
                }
            }
            FormSection {
                ChoiceRow("Status", t.columnId, M.columnsFor(t.subteamId).map { it.id to it.name }, edit) { M.move(t, it) }
                RowDivider()
                ChoiceRow("Priority", t.priority, Priority.entries.map { it to it.label }, edit) { M.update(t, mapOf("priority" to it.name)) }
                RowDivider()
                DayRow("Due", t.dueDate, edit, addLabel = "Add a due date", addDefault = { Day.inDays(7) }) { M.update(t, mapOf("dueDate" to it)) }
                RowDivider()
                FormRow(onClick = { picking = "owners" }, enabled = edit) {
                    Text("Owner", style = T.body, modifier = Modifier.weight(1f))
                    if (t.assigneeUids.isEmpty()) Text("Nobody", color = P.secondary)
                    else { AvatarStack(t.assigneeUids); Text(t.assigneeUids.mapNotNull { M.person(it)?.firstName }.joinToString(", "), color = P.secondary, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp)) }
                }
            }
            FormSection("Remind in Slack",
                if (t.dueDate.isEmpty()) "Give it a due date to get reminders." else "Posted in the board's Slack channel when that much of the time to the due date has gone by. The people on it get a push too.") {
                ReminderChips(t.reminders, edit && t.dueDate.isNotEmpty()) { M.update(t, mapOf("reminders" to it)) }
            }
            GoalSection(t, edit) { picking = "parts" }
            FormSection("Details") {
                ValueRow("Backup", if (t.backupUids.isEmpty()) "Nobody" else t.backupUids.mapNotNull { M.person(it)?.firstName }.joinToString(", "), { picking = "backups" }, edit)
                RowDivider()
                DayRow("Start date", t.startDate, edit) { M.update(t, mapOf("startDate" to it)) }
                if (t.dueDate.isNotEmpty() || t.newDueDate.isNotEmpty()) {
                    RowDivider()
                    DayRow("Second deadline", t.newDueDate, edit) { M.update(t, mapOf("newDueDate" to it)) }
                }
                RowDivider()
                ValueRow("Blocked by", if (t.blockedBy.isEmpty()) "Nothing" else t.blockedBy.mapNotNull { M.task(it)?.title }.joinToString(", "), { picking = "deps" }, edit)
                t.blockedBy.mapNotNull { M.task(it) }.forEach { d ->
                    FormRow(onClick = { nav.push(Screen.Task(d.id)) }) {
                        Icon(if (M.isDone(d)) Icons.Default.CheckCircle else if (M.isLate(d)) Icons.Default.Error else Icons.Outlined.Circle, null,
                            Modifier.size(18.dp), tint = if (M.isLate(d) && !M.isDone(d)) Brand.red else P.secondary)
                        Text(d.title, style = T.sub, color = if (M.isDone(d)) P.secondary else P.text)
                    }
                }
                RowDivider()
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Est. hours", style = T.body)
                    EditField(t.estHours, "0", edit, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)) { M.update(t, mapOf("estHours" to it)) }
                }
                RowDivider()
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Subsystem", style = T.body)
                    EditField(t.subsystem, "e.g. Intake", edit) { M.update(t, mapOf("subsystem" to it)) }
                }
                RowDivider()
                ToggleRow("Flag for attention", t.flagged, enabled = edit, icon = Icons.Default.Flag) { M.update(t, mapOf("flagged" to it)) }
                RowDivider()
                Text("Definition of done", style = T.caption, color = P.secondary, modifier = Modifier.padding(start = 14.dp, top = 10.dp))
                EditField(t.dod, "How will you know this is finished?", edit) { M.update(t, mapOf("dod" to it)) }
            }
            PhotosSection(t, edit && !M.isDone(t)) { reporting = it }
            val agendas = M.agendas.filter { !it.removed && t.id in it.taskIds }
            if (agendas.isNotEmpty()) FormSection("On hangar agendas") {
                agendas.forEach { a -> FormRow(onClick = { nav.push(Screen.Agenda(a.id)) }) { Icon(Icons.Default.ListAlt, null, tint = P.secondary); Text("${a.title} · ${Day.pretty(a.date)}") } }
            }
            FormSection("Notes") { EditField(t.description, "Add notes", edit, minLines = 3) { M.update(t, mapOf("description" to it)) } }
            FormSection("Checklist") {
                t.checklist.forEachIndexed { i, item ->
                    FormRow(onClick = { M.setChecklist(t, t.checklist.toMutableList().also { it[i] = item.copy(done = !item.done) }) }, enabled = edit) {
                        Icon(if (item.done) Icons.Default.CheckCircle else Icons.Outlined.Circle, null, tint = if (item.done) Brand.green else P.secondary)
                        Text(item.text, style = T.body, color = if (item.done) P.secondary else P.text, textDecoration = if (item.done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                        if (edit) Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp).clip(CircleShape).clickable { M.setChecklist(t, t.checklist.filterIndexed { j, _ -> j != i }) }, tint = P.tertiary)
                    }
                    RowDivider()
                }
                if (edit) Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AddCircleOutline, null, tint = P.secondary)
                    PlainField(newItem, { newItem = it }, "Add a step", singleLine = true, keyboard = KeyboardOptions(imeAction = ImeAction.Done))
                    val text = newItem.trim()
                    if (text.isNotEmpty()) TextButton(onClick = { M.setChecklist(t, t.checklist + ChecklistItem(text, false)); newItem = "" }) { Text("Add") }
                }
            }
            FormSection("Comments") {
                val shown = comments.filter { !M.isBlocked(it.authorId) }
                if (shown.isEmpty()) Text("No comments yet.", color = P.secondary, modifier = Modifier.padding(14.dp))
                shown.forEach { c -> CommentRow(c) { reporting = ReportTarget("comment", "tasks/${t.id}/comments/${c.id}", t.id, c.authorId, c.body) } }
                if (shown.size < comments.size) Text("Comments from people you blocked are hidden.", style = T.caption, color = P.secondary, modifier = Modifier.padding(14.dp))
                if (edit) {
                    RowDivider()
                    Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlainField(comment, { comment = it }, "Write a comment", Modifier.weight(1f))
                        IconButton(onClick = {
                            val body = comment.trim()
                            if (body.isEmpty() || sending) return@IconButton
                            sending = true; comment = ""
                            scope.launch {
                                try { M.addComment(t, body) } catch (e: Exception) { comment = body; M.toast = AppModel.friendly(e) }
                                sending = false
                            }
                        }, enabled = comment.isNotBlank() && !sending) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (comment.isNotBlank()) P.accent else P.tertiary) }
                    }
                }
            }
            FormSection {
                FormRow(onClick = { shareText(context, "${AppModel.SITE}/task/${t.id}") }) { Icon(Icons.Default.Link, null, tint = P.accent); Text("Share link", color = P.accent) }
                if (t.createdBy != M.uid) {
                    RowDivider()
                    FormRow(onClick = { reporting = ReportTarget("task", "tasks/${t.id}", t.id, t.createdBy, listOf(t.title, t.description).filter { it.isNotEmpty() }.joinToString("\n")) }) {
                        Icon(Icons.Default.Report, null, tint = P.accent); Text("Report this task", color = P.accent)
                    }
                }
                if (M.canDelete(t)) {
                    RowDivider()
                    FormRow(onClick = { M.archive(t); M.toast = "Archived “${t.title}”"; nav.pop() }) { Icon(Icons.Default.Archive, null, tint = Brand.red); Text("Archive", color = Brand.red) }
                }
            }
        }
    }

    when (picking) {
        "owners" -> PeoplePicker("Owner", t.assigneeUids, { picking = null }) { M.update(t, mapOf("assigneeUids" to it)) }
        "backups" -> PeoplePicker("Backup", t.backupUids, { picking = null }) { M.update(t, mapOf("backupUids" to it)) }
        "deps" -> TaskPicker("Blocked by", t.blockedBy, t.id, { picking = null }) { M.update(t, mapOf("blockedBy" to it)) }
        "parts" -> TaskPicker("What it's made of", t.childIds, t.id, { picking = null }) { M.update(t, mapOf("childIds" to it)) }
    }
    reporting?.let { ReportSheet(it) { reporting = null } }
}

/** "This is an overall goal": done automatically once every task in it is done. */
@Composable
private fun GoalSection(t: TaskItem, edit: Boolean, pickParts: () -> Unit) {
    val nav = LocalNav.current
    FormSection {
        ToggleRow("This is an overall goal", t.isGoal, "It's done automatically once every task in it is done.", edit) { M.update(t, mapOf("isGoal" to it)) }
        if (t.isGoal) {
            val parts = t.childIds.mapNotNull { M.task(it) }
            if (parts.isNotEmpty()) {
                val done = parts.count { M.isDone(it) }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    LinearProgressIndicator(progress = { done / parts.size.toFloat() }, Modifier.fillMaxWidth())
                    Text("$done of ${parts.size} done", style = T.caption, color = P.secondary, modifier = Modifier.padding(top = 4.dp))
                }
                parts.forEach { p ->
                    FormRow(onClick = { nav.push(Screen.Task(p.id)) }) {
                        Icon(if (M.isDone(p)) Icons.Default.CheckCircle else Icons.Outlined.Circle, null, Modifier.size(18.dp), tint = P.secondary)
                        Text(p.title, style = T.sub)
                    }
                }
            }
            if (edit) FormRow(onClick = pickParts) { Text(if (parts.isEmpty()) "Choose the tasks it's made of" else "Change its tasks", color = P.accent) }
        }
        M.tasks.filter { it.isGoal && t.id in it.childIds }.forEach { g ->
            FormRow(onClick = { nav.push(Screen.Task(g.id)) }) { Icon(Icons.Default.TrackChanges, null, tint = P.secondary); Text("Part of goal: ${g.title}", style = T.sub) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(c: Comment, report: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { if (c.authorId != M.uid) menu = true }).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PersonAvatar(c.authorId, 28.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(M.person(c.authorId)?.name ?: "Someone", style = T.subStrong)
                    Text(Day.relative(c.createdAt), style = T.caption, color = P.secondary)
                }
                Text(c.body, style = T.body)
            }
        }
        ReportMenu(menu, { menu = false }, c.authorId, report)
    }
}

// Photos (up to 8, kept 14 days), like the website: see them, add from the gallery, remove your own.

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotosSection(task: TaskItem, edit: Boolean, report: (ReportTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val now = nowMs.toDouble()
    val live = task.photos.filter { it.exp > now && !M.isBlocked(it.by) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        adding = true
        scope.launch {
            for (u in uris.take(8 - live.size)) {
                try { M.addPhoto(M.task(task.id) ?: task, u) } catch (e: Exception) { M.toast = e.message ?: AppModel.friendly(e) }
            }
            adding = false
        }
    }
    FormSection("Photos", "Photos last 14 days. Long-press one to remove or report it.") {
        if (live.isNotEmpty()) LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(live, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    PhotoThumb(p.id, Modifier.combinedClickable(onClick = { viewing = p.id }, onLongClick = { menu = true }))
                    DropdownMenu(menu, { menu = false }) {
                        if (edit && (p.by == M.uid || M.isLead)) DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; scope.launch { M.removePhoto(task, p.id) } })
                        if (p.by != M.uid) {
                            DropdownMenuItem(text = { Text("Report") }, leadingIcon = { Icon(Icons.Default.Report, null) },
                                onClick = { menu = false; report(ReportTarget("photo", "photos/${p.id}", task.id, p.by, "Photo on “${task.title}”")) })
                            if (p.by.isNotEmpty()) DropdownMenuItem(text = { Text("Block ${M.person(p.by)?.firstName ?: "this person"}", color = Brand.red) },
                                leadingIcon = { Icon(Icons.Default.Block, null, tint = Brand.red) }, onClick = { menu = false; M.block(p.by) })
                        }
                    }
                }
            }
        }
        if (edit && !M.demo) FormRow(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = live.size < 8 && !adding) {
            Icon(Icons.Default.AddPhotoAlternate, null, tint = P.accent)
            Text("Add photos", color = P.accent, modifier = Modifier.weight(1f))
            if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (live.isEmpty() && (!edit || M.demo)) Text("No photos.", color = P.secondary, modifier = Modifier.padding(14.dp))
    }
    viewing?.let { id -> PhotoFull(id) { viewing = null } }
}

@Composable
private fun PhotoThumb(id: String, modifier: Modifier) {
    var image by remember(id) { mutableStateOf<Bitmap?>(M.photoCache[id]) }
    LaunchedEffect(id) { if (image == null) image = M.photo(id) }
    Box(modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).background(P.secondary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        val b = image
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun PhotoFull(id: String, onDismiss: () -> Unit) {
    var image by remember(id) { mutableStateOf<Bitmap?>(M.photoCache[id]) }
    LaunchedEffect(id) { if (image == null) image = M.photo(id) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            val b = image
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else CircularProgressIndicator()
            IconButton(onClick = onDismiss, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
    }
}

