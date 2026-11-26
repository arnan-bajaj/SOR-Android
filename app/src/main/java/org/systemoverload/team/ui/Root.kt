package org.systemoverload.team.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.systemoverload.team.R
import org.systemoverload.team.data.AppLink
import org.systemoverload.team.data.AppModel
import org.systemoverload.team.data.notificationsPermissionNeeded
import org.systemoverload.team.data.setUpPush
import org.systemoverload.team.model.Track
import org.systemoverload.team.ui.screens.*

@Composable
fun Root(appearance: String, setAppearance: (String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (val ph = M.phase) {
            AppModel.Phase.NotConfigured -> Message("Firebase isn't set up", "This build has no google-services.json. See the README, then build again.")
            AppModel.Phase.Starting, AppModel.Phase.LoadingProfile -> Launch()
            AppModel.Phase.SignedOut -> SignInScreen()
            AppModel.Phase.Onboarding -> OnboardingScreen()
            AppModel.Phase.Pending -> PendingScreen()
            AppModel.Phase.Ready -> MainTabs(appearance, setAppearance)
            is AppModel.Phase.Failed -> Message("Something went wrong", ph.message, "Sign out") { M.signOut() }
        }
        ToastHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))
    }
}

@Composable
private fun Launch() = AppBackground {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(120.dp).clip(CircleShape))
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun Message(title: String, text: String, button: String? = null, action: () -> Unit = {}) = AppBackground {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Warning, null, Modifier.size(44.dp), tint = P.secondary)
        Text(title, style = T.title)
        Text(text, style = T.body, color = P.secondary, textAlign = TextAlign.Center)
        if (button != null) OutlinedButton(onClick = action) { Text(button) }
    }
}

/** Home, Tasks, Create, Meetings, You: the same five tabs as the website on a phone and the iPhone app. */
@Composable
private fun MainTabs(appearance: String, setAppearance: (String) -> Unit) {
    val nav = remember { Nav() }
    val holder = rememberSaveableStateHolder()
    val askPush = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { M.setUpPush() }

    LaunchedEffect(Unit) {
        // Android 13+ asks once, after sign-in, like the iPhone app
        if (!M.demo && M.notificationsPermissionNeeded() && !M.prefs.getBoolean("askedPush", false)) {
            M.prefs.edit().putBoolean("askedPush", true).apply()
            askPush.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Links, notifications and QR codes land on whichever tab you're on
    val link = M.openLink
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        M.openLink = null
        if (nav.tab == Tab.Create) nav.tab = nav.lastTab
        nav.push(when (link) {
            is AppLink.Task -> Screen.Task(link.id)
            is AppLink.Action -> {
                val a = M.actionItems.firstOrNull { it.id == link.id } ?: M.privateActionItems.firstOrNull { it.id == link.id }
                if (a != null) Screen.ActionItem(a.id, a.confidential) else Screen.ActionItems()
            }
            is AppLink.Meeting -> if (M.events.any { it.id == link.id }) Screen.Meeting(link.id) else Screen.Web("Signups", "signups/${link.id}")
            is AppLink.Web -> nativeFor(link.path) ?: Screen.Web("Team Board", link.path)
        })
    }
    LaunchedEffect(M.showHelper) { if (M.showHelper) { M.showHelper = false; nav.push(Screen.Helper) } }

    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.Home) {
        if (!nav.pop()) { if (nav.tab == Tab.Create) nav.leaveCreate() else nav.tab = Tab.Home }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Scaffold(
            containerColor = P.page,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar(containerColor = P.card.copy(alpha = 0.96f), tonalElevation = 0.dp) {
                    val tabs = Tab.entries.filter { it != Tab.Create || M.canEdit }
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = nav.tab == t, onClick = { nav.select(t) },
                            icon = { Icon(tabIcon(t), null) }, label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(pad)) {
                val top = nav.stack.lastOrNull()
                AnimatedContent(
                    targetState = Triple(nav.tab, nav.stack.size, top),
                    transitionSpec = {
                        val sameTab = initialState.first == targetState.first
                        val forward = targetState.second > initialState.second
                        if (!sameTab) fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                        else if (forward) slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)) togetherWith fadeOut(tween(160))
                        else fadeIn(tween(200)) togetherWith slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180))
                    },
                    label = "page",
                ) { (tab, size, screen) ->
                    holder.SaveableStateProvider("$tab/$size/${screen ?: "root"}") {
                        if (screen == null) TabRoot(tab, appearance, setAppearance) else ScreenContent(screen)
                    }
                }
            }
        }
    }
}

