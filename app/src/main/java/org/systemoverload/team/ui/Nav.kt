package org.systemoverload.team.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.systemoverload.team.SorApp
import org.systemoverload.team.data.AppModel
import org.systemoverload.team.model.Track

/** The app's model, everywhere in the UI. */
internal val M: AppModel get() = SorApp.model

enum class Tab(val label: String) { Home("Home"), Tasks("Tasks"), Create("Create"), Meetings("Meetings"), You("You") }

/** Every page that can be pushed on a tab. */
sealed class Screen {
    data class Board(val id: String) : Screen()
    data class Task(val id: String) : Screen()
    data class ActionItems(val boardRoom: Boolean = false, val track: Track = Track.board) : Screen()
    data class ActionItem(val id: String, val confidential: Boolean) : Screen()
    data object Timeline : Screen()
    data object Archive : Screen()
    data class Meeting(val eventId: String) : Screen()
    data class Agenda(val id: String) : Screen()
    data class AgendaDoc(val id: String, val board: Boolean) : Screen()
    data object Team : Screen()
    data object BoardRoom : Screen()
    data object Analytics : Screen()
    data object HelperChats : Screen()
    data object SignupRecords : Screen()
    data object Notifications : Screen()
    data object Reports : Screen()
    data object Blocked : Screen()
    data object Helper : Screen()
    data object CheckIn : Screen()
    data class NewTask(val subteamId: String? = null, val columnId: String? = null) : Screen()
    data class NewActionItem(val track: Track, val confidential: Boolean) : Screen()
    data class AddPeople(val shiftId: String) : Screen()
    data class Web(val title: String, val path: String) : Screen()
}

/** One back stack per tab, like the iPhone app's NavigationStacks. System back pops it. */
class Nav {
    var tab by mutableStateOf(Tab.Home)
    var lastTab = Tab.Home
    val stacks = Tab.entries.associateWith { mutableStateListOf<Screen>() }
    /** Bumped after each new task, so the Create tab starts empty next time. */
    var createRun by mutableStateOf(0)

    val stack get() = stacks.getValue(tab)
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean { if (stack.isEmpty()) return false; stack.removeAt(stack.size - 1); return true }
    fun select(t: Tab) {
        if (t == tab) { stacks.getValue(t).clear(); return } // tapping the tab you're on goes back to its top
        if (t != Tab.Create) lastTab = t
        if (tab != Tab.Create) lastTab = tab
        tab = t
    }
    /** Create's Cancel or Add goes back to where you were. */
    fun leaveCreate() { createRun++; tab = lastTab }
}

val LocalNav = staticCompositionLocalOf { Nav() }
