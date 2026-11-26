@file:OptIn(ExperimentalLayoutApi::class)

package org.systemoverload.team.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.systemoverload.team.data.Helper
import org.systemoverload.team.data.open
import org.systemoverload.team.ui.*

/** One helper per signed-in person, kept while the app runs (the chat itself is kept on this phone). */
private var helper: Helper? = null

/** The helper's chat. It opens from the sparkle button on Home (and You), never by itself. */
@Composable
fun HelperScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val h = remember(M.uid) { helper?.takeIf { helperUid == M.uid } ?: Helper(M).also { helper = it; helperUid = M.uid } }
    var text by remember { mutableStateOf("") }
    // Permission before personal data goes to an outside AI: once per person, on this phone.
    val consentKey = "sor-helper-consent:${M.uid}"
    var consented by remember { mutableStateOf(M.prefs.getBoolean(consentKey, false)) }
    var menu by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(h) {
        h.restore()
        h.onOpen = { path -> nav.pop(); scope.launch { delay(300); M.open(path) } }
    }
    LaunchedEffect(h.items.size, h.busy) { val n = h.items.size + (if (h.busy) 2 else 1); if (n > 0) state.animateScrollToItem(n - 1) }

    val suggestions = listOf("What's on my plate this week?", "When's the next hangar meeting?") +
        if (M.canEdit) listOf("Add a task for me to…", "Mark one of my tasks done") else listOf("What is the team working on?")

    fun send() { val t = text; text = ""; h.send(t) }

    Page("Helper", tint = Brand.violet, actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Clear the chat") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; h.clear() })
            }
        }
    }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (!consented) Consent({ nav.pop() }) { M.prefs.edit().putBoolean(consentKey, true).apply(); consented = true }
                    else Intro(h, suggestions) { s -> if (s.endsWith("…")) text = s.dropLast(1) else h.send(s) }
                }
                items(h.items, key = { it.id }) { item -> ItemRow(item, h) }
                if (h.busy) item(key = "busy") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Thinking…", color = P.secondary, style = T.sub)
                    }
                }
            }
            if (consented) Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(if (M.canEdit) "Ask, or tell it what to do" else "Ask about the board") },
                    maxLines = 5, shape = RoundedCornerShape(22.dp))
                FilledIconButton(onClick = { send() }, enabled = text.isNotBlank(), modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.violet)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

private var helperUid = ""

/** Before the first message: what goes to Google, and a clear yes or no. */
@Composable
private fun Consent(no: () -> Unit, yes: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(20.dp, Brand.violet).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(Icons.Default.AutoAwesome, Brand.violet, 30.dp)
            Text("Before you use the helper", style = T.headline)
        }
        Text(buildAnnotatedString {
            append("The helper runs on ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Google Gemini") }
            append(", outside the team. It sends Google what you type, your name, and the board's open tasks, people, upcoming meetings and action items.")
        }, style = T.sub)
        Text("Never emails, attendance, private answers or anything confidential. The team saves chats to stop misuse of its key. See the Privacy Policy.", style = T.foot, color = P.secondary)
        Row {
            OutlinedButton(onClick = no) { Text("Not now") }
            Spacer(Modifier.weight(1f))
            Button(onClick = yes, colors = ButtonDefaults.buttonColors(containerColor = Brand.violet)) { Text("Allow", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Intro(h: Helper, suggestions: List<String>, pick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(Icons.Default.AutoAwesome, Brand.violet, 34.dp)
            Column {
                Text("Ask about the board", style = T.headline)
                Text(if (M.canEdit) "Or tell it what to change. Nothing changes until you tap Do it." else "Tasks, meetings, who's on what.", style = T.caption, color = P.secondary)
            }
        }
        Text("This runs on the team's own Gemini key, so we ask that you don't misuse it. Every chat is saved so misuse can be stopped. It can also get things wrong.",
            style = T.caption, color = P.secondary)
        if (h.off.isNotEmpty()) Text(h.off, style = T.caption, color = Brand.amber)
        if (h.items.isEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                Text(s, style = T.sub, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { pick(s) }.glass(16.dp, Brand.violet).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun ItemRow(item: Helper.Item, h: Helper) {
    when (item) {
        is Helper.Item.You -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(item.text, color = Color.White, modifier = Modifier.padding(start = 50.dp).clip(RoundedCornerShape(18.dp)).background(Brand.wash(Brand.blue)).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        is Helper.Item.It -> Row(Modifier.fillMaxWidth()) {
            SelectionContainer(Modifier.padding(end = 40.dp)) { Text(markdown(item.text), modifier = Modifier.glass(18.dp).padding(horizontal = 14.dp, vertical = 10.dp)) }
        }
        is Helper.Item.Note -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp), tint = P.secondary); Spacer(Modifier.width(6.dp))
            Text(item.text, style = T.caption, color = P.secondary)
        }
        is Helper.Item.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = Brand.amber); Spacer(Modifier.width(6.dp))
            Text(item.text, style = T.sub, color = Brand.amber)
        }
        is Helper.Item.Card -> h.cards[item.id]?.let { (plan, st) -> Card(item.id, plan, st, h) }
    }
}

@Composable
private fun Card(id: String, plan: Helper.Plan, state: Helper.CardState, h: Helper) {
    Column(Modifier.fillMaxWidth().glass(18.dp, Brand.violet).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(plan.title.uppercase(), style = T.tiny, color = Brand.violet)
        Text(plan.head, style = T.headline)
        plan.lines.filter { it.isNotEmpty() }.forEach { Text(it, style = T.sub, color = P.secondary) }
        when (state) {
            Helper.CardState.Ask -> Row(Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { h.decide(id, false) }) { Text("Not now") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { h.decide(id, true) }, colors = ButtonDefaults.buttonColors(containerColor = Brand.violet)) { Text("Do it", fontWeight = FontWeight.SemiBold) }
            }
            Helper.CardState.Doing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Helper.CardState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Brand.green); Spacer(Modifier.width(6.dp))
                Text("Done", style = T.subStrong, color = Brand.green, modifier = Modifier.weight(1f))
                plan.path?.let { p -> TextButton(onClick = { h.onOpen?.invoke(p) }) { Text("Open") } }
            }
            Helper.CardState.No -> Text("You said no", style = T.sub, color = P.secondary)
            is Helper.CardState.Failed -> Text(state.msg, style = T.sub, color = Brand.amber)
        }
    }
}

/** **bold**, *italic* and `code` from Gemini's replies. */
private fun markdown(s: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)|`([^`]+)`")
    var i = 0
    for (m in re.findAll(s)) {
        append(s.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) { append(m.groupValues[3]) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
