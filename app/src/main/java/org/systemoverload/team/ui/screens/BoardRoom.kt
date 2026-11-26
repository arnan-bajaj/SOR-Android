package org.systemoverload.team.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*

/**
 * The Board Room: confidential. The Board writes; lead mentors read; nobody else can load any of it.
 * Three parts, like the website: the channel, the confidential action items, and the Board's agendas.
 */
@Composable
fun BoardRoomScreen() {
    val nav = LocalNav.current
    var part by rememberSaveable { mutableStateOf("channel") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { M.wantBoardRoom() }
    Page("Board Room", tint = Brand.red, actions = {
        if (part == "items") {
            IconButton(onClick = { showDone = !showDone }) { Icon(if (showDone) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Show done") }
            if (M.isBoardMember) IconButton(onClick = { nav.push(Screen.NewActionItem(Track.board, true)) }) { Icon(Icons.Default.Add, "New confidential item") }
        }
    }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(listOf("channel" to "Channel", "items" to "Action items", "agendas" to "Agendas"), part) { part = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(13.dp), tint = P.secondary); Spacer(Modifier.width(4.dp))
                Text("Only the Board and lead mentors can see this.", style = T.caption, color = P.secondary)
            }
        }
        when (part) {
            "channel" -> BoardChannel()
            "items" -> ItemList(boardRoom = true, track = Track.board, showDone = showDone)
            else -> AgendaDocsList(Track.board)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardChannel() {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }
    val list = M.boardChannel.filter { !M.isBlocked(it.authorId) }
    val state = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) state.animateScrollToItem(list.size) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().glass(18.dp, Brand.violet).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Default.EventRepeat, Brand.violet, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text("The team is on", style = T.caption, color = P.secondary)
                        Text(M.currentSeason.name, style = T.headline)
                    }
                    if (M.seasons.size > 1) OutlinedButton(onClick = { choosing = true }) { Text("Switch") }
                }
            }
            if ("boardChannel" !in M.loaded && !M.demo) item { SkeletonRows(4) }
            else if (M.boardChannel.isEmpty()) item { EmptyNote("No messages yet.") }
            items(list, key = { it.id }) { m ->
                if (m.kind == "season") {
                    Text(m.text, style = T.caption, color = P.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                } else {
                    val mine = m.authorId == M.uid
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            if (!mine) { PersonAvatar(m.authorId, 30.dp); Spacer(Modifier.width(10.dp)) }
                            Column(Modifier.widthIn(max = 290.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!mine) Text(M.person(m.authorId)?.name ?: m.authorName, style = T.captionStrong)
                                    Text(Day.fmtMs(m.createdAt, "MMM d, h:mm a"), style = T.caption, color = P.secondary)
                                }
                                Text(m.text, color = if (mine) Color.White else P.text, style = T.body,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                        .then(if (mine) Modifier.background(Brand.wash(Brand.blue)) else Modifier.background(P.row))
                                        .combinedClickable(onClick = {}, onLongClick = { if (!mine) menu = true })
                                        .padding(horizontal = 12.dp, vertical = 9.dp))
                            }
                        }
                        ReportMenu(menu, { menu = false }, m.authorId) { reporting = ReportTarget("message", "boardChannel/${m.id}", "", m.authorId, m.text) }
                    }
                }
            }
        }
        if (M.isBoardMember) Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message the Board") }, maxLines = 5, shape = RoundedCornerShape(22.dp))
            FilledIconButton(onClick = { val t = text; text = ""; scope.launch { M.postToBoard(t) } }, enabled = text.isNotBlank(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Move the whole team to…") },
        text = {
            Column {
                Text("Everyone switches right away. Days from today on belong to the new season. It can be undone.", style = T.sub, color = P.secondary)
                M.seasons.filter { it.id != M.currentSeason.id }.forEach { s ->
                    TextButton(onClick = { choosing = false; scope.launch { M.switchSeason(s) } }) { Text(s.name) }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
    reporting?.let { ReportSheet(it) { reporting = null } }
}

/** The agendas written on the website for one kind of meeting, plus the Google Doc ones /agenda made in Slack. */
@Composable
fun AgendaDocsList(track: Track) {
    val nav = LocalNav.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { M.wantAgendaDocs() }
    val (agendas, google) = M.docsFor(track)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (agendas.isEmpty() && google.isEmpty()) item { EmptyNote("No agendas yet. Start one on the website, or with /agenda in Slack.") }
        items(google, key = { "g" + it.id }) { g ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openUrl(context, g.url) }.glass(16.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Default.Description, Brand.blue, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(g.title.ifEmpty { "Agenda" }, style = T.subStrong)
                    Text("Google Doc · ${Day.pretty(g.date)}", style = T.caption, color = P.secondary)
                }
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = P.secondary)
            }
        }
        items(agendas, key = { "a" + it.id }) { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { nav.push(Screen.AgendaDoc(a.id, track == Track.board)) }.glass(16.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.AutoMirrored.Filled.ListAlt, Brand.violet, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text("${track.label} agenda", style = T.subStrong)
                    Text("${Day.pretty(a.date)}${if (a.startTime.isEmpty()) "" else " · ${a.startTime}"} · ${a.items.size} topics · ${a.totalMinutes} min", style = T.caption, color = P.secondary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = P.tertiary)
            }
        }
    }
}

@Composable
fun AgendaDocScreen(id: String, board: Boolean) {
    val context = LocalContext.current
    val doc = (if (board) M.boardAgendaDocs else M.agendaDocs).firstOrNull { it.id == id }
    Page("Agenda", tint = Brand.violet) {
        if (doc == null) { EmptyNote("This agenda is gone."); return@Page }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            FormSection {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${Track.of(doc.track)?.label ?: "Meeting"} agenda", style = T.title)
                    Text(Day.fmt(doc.date, "EEEE, MMM d") + if (doc.startTime.isEmpty()) "" else " · ${doc.startTime}", color = P.secondary)
                }
            }
            if (doc.items.isNotEmpty()) FormSection("Topics · ${doc.totalMinutes} min") {
                doc.items.forEachIndexed { i, it ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(it.topic, style = T.headline, modifier = Modifier.weight(1f))
                            if (it.minutes > 0) Text("${it.minutes} min", style = T.captionStrong, color = P.secondary)
                            if (it.urgency.isNotEmpty()) Pill(it.urgency.replaceFirstChar { c -> c.uppercase() },
                                tint = if (it.urgency == "high") Brand.red else if (it.urgency == "medium") Brand.amber else P.secondary)
                        }
                        if (it.points.isNotEmpty()) Text(it.points, style = T.sub, color = P.secondary)
                        if (it.who.isNotEmpty()) Text("Who: ${it.who}", style = T.caption, color = P.secondary)
                    }
                }
            }
            listOf("Purpose" to doc.purpose, "Key takeaways" to doc.takeaways, "Topics discussed" to doc.topics, "Next steps" to doc.nextSteps)
                .filter { it.second.isNotEmpty() }.forEach { (t, v) -> FormSection(t) { SelectionContainer { Text(v, modifier = Modifier.padding(14.dp)) } } }
            if (doc.attended.isNotEmpty()) FormSection("Attended") { Text(doc.attended.joinToString(", "), modifier = Modifier.padding(14.dp)) }
            if (doc.recording.isNotEmpty()) FormSection {
                FormRow(onClick = { openUrl(context, doc.recording) }) { Icon(Icons.Default.PlayCircle, null, tint = P.accent); Text("Recording", color = P.accent) }
            }
        }
    }
}

