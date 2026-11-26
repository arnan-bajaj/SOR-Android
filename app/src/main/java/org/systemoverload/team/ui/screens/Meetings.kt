@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.systemoverload.team.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Meetings: Calendar, Signups, Attendance and Agendas, the same four pages as the website's Meetings tab. */
@Composable
fun MeetingsScreen() {
    var page by rememberSaveable { mutableStateOf("calendar") }
    Page("Meetings", back = false, large = true) {
        Segmented(listOf("calendar" to "Calendar", "signups" to "Signups", "attendance" to "Attendance", "agendas" to "Agendas"), page,
            Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) { page = it }
        when (page) {
            "calendar" -> CalendarPage()
            "signups" -> SignupsPage()
            "attendance" -> AttendancePage()
            else -> AgendasPage()
        }
    }
}

@Composable
private fun Refreshable(content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(refreshing, { refreshing = true; scope.launch { M.refreshCalendar(force = true); delay(600); refreshing = false } }, Modifier.fillMaxSize()) { content() }
}

// Calendar: a month at a glance; tap a day for its meetings.

@Composable
private fun CalendarPage() {
    val nav = LocalNav.current
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selected by rememberSaveable { mutableStateOf(Day.iso()) }
    val ym = YearMonth.parse(month)
    Refreshable {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonthGrid(ym, selected, { selected = it }) { month = ym.plusMonths(it.toLong()).toString() }
            val list = M.events.filter { it.dayKey == selected || (it.allDay && covers(it, selected)) }
            Text(dayHeading(selected), style = T.headline, modifier = Modifier.padding(top = 4.dp))
            when {
                "calendar" !in M.loaded -> SkeletonRows(2)
                list.isEmpty() -> Text("Nothing on the calendar.", color = P.secondary)
            }
            list.forEach { e -> MeetingRow(e) { nav.push(Screen.Meeting(e.id)) } }
        }
    }
}

/** All-day events are stored at UTC midnight: compare by day keys. */
private fun covers(e: CalEvent, day: String): Boolean {
    val last = Day.isoAt(e.end - TeamCalendar.DAY + 43_200_000)
    return e.dayKey <= day && day <= last
}

private fun dayHeading(iso: String): String {
    val date = Day.fmt(iso, "EEEE, MMMM d")
    return when (Day.until(iso)) { 0 -> "Today · $date"; 1 -> "Tomorrow · $date"; else -> date }
}

@Composable
private fun MonthGrid(ym: YearMonth, selected: String, select: (String) -> Unit, shift: (Int) -> Unit) {
    val first = ym.atDay(1)
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = (first.dayOfWeek.value - firstDow.value + 7) % 7
    val byDay = M.events.groupBy { it.dayKey }
    val today = Day.iso()
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Day.fmt(first, "MMMM yyyy"), style = T.title3, modifier = Modifier.weight(1f))
            IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            IconButton(onClick = { shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
        }
        val labels = (0 until 7).map { DayOfWeek.of(((firstDow.value - 1 + it) % 7) + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
        Row { labels.forEach { Text(it, style = T.captionStrong, color = P.secondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) } }
        val cells = List(lead) { null } + (1..ym.lengthOfMonth()).map { it }
        cells.chunked(7).forEach { week ->
            Row {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val iso = first.withDayOfMonth(d).toString()
                            val isSel = iso == selected; val isToday = iso == today
                            Column(
                                Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(10.dp)).background(if (isSel) P.accent else Color.Transparent).clickable { select(iso) },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$d", fontSize = 15.sp, fontWeight = if (isSel || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.White else if (isToday) P.accent else P.text)
                                Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    (byDay[iso] ?: emptyList()).take(3).forEach { e -> Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSel) Color.White else kindColor(e))) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeetingRow(e: CalEvent, onClick: () -> Unit) {
    val cancelled = M.isCancelled(e)
    val c = kindColor(e)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).glass(16.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.size(44.dp, 48.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(Day.fmt(e.day, "MMM").uppercase(), style = T.tiny, color = c)
            Text(Day.fmt(e.day, "d"), style = T.title3.copy(fontWeight = FontWeight.Bold))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(e.title, style = T.bodyMedium, maxLines = 2, textDecoration = if (cancelled) TextDecoration.LineThrough else null)
            val where = if (e.place.isNotEmpty()) " · ${e.shortPlace}" else if (e.service.isNotEmpty()) " · ${e.service}" else ""
            Text(e.whenLabel + where, style = T.sub, color = P.secondary, maxLines = 1)
        }
        if (cancelled) Pill("Cancelled", tint = Brand.red)
        else if (M.amGoing(e.id)) Pill("Going", Icons.Default.Check, Brand.green)
    }
}

// Signups

@Composable
private fun SignupsPage() {
    val nav = LocalNav.current
    var weeks by rememberSaveable { mutableIntStateOf(4) }
    val shifts = M.upcomingShifts(weeks)
    Refreshable {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Caption(if (M.signsUpInApp) "Tap a meeting to volunteer, and see if it has enough adults."
                else "Students sign up in Slack: react to the week's post in #meeting-signups. It shows up here right away.")
            }
            if ("calendar" !in M.loaded || "signups" !in M.loaded) item { SkeletonRows(5) }
            items(shifts, key = { it.id }) { sh -> SignupRow(sh) { sh.event?.let { nav.push(Screen.Meeting(it.id)) } } }
            if ("calendar" in M.loaded) item {
                TextButton(onClick = { weeks += 4 }, Modifier.fillMaxWidth()) { Text("Show more") }
            }
        }
    }
}

