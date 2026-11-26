package org.systemoverload.team

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.systemoverload.team.data.open
import org.systemoverload.team.data.refreshCalendarOnResume
import org.systemoverload.team.ui.Root
import org.systemoverload.team.ui.SorTheme

class MainActivity : ComponentActivity() {
    private val model get() = SorApp.model
    var appearance by mutableStateOf("system")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appearance = model.prefs.getString("appearance", "system") ?: "system"
        model.start()
        if (savedInstanceState == null) handle(intent)
        setContent {
            SorTheme(appearance) {
                Root(appearance = appearance, setAppearance = { appearance = it; model.prefs.edit().putString("appearance", it).apply() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        model.refreshCalendarOnResume()
    }

    /** A team.systemoverload.org link, or a tapped notification (the bot puts the website path in `path`). */
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.data?.let { if (intent.action == Intent.ACTION_VIEW) { model.open(it); return } }
        intent.getStringExtra("path")?.let { model.open(it) }
    }
}
