package org.systemoverload.team.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*

/** Tasks: every subteam board, then one board's columns. */
@Composable
fun TasksScreen() {
    val nav = LocalNav.current
    Page("Tasks", back = false, large = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if ("subteams" !in M.loaded) Box(Modifier.padding(16.dp)) { SkeletonRows(6, 52.dp) }
            FormSection {
                BoardRow("My Work", P.accent, Icons.Default.Person, M.myOpenTasks.size) { nav.push(Screen.Board("mine")) }
            }
            FormSection {
                BoardRow("Action Items", Brand.cyan, Icons.Default.Checklist, M.myOpenActionItems.size) { nav.push(Screen.ActionItems()) }
                RowDivider()
                BoardRow("Timeline", Brand.indigo, Icons.Default.Timeline, 0) { nav.push(Screen.Timeline) }
            }
            FormSection("Boards") {
                M.boards.forEachIndexed { i, s ->
                    if (i > 0) RowDivider()
                    BoardRow(s.name, s.color, boardIcon(s.id), M.tasks.count { it.subteamId == s.id && !M.isDone(it) }) { nav.push(Screen.Board(s.id)) }
                }
            }
            FormSection {
                BoardRow("Archive", Brand.gray, Icons.Default.Archive, 0) { nav.push(Screen.Archive) }
            }
        }
    }
}

@Composable
fun BoardRow(name: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, onClick: () -> Unit) {
    FormRow(onClick = onClick) {
        IconTile(icon, color, 34.dp)
        Text(name, style = T.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", color = P.secondary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = P.tertiary)
    }
}

/** One board, a column at a time (swipe between them or tap the tabs). "mine" is My Work across every board. */
@Composable
fun BoardScreen(boardId: String) {
    val nav = LocalNav.current
    val isMine = boardId == "mine"
    val board = M.subteam(boardId)
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun tasksIn(col: String) = M.tasks
        .filter { it.columnId == col }
        .filter { if (isMine) (M.uid in it.assigneeUids || M.uid in it.backupUids) else it.subteamId == boardId }
        .filter { !onlyMine || M.uid in it.assigneeUids }
        .sortedBy { it.position }
    // My Work shows the shared columns, plus a board-only one only when one of my tasks sits in it: boardColumns in mod-board.
    val cols = if (isMine) M.columnsFor("all").filter { c -> c.subteamIds.isEmpty() || tasksIn(c.id).isNotEmpty() } else M.columnsFor(boardId)
    val startPage = cols.indexOfFirst { it.id == "todo" }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startPage) { cols.size }
    val scope = rememberCoroutineScope()
    val current = cols.getOrNull(pager.currentPage)

    Page(if (isMine) "My Work" else board?.name ?: "Board", tint = board?.color ?: Brand.blue, actions = {
        if (!isMine) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(if (onlyMine) Icons.Default.FilterAlt else Icons.Default.FilterList, "Filter") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Only my tasks") }, onClick = { onlyMine = !onlyMine; menu = false },
                        trailingIcon = { if (onlyMine) Icon(Icons.Default.Check, null) })
                }
            }
            if (M.canEdit) IconButton(onClick = { nav.push(Screen.NewTask(boardId, current?.id)) }) { Icon(Icons.Default.Add, "New task") }
        }
    }) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cols.size) { i ->
                val c = cols[i]
                val on = i == pager.currentPage
                val sc = statusColor(c.id)
                Row(
                    Modifier.clip(CircleShape).background(if (on) sc else P.card).clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(c.name, style = T.subStrong, color = if (on) Color.White else sc)
                    Text("${tasksIn(c.id).size}", style = T.subStrong, color = if (on) Color.White.copy(alpha = 0.8f) else P.secondary)
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { cols.getOrNull(it)?.id ?: it }) { page ->
            val c = cols[page]
            val list = tasksIn(c.id)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if ("tasks" !in M.loaded) item { SkeletonRows() }
                else if (list.isEmpty()) item { EmptyNote(if (c.id == M.doneColumnId) "Nothing finished yet." else "Nothing here.", Modifier.padding(top = 30.dp)) }
                items(list, key = { it.id }) { t -> TaskRow(t, showBoard = isMine, menu = true) { nav.push(Screen.Task(t.id)) } }
            }
        }
    }
}

