@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.systemoverload.team.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.systemoverload.team.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

// Faces

/** A round photo, or initials on a tint when there's no photo. */
@Composable
fun Avatar(name: String, photoURL: String, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Initials(name, size)
        if (photoURL.isNotEmpty()) AsyncImage(photoURL, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun Initials(name: String, size: Dp) {
    val letters = name.split(" ").filter { it.isNotEmpty() }.take(2).map { it.first() }.joinToString("").uppercase()
    val hue = (abs(name.hashCode()) % 360).toFloat()
    Box(Modifier.fillMaxSize().background(Color.hsv(hue, 0.45f, 0.55f)), contentAlignment = Alignment.Center) {
        Text(letters, color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PersonAvatar(id: String, size: Dp = 24.dp) {
    val p = M.person(id)
    Avatar(p?.name ?: "?", p?.photoURL ?: "", size)
}

/** Overlapping faces for a task's owners. */
@Composable
fun AvatarStack(ids: List<String>, size: Dp = 22.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(-size * 0.3f)) {
        ids.take(4).forEach { id ->
            Box(Modifier.size(size + 4.dp).clip(CircleShape).background(P.card), contentAlignment = Alignment.Center) { PersonAvatar(id, size) }
        }
    }
}

// Small pieces

@Composable
fun Pill(text: String, icon: ImageVector? = null, tint: Color = P.secondary) {
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = tint)
        Text(text, style = T.caption.copy(fontWeight = FontWeight.Medium), color = tint, maxLines = 1)
    }
}

/** Due-date pill coloured by how close it is. */
@Composable
fun DuePill(iso: String, done: Boolean = false) {
    if (iso.isEmpty()) return
    val n = Day.until(iso) ?: 99
    val tint = if (done) P.secondary else if (n < 0) Brand.red else if (n <= 2) Brand.amber else P.secondary
    Pill(if (n < 0 && !done) "Overdue · ${Day.pretty(iso)}" else Day.pretty(iso), Icons.Default.CalendarToday, tint)
}

@Composable
fun SubteamDot(color: Color, size: Dp = 9.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = T.title3, modifier = modifier.padding(top = 8.dp))

/** Placeholder rows shaped like the content while it loads (the website's skeletons). */
@Composable
fun SkeletonRows(count: Int = 4, height: Dp = 64.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(P.card.copy(alpha = 0.7f))) }
    }
}

/** A small coloured square with a symbol (list rows, stat tiles). */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brand.wash(color)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.52f), tint = Color.White)
    }
}

/** A number with a label, for the stat row on Home and Analytics. */
@Composable
fun StatTile(value: String, label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .glass(18.dp, color).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = color)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, style = T.caption.copy(fontWeight = FontWeight.Medium), color = P.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round progress ring (goals, countdowns). */
@Composable
fun Ring(progress: Float, color: Color = Brand.blue, width: Dp = 6.dp, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = width.toPx()
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        val s = androidx.compose.ui.geometry.Size(d, d)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, s, style = Stroke(stroke))
        drawArc(color, -90f, 360f * progress.coerceIn(0.001f, 1f), false, tl, s, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A rounded card. */
@Composable
fun CardBox(modifier: Modifier = Modifier, tint: Color? = null, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(22.dp, tint).padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Caption(text: String, color: Color = P.secondary, modifier: Modifier = Modifier) = Text(text, style = T.foot, color = color, modifier = modifier)

// Page scaffolding

/** A page with a top bar: title, back (when pushed), and actions. The page's background shows the colour pools. */
@Composable
fun Page(
    title: String, tint: Color = Brand.blue, back: Boolean = true, large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    AppBackground(tint) {
        Column(Modifier.fillMaxSize()) {
            if (large) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = T.largeTitle, modifier = Modifier.weight(1f))
                    actions()
                }
            } else {
                TopAppBar(
                    title = { Text(title, style = T.headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (back) IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    windowInsets = WindowInsets.statusBars,
                )
            }
            content()
        }
    }
}

/** iOS-style grouped list: sections of rows on frosted cards. */
@Composable
fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (header != null) Text(header.uppercase(), style = T.tiny, color = P.secondary, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().glass(16.dp)) { content() }
        if (footer != null) Text(footer, style = T.foot, color = P.secondary, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp))
    }
}

