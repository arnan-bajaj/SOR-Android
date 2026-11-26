package org.systemoverload.team.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*
import java.time.LocalDate
import java.time.LocalTime

/** The date tile (Next up) and the countdown ring (Next competition) share a width, so the titles beside them line up. */
private val LEAD = 66.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AppBackground {
        PullToRefreshBox(refreshing, onRefresh = {
            refreshing = true
            scope.launch { M.refreshCalendar(force = true); delay(600); refreshing = false }
        }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Header()
                if (M.demo) DemoBanner()
                Stats()
                val now = M.meetingNow
                if (now != null) HereCard(now)
                else M.openShifts.firstOrNull()?.event?.let { HereCard(it) }
                nextMeeting()?.let { NextUp(it) }
                M.nextCompetition?.let { CompetitionCard(it) }
                ComingUp()
                MyWork()
            }
        }
    }
}

private fun nextMeeting(): CalEvent? {
    val now = nowMs.toDouble()
    val here = M.meetingNow?.id
    return M.events.firstOrNull { !it.allDay && it.start > now && !M.isCancelled(it) && it.id != here }
}

@Composable
private fun Header() {
    val nav = LocalNav.current
    val h = LocalTime.now().hour
    val greeting = if (h < 5) "Up late" else if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Day.fmt(LocalDate.now(), "EEEE, MMMM d").uppercase(), style = T.captionStrong, color = P.secondary, letterSpacing = 0.6.sp)
            Text("$greeting, ${M.profile?.firstName ?: ""}", style = T.largeTitle, maxLines = 2)
        }
        HelperButton()
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(CircleShape).clickable { nav.select(Tab.You) }.border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)) {
            Avatar(M.profile?.name ?: "", M.authPhoto, 44.dp)
        }
    }
}

/** The small sparkle button that opens the helper. */
@Composable
fun HelperButton() {
    val nav = LocalNav.current
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { nav.push(Screen.Helper) }.glass(20.dp, Brand.violet), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.AutoAwesome, "Helper", Modifier.size(19.dp), tint = Brand.violet)
    }
}

@Composable
private fun DemoBanner() {
    Row(Modifier.fillMaxWidth().glass(18.dp, Brand.amber).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(Icons.Default.TheaterComedy, Brand.amber, 34.dp)
        Column(Modifier.weight(1f)) {
            Text("This is a demo", style = T.subStrong)
            Text("Made-up people and tasks. Nothing you change is saved.", style = T.caption, color = P.secondary)
        }
        OutlinedButton(onClick = { M.leaveDemo() }) { Text("Sign in") }
    }
}

@Composable
private fun Stats() {
    val nav = LocalNav.current
    val mine = M.myOpenTasks
    val soon = mine.count { (Day.until(it.dueDate) ?: 99) <= 3 }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("${mine.size}", "My tasks", Icons.Default.Checklist, Brand.blue, Modifier.weight(1f)) { nav.select(Tab.Tasks); nav.push(Screen.Board("mine")) }
        StatTile("$soon", "Due soon", Icons.Default.Schedule, if (soon > 0) Brand.amber else P.secondary, Modifier.weight(1f))
        StatTile("${M.myOpenActionItems.size}", "Action items", Icons.Default.CheckCircle, Brand.teal, Modifier.weight(1f)) { nav.push(Screen.ActionItems()) }
    }
}

// At a meeting: check in, and "I'm here"

@Composable
private fun HereCard(m: CalEvent) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    val canCheckIn = M.openShifts.any { it.id == m.id }
    val marked = M.myMarks.firstOrNull { it.meetingId == m.id }
    val c = kindColor(m)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brand.wash(c)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Brand.green))
            Text("HAPPENING NOW", style = T.tiny, color = Color.White.copy(alpha = 0.85f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.title, style = T.title, color = Color.White)
            Text("${m.whenLabel} · ${m.shortPlace}", style = T.sub, color = Color.White.copy(alpha = 0.8f))
        }
        val st = marked?.let { MarkStatus.of(it.status) }
        if (st != null) {
            Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Checked in · ${st.label}", style = T.subStrong, color = Color.White)
            }
        } else if (canCheckIn) {
            Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(15.dp)).background(Color.White).clickable { nav.push(Screen.CheckIn) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCodeScanner, null, tint = c)
                Spacer(Modifier.width(8.dp))
                Text("Scan to check in", style = T.headline, color = c)
            }
        }
        if (M.imHere) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WavingHand, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.width(6.dp))
                Text("The team knows you're here", style = T.sub, color = Color.White.copy(alpha = 0.85f))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.18f))
                    .clickable(enabled = !sending) { sending = true; scope.launch { M.announceArrival(); sending = false } },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.WavingHand, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("I'm here, open the door", style = T.subStrong, color = Color.White)
            }
        }
    }
}