@Composable
private fun SignupRow(shift: Shift, onClick: () -> Unit) {
    val list = M.signupsFor(shift.id)
    val cover = M.coverage(shift, list)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).glass(16.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(shift.title, style = T.bodyMedium, textDecoration = if (shift.cancelled) TextDecoration.LineThrough else null)
                Text("${Day.pretty(shift.date)} · ${shift.event?.whenLabel ?: ""}", style = T.sub, color = P.secondary)
            }
            if (shift.cancelled) Pill("Cancelled", tint = Brand.red)
            else if (M.amGoing(shift.id)) Pill("Going", Icons.Default.Check, Brand.green)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) Text("No one yet", style = T.caption, color = P.secondary)
            else { AvatarStack(list.take(6).map { it.uid }, 20.dp); Text("${list.size} going", style = T.caption, color = P.secondary) }
            Spacer(Modifier.weight(1f))
            if (!shift.cancelled && !cover.ok) Pill(cover.label, tint = Brand.amber)
        }
    }
}

// Attendance

@Composable
private fun AttendancePage() {
    val nav = LocalNav.current
    val s = M.myScore
    val marks = M.mySeasonMarks
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CardBox {
                Text("YOUR SCORE · ${M.currentSeason.name.uppercase()}", style = T.tiny, color = P.secondary)
                Text(fmtScore(s.score), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("${s.present} present", tint = Brand.green)
                    if (s.partial > 0) Pill("${s.partial} partial", tint = Brand.yellow)
                    if (s.excused > 0) Pill("${s.excused} excused", tint = Brand.blue)
                    if (s.unexcused > 0) Pill("${s.unexcused} unexcused", tint = Brand.red)
                }
                Caption("Present counts 1, Partial ½, online shifts a third, like the team's sheet.")
                Button(onClick = { nav.push(Screen.CheckIn) }, Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Check in")
                }
            }
        }
        if (M.isLead) item {
            CardBox {
                Text("For leads", style = T.headline)
                Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.Web("Take Attendance", "attendance")) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ListAlt, null, tint = P.accent); Spacer(Modifier.width(10.dp))
                    Text("Take attendance, students grid and roster", color = P.accent)
                }
                Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.Analytics) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BarChart, null, tint = P.accent); Spacer(Modifier.width(10.dp)); Text("Analytics", color = P.accent)
                }
            }
        }
        item { SectionTitle("Your meetings") }
        when {
            "attendance" !in M.loaded -> item { SkeletonRows(3) }
            marks.isEmpty() -> item { Text("No marks yet this season.", color = P.secondary) }
        }
        items(marks, key = { it.id }) { m ->
            val st = MarkStatus.of(m.status)
            Row(Modifier.fillMaxWidth().glass(16.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m.title.ifEmpty { "Meeting" }, style = T.bodyMedium)
                    Text(Day.fmt(m.date, "EEE, MMM d"), style = T.sub, color = P.secondary)
                }
                if (st != null) Pill(st.label, tint = st.tint)
            }
        }
    }
}