/** A flat task card with a hairline, like the website's. Tick it off right here; long-press to move or archive. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(task: TaskItem, showBoard: Boolean = false, menu: Boolean = false, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val done = M.isDone(task)
    val tint = M.subteam(task.subteamId)?.color ?: Brand.blue
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .combinedClickable(onClick = onClick, onLongClick = if (menu && M.canEdit) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); open = true }) else null)
                .glass(18.dp, if (done) null else tint).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.clip(CircleShape).clickable(enabled = M.canEdit) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                M.move(task, if (done) (M.columnsFor(task.subteamId).firstOrNull { it.id == "todo" }?.id ?: "todo") else M.doneColumnId)
            }) { CheckCircle(done, tint, goal = task.isGoal) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.title, style = T.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (done) P.secondary else P.text,
                        textDecoration = if (done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                    if (task.assigneeUids.isNotEmpty()) AvatarStack(task.assigneeUids)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showBoard) M.subteam(task.subteamId)?.let { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SubteamDot(s.color, 7.dp); Spacer(Modifier.width(5.dp)); Text(s.name, style = T.caption, color = P.secondary)
                        }
                    }
                    if (task.priority == Priority.high || task.priority == Priority.critical) Pill(task.priority.label, Icons.Default.PriorityHigh, task.priority.tint)
                    DuePill(task.dueDate, done)
                    if (task.checklist.isNotEmpty()) Pill("${task.checklist.count { it.done }}/${task.checklist.size}", Icons.Default.Checklist)
                    if (task.commentCount > 0) Pill("${task.commentCount}", Icons.AutoMirrored.Filled.Comment)
                }
            }
        }
        if (menu) DropdownMenu(open, { open = false }) {
            M.columnsFor(task.subteamId).filter { it.id != task.columnId }.forEach { c ->
                DropdownMenuItem(text = { Text("Move to ${c.name}") }, onClick = { M.move(task, c.id); open = false },
                    leadingIcon = { Icon(if (c.id == M.doneColumnId) Icons.Default.CheckCircle else Icons.Default.ArrowCircleRight, null) })
            }
            if (M.canDelete(task)) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Archive", color = Brand.red) }, onClick = { M.archive(task); M.toast = "Archived “${task.title}”"; open = false },
                    leadingIcon = { Icon(Icons.Default.Archive, null, tint = Brand.red) })
            }
        }
    }
}

/** Archived tasks from this season, with Restore (the website's Archive page). */
@Composable
fun ArchiveScreen() {
    Page("Archive") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (M.archivedTasks.isEmpty()) item { EmptyNote("Nothing archived this season.") }
            items(M.archivedTasks, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().glass(16.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(t.title, style = T.body)
                        M.subteam(t.subteamId)?.let { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) { SubteamDot(s.color, 7.dp); Spacer(Modifier.width(5.dp)); Text(s.name, style = T.caption, color = P.secondary) }
                        }
                    }
                    if (M.canEdit) OutlinedButton(onClick = { M.unarchive(t); M.toast = "Restored “${t.title}”" }) { Text("Restore") }
                }
            }
        }
    }
}

/** The new-task form: the Create tab, or a page from a board's +. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskScreen(subteamId: String? = null, columnId: String? = null, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var board by rememberSaveable {
        val mine = M.profile?.subteamIds?.firstOrNull { id -> M.boards.any { it.id == id } }
        mutableStateOf(subteamId?.takeIf { id -> M.boards.any { it.id == id } } ?: mine ?: M.boards.firstOrNull()?.id ?: "")
    }
    var owners by rememberSaveable { mutableStateOf(listOf(M.uid)) }
    var due by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(Priority.med) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val canAdd = title.isNotBlank() && board.isNotEmpty() && !saving

    fun save() {
        saving = true
        val col = columnId?.takeIf { id -> M.columnsFor(board).any { it.id == id } }
        scope.launch {
            try {
                M.createTask(title, notes, board, col, owners, due, priority)
                M.toast = "Task added"
                onDone()
            } catch (e: Exception) { M.toast = AppModel.friendly(e) }
            saving = false
        }
    }

    AppBackground {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("New task", style = T.headline) },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { save() }, enabled = canAdd) { Text("Add", fontWeight = FontWeight.SemiBold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets.statusBars,
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
                FormSection {
                    PlainField(title, { title = it }, "What needs doing?", style = T.title3)
                    RowDivider()
                    PlainField(notes, { notes = it }, "Notes", minLines = 2)
                }
                FormSection {
                    ChoiceRow("Board", board, M.boards.map { it.id to it.name }) { board = it }
                    RowDivider()
                    ValueRow("Owner", if (owners.isEmpty()) "Nobody yet" else owners.mapNotNull { M.person(it)?.firstName }.joinToString(", "), onClick = { picking = true })
                    RowDivider()
                    DayRow("Due date", due, addDefault = { Day.inDays(7) }) { due = it }
                    RowDivider()
                    ChoiceRow("Priority", priority, Priority.entries.map { it to it.label }) { priority = it }
                }
                Caption("Slack reminds the owner when 90% of the time is up, like tasks made on the website.", modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
            }
        }
    }
    if (picking) PeoplePicker(selected = owners, onDismiss = { picking = false }) { owners = it }
}
