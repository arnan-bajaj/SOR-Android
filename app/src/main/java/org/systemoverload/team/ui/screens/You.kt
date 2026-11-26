@file:OptIn(ExperimentalLayoutApi::class)

package org.systemoverload.team.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*
import org.systemoverload.team.widget.WidgetSnapshot

@Composable
fun YouScreen(appearance: String, setAppearance: (String) -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { M.wantReports() }
    Page("You", back = false, large = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            FormSection {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(M.profile?.name ?: "", M.authPhoto, 64.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(M.profile?.name ?: "", style = T.title3)
                        Text(M.authEmail, style = T.sub, color = P.secondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(M.role.label, tint = P.accent)
                            M.profile?.takeIf { it.memberType.isNotEmpty() }?.let { Pill(it.memberTypeLabel) }
                        }
                    }
                }
                val ids = M.profile?.subteamIds ?: emptyList()
                if (ids.isNotEmpty()) FlowRow(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ids.mapNotNull { M.subteam(it) }.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) { SubteamDot(s.color, 8.dp); Spacer(Modifier.width(5.dp)); Text(s.name, style = T.sub) }
                    }
                }
            }
            FormSection {
                NavRow("Ask the helper", Icons.Default.AutoAwesome, Brand.violet) { nav.push(Screen.Helper) }
                RowDivider()
                NavRow("Notifications", Icons.Default.NotificationsActive, Brand.red) { nav.push(Screen.Notifications) }
            }
            FormSection("Appearance") {
                Segmented(listOf("system" to "Match phone", "dark" to "Dark", "light" to "Light"), appearance, Modifier.padding(12.dp)) { setAppearance(it) }
            }
            FormSection("Team") {
                NavRow("Team", Icons.Default.Groups, Brand.blue, badge = if (M.isAdmin && M.pendingPeople.isNotEmpty()) "${M.pendingPeople.size} waiting" to Brand.red else null) { nav.push(Screen.Team) }
                if (M.canSeeBoardRoom) { RowDivider(); NavRow("Board Room", Icons.Default.Lock, Brand.red) { nav.push(Screen.BoardRoom) } }
                RowDivider(); NavRow("Analytics", Icons.Default.BarChart, Brand.teal) { nav.push(Screen.Analytics) }
                RowDivider(); NavRow("Archive", Icons.Default.Archive, Brand.gray) { nav.push(Screen.Archive) }
                if (M.isHelperReviewer) { RowDivider(); NavRow("Helper chats", Icons.Default.AutoAwesome, Brand.violet) { nav.push(Screen.HelperChats) } }
                if (M.isAdmin) { RowDivider(); NavRow("Reports", Icons.Default.Report, Brand.orange, badge = if (M.openReports.isNotEmpty()) "${M.openReports.size} open" to Brand.orange else null) { nav.push(Screen.Reports) } }
                if (M.isLead) { RowDivider(); NavRow("Signup records", Icons.Default.Description, Brand.amber) { nav.push(Screen.SignupRecords) } }
            }
            FormSection("Safety", "Long-press a comment, photo or message to report it or block its author. The Board reviews every report.") {
                NavRow("Blocked people", Icons.Default.Block, Brand.gray) { nav.push(Screen.Blocked) }
            }
            FormSection("About") {
                NavRow("Privacy Policy", Icons.Default.PrivacyTip, Brand.blue) { openInCustomTab(context, "${AppModel.SITE}/privacy") }
                RowDivider()
                NavRow("Help and support", Icons.AutoMirrored.Filled.HelpOutline, Brand.teal) { openInCustomTab(context, "${AppModel.SITE}/support") }
                RowDivider()
                ValueRow("Version", M.version)
            }
            FormSection(footer = "Deleting removes your profile, your private answers and your sign-in. Tasks, comments and attendance you were part of stay with the team's records.") {
                FormRow(onClick = { WidgetSnapshot.clear(context); M.signOut() }) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, tint = P.accent); Text(if (M.demo) "Leave the demo" else "Sign out", color = P.accent)
                }
                if (!M.demo) {
                    RowDivider()
                    FormRow(onClick = { confirmDelete = true }, enabled = !deleting) {
                        Icon(Icons.Default.DeleteForever, null, tint = Brand.red); Text("Delete my account", color = Brand.red, modifier = Modifier.weight(1f))
                        if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete your account?") },
        text = { Text("Google will ask you to sign in once more to confirm. You can join again later, but a Board member will need to approve you again.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                val a = context.findActivity() ?: return@TextButton
                deleting = true
                scope.launch {
                    try { M.deleteAccount(a); WidgetSnapshot.clear(context) } catch (e: Exception) { M.toast = "Couldn't delete your account: ${AppModel.friendly(e)}" }
                    deleting = false
                }
            }) { Text("Delete my account", color = Brand.red) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun NavRow(title: String, icon: ImageVector, color: Color, badge: Pair<String, Color>? = null, onClick: () -> Unit) {
    FormRow(onClick = onClick) {
        IconTile(icon, color, 30.dp)
        Text(title, style = T.body, modifier = Modifier.weight(1f))
        if (badge != null) Text(badge.first, style = T.captionStrong, color = Color.White,
            modifier = Modifier.clip(CircleShape).background(badge.second).padding(horizontal = 8.dp, vertical = 3.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = P.tertiary)
    }
}

@Composable
fun NotificationSettingsScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { M.setUpPush() }
    Page("Notifications") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!M.pushAllowed) FormSection {
                Text("Notifications are off for this app.", modifier = Modifier.padding(14.dp))
                FormRow(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Turn them on in Settings", color = P.accent) }
            }
            FormSection(footer = "Nothing is sent between 9 pm and 8 am except quietly. Slack keeps sending what it sends now.") {
                PushKind.entries.filter { it != PushKind.team || M.isAdmin || M.role == Role.pending }.forEachIndexed { i, k ->
                    if (i > 0) RowDivider()
                    ToggleRow(k.label, M.pushOn(k), k.hint) { M.setPush(k, it) }
                }
            }
        }
    }
}

/**
 * Team: everyone on the board, new people waiting for approval (admins approve them), and roles.
 * Leadership positions, subteams and seasons are edited on the website (linked at the bottom).
 */
@Composable
fun TeamScreen() {
    val nav = LocalNav.current
    var query by remember { mutableStateOf("") }
    val people = M.users.filter { it.role != Role.pending }
        .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<Person> { it.role.rank }.thenBy { it.name })
    Page("Team") {
        SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (M.isAdmin && M.pendingPeople.isNotEmpty()) item {
                FormSection("Waiting for approval") {
                    M.pendingPeople.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(p.name, p.photoURL, 40.dp)
                                Column {
                                    Text(p.name, style = T.bodyMedium)
                                    Text("${p.memberTypeLabel} · ${p.email}", style = T.caption, color = P.secondary)
                                }
                            }
                            Row {
                                OutlinedButton(onClick = { M.decline(p) }) { Text("Decline", color = Brand.red) }
                                Spacer(Modifier.weight(1f))
                                Button(onClick = { M.setRole(p, approveRole(p)) }) { Text("Approve as ${approveRole(p).label}") }
                            }
                        }
                    }
                }
            }
            item {
                FormSection("${people.size} people") {
                    people.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        PersonRow(p)
                    }
                }
            }
            item {
                FormSection { NavRow("Leadership, subteams and seasons", Icons.Default.Groups, Brand.blue) { nav.push(Screen.Web("Team", "team")) } }
            }
        }
    }
}

@Composable
private fun PersonRow(p: Person) {
    var menu by remember { mutableStateOf(false) }
    FormRow {
        Avatar(p.name, p.photoURL, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(p.name)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.subteamIds.mapNotNull { M.subteam(it) }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) { SubteamDot(s.color, 7.dp); Spacer(Modifier.width(4.dp)); Text(s.name, style = T.caption, color = P.secondary) }
                }
            }
        }
        Box {
            val tint = if (p.role == Role.admin) P.accent else P.secondary
            if (M.isAdmin && p.id != M.uid) {
                TextButton(onClick = { menu = true }) { Pill(p.role.label, tint = tint) }
                DropdownMenu(menu, { menu = false }) {
                    listOf(Role.admin, Role.lead, Role.member, Role.viewer).forEach { r ->
                        DropdownMenuItem(text = { Text(r.label) }, onClick = { menu = false; M.setRole(p, r) },
                            trailingIcon = { if (r == p.role) Icon(Icons.Default.Check, null) })
                    }
                }
            } else Pill(p.role.label, tint = tint)
        }
    }
}