fun fmtScore(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

// Agendas (hangar agendas copied from Slack)

@Composable
private fun AgendasPage() {
    val nav = LocalNav.current
    LaunchedEffect(Unit) { M.wantAgendas() }
    val list = M.seasonAgendas
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Caption("What's planned for each hangar meeting. Agendas are written in Slack (#meeting-agendas) and copied here.") }
        if ("agendas" !in M.loaded && !M.demo) item { SkeletonRows(4) }
        else if (list.isEmpty()) item { EmptyNote("No agendas yet this season.") }
        items(list, key = { it.id }) { a ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { nav.push(Screen.Agenda(a.id)) }.glass(16.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.title, style = T.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Day.pretty(a.date), style = T.caption, color = P.secondary)
                    a.subteamIds.mapNotNull { M.subteam(it) }.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) { SubteamDot(s.color, 7.dp); Spacer(Modifier.width(4.dp)); Text(s.name, style = T.caption, color = P.secondary) }
                    }
                    if (a.authorName.isNotEmpty()) Text("· ${a.authorName}", style = T.caption, color = P.secondary)
                }
                Text(AgendaText.plain(a.body), style = T.sub, color = P.secondary, maxLines = 3)
            }
        }
    }
}

/** Slack's formatting made readable: <url|label> links, *bold*, &amp; and friends. */
object AgendaText {
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    fun plain(s: String): String = unescape(s.replace(Regex("<(https?://[^|>\\s]+)\\|([^>]*)>"), "$2").replace(Regex("<(https?://[^>\\s]+)>"), "$1"))
    fun annotated(s: String, link: Color): AnnotatedString = buildAnnotatedString {
        val re = Regex("<(https?://[^|>\\s]+)(?:\\|([^>]*))?>|(^|(?<=[\\s(]))\\*([^*\\n]+)\\*")
        var i = 0
        for (m in re.findAll(s)) {
            append(unescape(s.substring(i, m.range.first)))
            if (m.groupValues[1].isNotEmpty()) {
                withLink(LinkAnnotation.Url(m.groupValues[1], TextLinkStyles(SpanStyle(color = link)))) { append(unescape(m.groupValues[2].ifEmpty { m.groupValues[1] })) }
            } else withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unescape(m.groupValues[4])) }
            i = m.range.last + 1
        }
        append(unescape(s.substring(i)))
    }
}

@Composable
fun AgendaDetailScreen(id: String) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val agenda = M.agendas.firstOrNull { it.id == id }
    Page("Agenda") {
        if (agenda == null) { EmptyNote("This agenda is gone."); return@Page }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            FormSection {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(agenda.title, style = T.title)
                    Text(listOf(Day.pretty(agenda.date), agenda.authorName).filter { it.isNotEmpty() }.joinToString(" · "), color = P.secondary)
                }
            }
            FormSection { SelectionContainer { Text(AgendaText.annotated(agenda.body, P.accent), style = T.body, modifier = Modifier.padding(14.dp)) } }
            val tasks = agenda.taskIds.mapNotNull { M.task(it) }
            if (tasks.isNotEmpty()) FormSection("Tasks") { tasks.forEach { t -> FormRow(onClick = { nav.push(Screen.Task(t.id)) }) { Text(t.title) } } }
            if (agenda.permalink.isNotEmpty()) FormSection {
                FormRow(onClick = { openUrl(context, agenda.permalink) }) { Icon(Icons.Default.Tag, null, tint = P.accent); Text("Open in Slack", color = P.accent) }
            }
        }
    }
}