private fun tabIcon(t: Tab): ImageVector = when (t) {
    Tab.Home -> Icons.Default.Home
    Tab.Tasks -> Icons.Default.Apps
    Tab.Create -> Icons.Default.AddCircle
    Tab.Meetings -> Icons.Default.CalendarMonth
    Tab.You -> Icons.Default.AccountCircle
}

@Composable
private fun TabRoot(tab: Tab, appearance: String, setAppearance: (String) -> Unit) {
    val nav = LocalNav.current
    when (tab) {
        Tab.Home -> HomeScreen()
        Tab.Tasks -> TasksScreen()
        Tab.Create -> key(nav.createRun) { NewTaskScreen(onDone = { nav.leaveCreate() }) }
        Tab.Meetings -> MeetingsScreen()
        Tab.You -> YouScreen(appearance, setAppearance)
    }
}

@Composable
fun ScreenContent(s: Screen) {
    when (s) {
        is Screen.Board -> BoardScreen(s.id)
        is Screen.Task -> TaskDetailScreen(s.id)
        is Screen.ActionItems -> ActionItemsScreen(s.boardRoom, s.track)
        is Screen.ActionItem -> ActionItemDetailScreen(s.id, s.confidential)
        Screen.Timeline -> TimelineScreen()
        Screen.Archive -> ArchiveScreen()
        is Screen.Meeting -> MeetingDetailScreen(s.eventId)
        is Screen.Agenda -> AgendaDetailScreen(s.id)
        is Screen.AgendaDoc -> AgendaDocScreen(s.id, s.board)
        Screen.Team -> TeamScreen()
        Screen.BoardRoom -> BoardRoomScreen()
        Screen.Analytics -> AnalyticsScreen()
        Screen.HelperChats -> HelperChatsScreen()
        Screen.SignupRecords -> SignupRecordsScreen()
        Screen.Notifications -> NotificationSettingsScreen()
        Screen.Reports -> ReportsScreen()
        Screen.Blocked -> BlockedPeopleScreen()
        Screen.Helper -> HelperScreen()
        Screen.CheckIn -> CheckInScreen()
        is Screen.NewTask -> { val nav = LocalNav.current; NewTaskScreen(s.subteamId, s.columnId, onDone = { nav.pop() }) }
        is Screen.NewActionItem -> NewActionItemScreen(s.track, s.confidential)
        is Screen.AddPeople -> AddPeopleScreen(s.shiftId)
        is Screen.Web -> WebPageScreen(s.title, s.path)
    }
}

/** Website paths the app has its own page for (open_page from the helper, links to the website). */
private fun nativeFor(path: String): Screen? {
    val p = path.trim('/')
    return when {
        p.startsWith("board/") -> p.removePrefix("board/").let { if (it == "mine") Screen.Board("mine") else Screen.Board(it) }
        p == "mine" -> Screen.Board("mine")
        p == "timeline" -> Screen.Timeline
        p == "archive" -> Screen.Archive
        p == "analytics" -> Screen.Analytics
        p == "team" -> Screen.Team
        p.startsWith("actions") -> Screen.ActionItems(track = Track.of(p.removePrefix("actions").trim('/')) ?: Track.board)
        p == "boardroom" || p == "board-room" -> if (M.canSeeBoardRoom) Screen.BoardRoom else null
        else -> null
    }
}