/** One row in a FormSection. */
@Composable
fun FormRow(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 14.dp), color = P.hairline)

/** "Label ........ value ›" */
@Composable
fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null, enabled: Boolean = true, trailing: @Composable (() -> Unit)? = null) {
    FormRow(onClick = onClick, enabled = enabled) {
        Text(label, style = T.body, modifier = Modifier.weight(1f))
        trailing?.invoke()
        Text(value, style = T.body, color = P.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}


@Composable
fun ToggleRow(label: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    FormRow(onClick = { onChange(!checked) }, enabled = enabled) {
        if (icon != null) Icon(icon, null, tint = P.secondary)
        Column(Modifier.weight(1f)) {
            Text(label, style = T.body)
            if (hint != null) Text(hint, style = T.caption, color = P.secondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Segmented control (the iPhone app's segmented pickers). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {},
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = T.sub) },
            )
        }
    }
}

/** A short message that floats above the tab bar and goes away by itself. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val t = M.toast
    LaunchedEffect(t) { if (t != null) { delay(3200); M.toast = null } }
    AnimatedVisibility(t != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(
                t ?: "", style = T.sub.copy(fontWeight = FontWeight.Medium), textAlign = TextAlign.Center, color = P.text,
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(P.sheet).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

// Colours by kind

fun kindColor(e: CalEvent): Color = when {
    e.isCompetition -> Brand.red
    e.isHangar -> Brand.orange
    e.track == "board" -> Brand.cyan
    e.track == "build" -> Brand.teal
    e.track == "ops" -> Brand.purple
    else -> Brand.blue
}

/** Column headings take the status colour (the website's hover.dev-style boards). */
fun statusColor(columnId: String): Color = when (columnId) {
    "todo" -> Brand.blue; "doing" -> Brand.orange; "done" -> Brand.green; "grants" -> Brand.purple; else -> Brand.teal
}

/** A symbol for each subteam's board. */
fun boardIcon(id: String): ImageVector = when (id) {
    "prog" -> Icons.Default.Code
    "elec" -> Icons.Default.Bolt
    "vision" -> Icons.Default.Visibility
    "cad" -> Icons.Default.ViewInAr
    "mech" -> Icons.Default.Build
    "biz" -> Icons.Default.Work
    "media" -> Icons.Default.Campaign
    "strat" -> Icons.Default.ShowChart
    "drive" -> Icons.Default.SportsEsports
    else -> Icons.Default.GridView
}

// Outside the app

fun openUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    // Slack, Zoom, Maps and Google Docs open in their apps when installed; everything else in a Custom Tab
    val native = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
    try { context.startActivity(native); return } catch (_: ActivityNotFoundException) {} catch (_: Exception) {}
    try { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
    catch (_: Exception) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
}

fun openInCustomTab(context: Context, url: String) {
    try { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
    catch (_: Exception) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}

fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

// Pickers

/** Full-screen picker shell: title, Cancel and Done, a search field, then the list. */
@Composable
fun PickerDialog(title: String, onCancel: () -> Unit, onDone: () -> Unit, query: String, onQuery: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AppBackground {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Text(title, style = T.headline, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    TextButton(onClick = onDone) { Text("Done", fontWeight = FontWeight.SemiBold) }
                }
                SearchField(query, onQuery, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                content()
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), placeholder = { Text(placeholder) }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp),
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear") } },
    )
}

/** Pick the people on a task, searchable (works with a 40-person team). */
@Composable
fun PeoplePicker(title: String = "Owner", selected: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    PickerDialog(title, onDismiss, { onDone(picked); onDismiss() }, query, { query = it }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(M.teammates.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picked = if (p.id in picked) picked - p.id else picked + p.id }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(p.name, p.photoURL, 34.dp)
                    Text(p.name, style = T.body, modifier = Modifier.weight(1f))
                    if (p.id in picked) Icon(Icons.Default.Check, null, tint = P.accent)
                }
            }
        }
    }
}