// The meeting page

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MeetingDetailScreen(eventId: String) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val event = M.events.firstOrNull { it.id == eventId }
    var busy by remember { mutableStateOf(false) }
    if (event == null) { Page("Meeting") { EmptyNote("This meeting isn't on the calendar any more.", Modifier.padding(top = 60.dp)) }; return }
    val sh = M.shift(event.id)
    val list = M.signupsFor(event.id)
    Page("Meeting", tint = kindColor(event)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            FormSection {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(event.title, style = T.title)
                    Text(if (event.allDay) event.whenLabel else "${Day.fmt(event.day, "EEEE, MMM d")} · ${event.whenLabel}", color = P.secondary)
                    if (M.isCancelled(event)) {
                        val r = M.meetingExtras[event.id]?.str("cancelReason") ?: ""
                        Pill("Cancelled${if (r.isEmpty()) "" else ": $r"}", Icons.Default.Close, Brand.red)
                    }
                }
            }
            if (event.place.isNotEmpty()) FormSection("Where") {
                FormRow(onClick = { openUrl(context, "geo:0,0?q=${android.net.Uri.encode(event.place)}") }) {
                    Icon(Icons.Default.Place, null, tint = P.accent); Text(event.place)
                }
            }
            if (event.link.isNotEmpty()) FormSection("Online") {
                FormRow(onClick = { openUrl(context, event.link) }) { Icon(Icons.Default.Videocam, null, tint = P.accent); Text("Join on ${event.service}", color = P.accent) }
                if (event.code.isNotEmpty()) { RowDivider(); SelectionContainer { Text(event.code, color = P.secondary, modifier = Modifier.padding(14.dp)) } }
            }
            FormSection("Signing up") {
                when {
                    sh?.cancelled == true -> Text("This meeting is cancelled.", color = P.secondary, modifier = Modifier.padding(14.dp))
                    M.signsUpInApp && sh != null -> FormRow(onClick = { busy = true; scope.launch { M.toggleSignup(sh); busy = false } }, enabled = !busy) {
                        val going = M.amGoing(sh.id)
                        Icon(if (going) Icons.Default.CheckCircle else Icons.Default.PanTool, null, tint = if (going) Brand.green else P.accent)
                        Text(if (going) "You're volunteering" else "I'll volunteer", color = if (going) P.text else P.accent, modifier = Modifier.weight(1f))
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else if (going) Text("Undo", color = P.secondary)
                    }
                    M.amGoing(event.id) -> FormRow { Icon(Icons.Default.CheckCircle, null, tint = Brand.green); Text("You're going", color = Brand.green) }
                    M.slackSignupURL(event.id) != null -> {
                        FormRow(onClick = { openUrl(context, M.slackSignupURL(event.id)!!) }) { Icon(Icons.Default.PanTool, null, tint = P.accent); Text("Sign up on Slack", color = P.accent) }
                        Caption("Opens this meeting's post in #meeting-signups. React to sign up.", modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
                    }
                    else -> Text("Not posted in Slack yet", color = P.secondary, modifier = Modifier.padding(14.dp))
                }
                if (sh != null && !sh.cancelled) {
                    val c = M.coverage(sh, list)
                    if (!c.ok) { RowDivider(); FormRow { Icon(Icons.Default.Warning, null, tint = Brand.amber); Text(c.label, color = Brand.amber) } }
                    if (M.isLead) { RowDivider(); FormRow(onClick = { nav.push(Screen.AddPeople(sh.id)) }) { Icon(Icons.Default.PersonAdd, null, tint = P.accent); Text("Add someone", color = P.accent) } }
                }
            }
            if (list.isNotEmpty()) {
                People("Adults", list.filter { it.isAdult })
                People("Leadership", list.filter { !it.isAdult && it.leader })
                People("Students", list.filter { !it.isAdult && !it.leader })
            }
            if (event.description.isNotEmpty()) FormSection("Details") { SelectionContainer { Text(event.description, modifier = Modifier.padding(14.dp)) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun People(title: String, rows: List<Signup>) {
    if (rows.isEmpty()) return
    FormSection("$title · ${rows.size}", if (M.isLead) "Long-press someone to take them off." else null) {
        rows.forEach { s ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { if (M.isLead) menu = true }).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PersonAvatar(s.uid, 28.dp)
                    Text(M.person(s.uid)?.name ?: s.name)
                    if (s.femaleAdult) Text("F", style = T.tiny, color = P.secondary)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove", color = Brand.red) }, leadingIcon = { Icon(Icons.Default.PersonRemove, null, tint = Brand.red) },
                        onClick = { menu = false; M.removeSignup(s) })
                }
            }
        }
    }
}