// Next up

@Composable
private fun NextUp(e: CalEvent) {
    val nav = LocalNav.current
    val c = kindColor(e)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { nav.push(Screen.Meeting(e.id)) }.glass(22.dp, c).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.width(LEAD).height(74.dp).clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.16f)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(Day.fmt(e.day, "EEE").uppercase(), style = T.tiny, color = c)
            Text(Day.fmt(e.day, "d"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(Day.fmt(e.day, "MMM"), style = T.caption, color = P.secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("NEXT UP · ${relative(e)}", style = T.tiny, color = c)
            Text(e.title, style = T.headline, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (e.place.isEmpty()) Icons.Default.Videocam else Icons.Default.Place, null, Modifier.size(15.dp), tint = P.secondary)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (e.place.isEmpty()) (if (e.service.isEmpty()) e.whenLabel else "${e.whenLabel} · ${e.service}") else "${e.whenLabel} · ${e.shortPlace}",
                    style = T.sub, color = P.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (M.amGoing(e.id)) Icon(Icons.Default.CheckCircle, "Going", tint = Brand.green, modifier = Modifier.size(26.dp))
    }
}

private fun relative(e: CalEvent): String {
    val n = Day.until(e.dayKey) ?: 0
    if (n == 0) {
        val mins = ((e.start - nowMs) / 60_000).toInt()
        return if (mins < 60) "IN ${maxOf(1, mins)} MIN" else "TODAY"
    }
    return if (n == 1) "TOMORROW" else "IN $n DAYS"
}

// Competition

@Composable
private fun CompetitionCard(c: CalEvent) {
    val nav = LocalNav.current
    val days = maxOf(0, Day.until(c.dayKey) ?: 0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { nav.push(Screen.Meeting(c.id)) }.glass(22.dp, Brand.red).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(LEAD), contentAlignment = Alignment.Center) {
            Ring(1f - minOf(days, 120) / 120f, Brand.red, 7.dp, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$days", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(if (days == 1) "day" else "days", style = T.caption, color = P.secondary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, null, Modifier.size(14.dp), tint = Brand.red)
                Spacer(Modifier.width(4.dp))
                Text("NEXT COMPETITION", style = T.tiny, color = Brand.red)
            }
            Text(c.title, style = T.headline)
            Text(Day.fmt(c.day, "EEEE, MMM d"), style = T.sub, color = P.secondary)
        }
    }
}

// Coming up

@Composable
private fun ComingUp() {
    val nav = LocalNav.current
    val next = nextMeeting()?.id
    val here = M.meetingNow?.id
    val list = M.upcoming.filter { !it.allDay && it.id != next && it.id != here }.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Coming up", Modifier.weight(1f))
            TextButton(onClick = { nav.select(Tab.Meetings) }) { Text("See all") }
        }
        when {
            "calendar" !in M.loaded -> SkeletonRows(1, 116.dp)
            list.isEmpty() -> Text("Nothing else on the calendar.", color = P.secondary)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { e -> MiniMeeting(e) { nav.push(Screen.Meeting(e.id)) } }
            }
        }
    }
}

@Composable
private fun MiniMeeting(e: CalEvent, onClick: () -> Unit) {
    val c = kindColor(e)
    val cancelled = M.isCancelled(e)
    Column(Modifier.width(160.dp).height(116.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).glass(18.dp, c).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Day.fmt(e.day, "EEE d"), style = T.captionStrong, color = c, modifier = Modifier.weight(1f))
            if (M.amGoing(e.id)) Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = Brand.green)
            if (cancelled) Text("Cancelled", style = T.tiny, color = Brand.red)
        }
        Text(e.title, style = T.subStrong, maxLines = 2, textDecoration = if (cancelled) TextDecoration.LineThrough else null)
        Spacer(Modifier.weight(1f))
        Text(e.whenLabel, style = T.caption, color = P.secondary, maxLines = 1)
    }
}

// My work

@Composable
private fun MyWork() {
    val nav = LocalNav.current
    val mine = M.myOpenTasks
    val items = M.myOpenActionItems
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("My work")
        when {
            "tasks" !in M.loaded -> SkeletonRows(3)
            mine.isEmpty() -> Row(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Default.AutoAwesome, Brand.teal, 36.dp)
                Text("Nothing assigned to you right now.", color = P.secondary)
            }
            else -> mine.forEach { t -> TaskRow(t, showBoard = true) { nav.push(Screen.Task(t.id)) } }
        }
        if (items.isNotEmpty()) {
            SectionTitle("My action items")
            Column(Modifier.fillMaxWidth().glass(18.dp, Brand.teal).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { a -> ActionItemRow(a) { nav.push(Screen.ActionItem(a.id, a.confidential)) } }
            }
        }
    }
}