/** Pick tasks (dependencies, a goal's parts), searchable, from this season's open board. */
@Composable
fun TaskPicker(title: String, selected: List<String>, exclude: String, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    PickerDialog(title, onDismiss, { onDone(picked); onDismiss() }, query, { query = it }) {
        val list = M.tasks.filter { it.id != exclude && (query.isEmpty() || it.title.contains(query, ignoreCase = true)) }
            .sortedWith(compareBy<TaskItem> { if (it.id in selected) 0 else 1 }.thenBy { it.title })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(list, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picked = if (t.id in picked) picked - t.id else picked + t.id }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubteamDot(M.subteam(t.subteamId)?.color ?: Brand.gray)
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = T.body)
                        Text(listOf(M.subteam(t.subteamId)?.name ?: "", M.columnName(t.columnId)).joinToString(" · "), style = T.caption, color = P.secondary)
                    }
                    if (t.id in picked) Icon(Icons.Default.Check, null, tint = P.accent)
                }
            }
        }
    }
}

/** Material date picker for a "YYYY-MM-DD" day. */
@Composable
fun DayPickerDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = (Day.date(initial) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = start)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/** A day row: "Label  Tue, Dec 2  ✕", or "Label  Add". */
@Composable
fun DayRow(label: String, iso: String, enabled: Boolean = true, addLabel: String = "Add", addDefault: () -> String = { Day.iso() }, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    FormRow(onClick = { if (iso.isEmpty()) onChange(addDefault()) else picking = true }, enabled = enabled) {
        Text(label, style = T.body, modifier = Modifier.weight(1f))
        if (iso.isEmpty()) Text(addLabel, color = if (enabled) P.accent else P.secondary, style = T.body)
        else {
            Text(Day.fmt(iso, "EEE, MMM d"), color = P.accent, style = T.body)
            if (enabled) Icon(Icons.Default.Cancel, "Clear", Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") }, tint = P.tertiary)
        }
    }
    if (picking) DayPickerDialog(iso, { picking = false }, onChange)
}

/** A menu of choices shown from a row. */
@Composable
fun <T> ChoiceRow(label: String, value: T, options: List<Pair<T, String>>, enabled: Boolean = true, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueRow(label, options.firstOrNull { it.first == value }?.second ?: "", onClick = { open = true }, enabled = enabled)
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            options.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false },
                    trailingIcon = { if (v == value) Icon(Icons.Default.Check, null) })
            }
        }
    }
}

/** 25 / 50 / 75 / 90% Slack reminder chips (REMINDER_STEPS in index.html and the bot). */
@Composable
fun ReminderChips(on: List<Int>, enabled: Boolean, set: (List<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(25, 50, 75, 90).forEach { p ->
            val active = p in on
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .then(if (active) Modifier.background(Brand.wash(Brand.blue)) else Modifier.background(P.secondary.copy(alpha = 0.15f)))
                    .clickable(enabled = enabled) { set(if (active) on - p else (on + p).sorted()) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$p%", style = T.subStrong, color = if (active) Color.White else if (enabled) P.text else P.secondary) }
        }
    }
}

/** Big single-line text field without a box, for titles in forms. */
@Composable
fun PlainField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = T.body,
               enabled: Boolean = true, singleLine: Boolean = false, minLines: Int = 1, keyboard: KeyboardOptions = KeyboardOptions.Default) {
    TextField(
        value, onChange, modifier.fillMaxWidth(), enabled = enabled, textStyle = style, placeholder = { Text(placeholder, style = style) },
        singleLine = singleLine, minLines = minLines, keyboardOptions = keyboard,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            disabledTextColor = P.text,
        ),
    )
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) =
    Text(text, style = T.body, color = P.secondary, modifier = modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center)

@Composable
fun CheckCircle(done: Boolean, tint: Color, size: Dp = 26.dp, goal: Boolean = false) {
    Icon(
        if (done) Icons.Default.CheckCircle else if (goal) Icons.Default.Flag else Icons.Outlined.Circle, null,
        Modifier.size(size), tint = if (done) Brand.green else tint,
    )
}