// Checking in: Google's code scanner reads the Monitor's QR (team.systemoverload.org/checkin/1234), or type the digits.

@Composable
fun CheckInScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun submit(text: String) {
        if (busy) return
        val digits = Regex("checkin/(\\d{4})").find(text)?.groupValues?.get(1) ?: text.filter { it.isDigit() }
        if (digits.length != 4) return
        busy = true
        scope.launch {
            val ok = M.checkIn(digits)
            busy = false
            if (ok) nav.pop() else code = ""
        }
    }
    fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(context, opts).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let { submit(it) } }
            .addOnFailureListener { M.toast = "The scanner isn't available. Type the code instead." }
    }
    LaunchedEffect(Unit) { scan() }
    Page("Check in") {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp)).clickable { scan() }.glass(22.dp, Brand.blue), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(56.dp), tint = Brand.blue)
                    Text("Scan the QR code", style = T.headline)
                }
            }
            Text("Point your camera at the QR code on the meeting-room Monitor.", style = T.sub, color = P.secondary, textAlign = TextAlign.Center)
            Text("or type the code", style = T.caption, color = P.tertiary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(4); if (code.length == 4) submit(code) },
                    Modifier.width(180.dp), placeholder = { Text("1234", style = T.title.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth()) },
                    textStyle = T.title.copy(textAlign = TextAlign.Center), singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(22.dp), strokeWidth = 2.dp)
            }
            Caption("On time for the first 25 minutes of a meeting, Partial after that.")
        }
    }
}

/** Leads put anyone on the roster on a shift, or take them off (the website's "Add someone"). */
@Composable
fun AddPeopleScreen(shiftId: String) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { M.wantRoster() }
    val sh = M.shift(shiftId)
    Page("Add someone") {
        if (sh == null) { EmptyNote("This meeting is gone."); return@Page }
        SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp), "Search the roster")
        val everyone = M.rosterPeople.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if ("roster" !in M.loaded && !M.demo) item { Box(Modifier.padding(16.dp)) { SkeletonRows(5, 48.dp) } }
            else if (everyone.isEmpty()) item {
                EmptyNote(if (query.isEmpty()) "The roster is empty. Leads add people on the website's Attendance › Roster." else "Nobody by that name on the roster.")
            }
            listOf("student" to "Students", "parent" to "Parents", "mentor" to "Mentors").forEach { (kind, title) ->
                val rows = everyone.filter { it.kind == kind || (kind == "student" && it.kind !in listOf("parent", "mentor")) }
                if (rows.isNotEmpty()) item(key = kind) {
                    FormSection(title) {
                        rows.forEachIndexed { i, w ->
                            if (i > 0) RowDivider()
                            val on = M.signupOf(w, sh.id)
                            FormRow(onClick = {
                                busy = busy + w.key
                                scope.launch { if (on != null) M.removeSignup(on) else M.addToShift(w, sh); busy = busy - w.key }
                            }, enabled = w.key !in busy) {
                                if (w.uid.isEmpty()) Avatar(w.name, "", 32.dp) else PersonAvatar(w.uid, 32.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(w.name)
                                    if (w.uid.isEmpty()) Text("No board account", style = T.caption, color = P.secondary)
                                }
                                if (w.femaleAdult) Text("F", style = T.tiny, color = P.secondary)
                                if (w.key in busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(if (on == null) Icons.Outlined.Circle else Icons.Default.CheckCircle, null, tint = if (on == null) P.secondary else Brand.green)
                            }
                        }
                    }
                }
            }
        }
    }
}
