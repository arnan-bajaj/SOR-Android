package org.systemoverload.team.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.systemoverload.team.MainActivity
import org.systemoverload.team.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What the Home Screen widget shows. The app writes it; the widget only reads it (like the iPhone widget). */
data class WidgetSnapshot(
    val meetings: List<Meeting>, val competition: String, val competitionDay: String, val openTasks: Int, val dueSoon: Int,
) {
    data class Meeting(val title: String, val start: Long, val end: Long, val place: String, val going: Boolean)

    fun save(context: Context) {
        val json = JSONObject()
            .put("meetings", JSONArray(meetings.map { JSONObject().put("title", it.title).put("start", it.start).put("end", it.end).put("place", it.place).put("going", it.going) }))
            .put("competition", competition).put("competitionDay", competitionDay).put("openTasks", openTasks).put("dueSoon", dueSoon)
            .toString()
        val prefs = context.getSharedPreferences("sor-widget", Context.MODE_PRIVATE)
        if (prefs.getString("snap", null) == json) return
        prefs.edit().putString("snap", json).apply()
        CoroutineScope(Dispatchers.Default).launch {
            if (GlanceAppWidgetManager(context).getGlanceIds(NextMeetingWidget::class.java).isNotEmpty()) NextMeetingWidget().updateAll(context)
        }
    }

    companion object {
        fun load(context: Context): WidgetSnapshot? {
            val raw = context.getSharedPreferences("sor-widget", Context.MODE_PRIVATE).getString("snap", null) ?: return null
            return runCatching {
                val o = JSONObject(raw)
                val list = o.getJSONArray("meetings")
                WidgetSnapshot(
                    (0 until list.length()).map { i -> list.getJSONObject(i).let { Meeting(it.getString("title"), it.getLong("start"), it.getLong("end"), it.optString("place"), it.optBoolean("going")) } },
                    o.optString("competition"), o.optString("competitionDay"), o.optInt("openTasks"), o.optInt("dueSoon"),
                )
            }.getOrNull()
        }
        fun clear(context: Context) {
            context.getSharedPreferences("sor-widget", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}

class NextMeetingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetSnapshot.load(context)
        provideContent { Content(snap) }
    }

    @Composable
    private fun Content(snap: WidgetSnapshot?) {
        val now = System.currentTimeMillis()
        val meetings = snap?.meetings?.filter { it.end > now } ?: emptyList()
        val secondary = ColorProvider(Color(0xFF9AA3AB))
        Column(
            GlanceModifier.fillMaxSize().background(Color(0xFF1C2125)).cornerRadius(22.dp).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row {
                Image(ImageProvider(R.drawable.logo), "SOR", GlanceModifier.size(18.dp).cornerRadius(9.dp))
                Spacer(GlanceModifier.width(6.dp))
                Text("Coming up", style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                if (snap != null && snap.openTasks > 0) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text("${snap.openTasks} tasks", style = TextStyle(color = ColorProvider(if (snap.dueSoon > 0) Color(0xFFFF9F29) else Color(0xFF9AA3AB)), fontSize = 12.sp))
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            when {
                snap == null -> Text("Open the app to sign in.", style = TextStyle(color = secondary, fontSize = 13.sp))
                meetings.isEmpty() -> Text("Nothing on the calendar.", style = TextStyle(color = secondary, fontSize = 13.sp))
            }
            meetings.take(2).forEach { m ->
                Text(m.title, maxLines = 2, style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text(whenLabel(m, now), maxLines = 1, style = TextStyle(color = secondary, fontSize = 12.sp))
                Spacer(GlanceModifier.height(6.dp))
            }
            if (snap != null && snap.competition.isNotEmpty() && snap.competitionDay.isNotEmpty()) {
                val days = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(snap.competitionDay)).coerceAtLeast(0) }.getOrDefault(0)
                Text("$days days to ${snap.competition}", maxLines = 1, style = TextStyle(color = ColorProvider(Color(0xFFFF5A5F)), fontSize = 11.sp, fontWeight = FontWeight.Medium))
            }
        }
    }

    private fun whenLabel(m: WidgetSnapshot.Meeting, now: Long): String {
        val z = ZoneId.systemDefault()
        val t = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val s = Instant.ofEpochMilli(m.start).atZone(z)
        if (m.start <= now) return "Now · until ${Instant.ofEpochMilli(m.end).atZone(z).format(t)}"
        val d = s.toLocalDate()
        val day = when (d) { LocalDate.now() -> "Today"; LocalDate.now().plusDays(1) -> "Tomorrow"; else -> d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) }
        return "$day · ${s.format(t)}"
    }
}

class NextMeetingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextMeetingWidget()
}
