@file:OptIn(ExperimentalMaterial3Api::class)

package org.systemoverload.team.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.systemoverload.team.data.*
import org.systemoverload.team.model.*
import org.systemoverload.team.ui.*

/** Report and Block, for a long-press menu on someone else's comment, photo or message. */
@Composable
fun ReportMenu(expanded: Boolean, onDismiss: () -> Unit, authorUid: String, onReport: () -> Unit) {
    if (authorUid == M.uid) return
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("Report") }, leadingIcon = { Icon(Icons.Default.Report, null) }, onClick = { onDismiss(); onReport() })
        if (authorUid.isNotEmpty()) DropdownMenuItem(
            text = { Text("Block ${M.person(authorUid)?.firstName ?: "this person"}", color = Brand.red) },
            leadingIcon = { Icon(Icons.Default.Block, null, tint = Brand.red) }, onClick = { onDismiss(); M.block(authorUid) },
        )
    }
}

/** Report something: pick why, add a note, optionally block the person too. The Board reviews it (You › Reports). */
@Composable
fun ReportSheet(target: ReportTarget, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val what = mapOf("comment" to "comment", "photo" to "photo", "message" to "message", "task" to "task")[target.kind] ?: "post"
    val author = M.person(target.authorUid)?.firstName ?: "this person"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P.sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Report", style = T.title3, modifier = Modifier.weight(1f))
                if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Button(onClick = {
                    val r = reason ?: return@Button
                    sending = true
                    scope.launch {
                        try {
                            M.report(target, r, note)
                            if (alsoBlock) M.block(target.authorUid)
                            M.toast = "Thanks. The Board will review it."
                            onDismiss()
                        } catch (e: Exception) { M.toast = AppModel.friendly(e); sending = false }
                    }
                }, enabled = reason != null) { Text("Send") }
            }
            FormSection("What's wrong with this $what?") {
                ReportReason.entries.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    FormRow(onClick = { reason = r }) {
                        Text(r.label, modifier = Modifier.weight(1f))
                        if (reason == r) Icon(Icons.Default.Check, null, tint = P.accent)
                    }
                }
            }
            FormSection { PlainField(note, { note = it }, "Anything the Board should know (optional)", minLines = 2) }
            if (target.authorUid.isNotEmpty() && target.authorUid != M.uid) FormSection(footer = "You won't see their comments, photos or Board channel messages. They aren't told.") {
                ToggleRow("Also block $author", alsoBlock) { alsoBlock = it }
            }
            Caption("Reports go to the team's Board, who review them within a day and remove anything that breaks the team's rules. In an emergency, tell a mentor right away.",
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
        }
    }
}

/** You › Blocked people. */
@Composable
fun BlockedPeopleScreen() {
    Page("Blocked people") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (M.blockedUids.isEmpty()) item { EmptyNote("You haven't blocked anyone. Long-press someone's comment, photo or message to report or block them.") }
            items(M.blockedUids.sorted()) { id ->
                Row(Modifier.fillMaxWidth().glass(16.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonAvatar(id, 32.dp)
                    Text(M.person(id)?.name ?: "Someone", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { M.unblock(id) }) { Text("Unblock") }
                }
            }
        }
    }
}

/** You › Reports (admins): what people reported, to remove or dismiss. */
@Composable
fun ReportsScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var handled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { M.wantReports() }
    val list = if (handled) M.reports.filter { !it.isOpen } else M.openReports
    Page("Reports") {
        Segmented(listOf(false to "Open", true to "Handled"), handled, Modifier.padding(horizontal = 16.dp)) { handled = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if ("reports" !in M.loaded && !M.demo) item { SkeletonRows(3) }
            else if (list.isEmpty()) item { EmptyNote(if (handled) "Nothing handled yet." else "No open reports.") }
            items(list, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().glass(16.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text(r.kindLabel.uppercase(), style = T.tiny, color = P.secondary, modifier = Modifier.weight(1f))
                        Text(Day.relative(r.createdAt), style = T.caption, color = P.secondary)
                    }
                    Text(r.reason, style = T.headline)
                    if (r.text.isNotEmpty()) Text(r.text, style = T.sub, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).glass(12.dp).padding(10.dp))
                    Text("By ${r.authorName.ifEmpty { "someone" }} · reported by ${r.reporterName.ifEmpty { "someone" }}", style = T.caption, color = P.secondary)
                    if (r.note.isNotEmpty()) Text("“${r.note}”", style = T.caption.copy(fontStyle = FontStyle.Italic))
                    if (r.isOpen) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (r.taskId.isNotEmpty()) OutlinedButton(onClick = { nav.push(Screen.Task(r.taskId)) }) { Text("Open task") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { M.setReport(r, "resolved") }) { Text("Dismiss") }
                        if (M.canRemove(r)) Button(onClick = { scope.launch { M.removeReported(r) } }, colors = ButtonDefaults.buttonColors(containerColor = Brand.red)) { Text("Remove") }
                    } else Text("${if (r.status == "removed") "Removed" else "Dismissed"}${if (r.resolvedByName.isEmpty()) "" else " by ${r.resolvedByName}"}",
                        style = T.captionStrong, color = if (r.status == "removed") Brand.red else P.secondary)
                }
            }
            item {
                Caption("Anyone on the team can report a comment, photo, Board channel message or task by long-pressing it. Remove takes it down for everyone; Dismiss keeps it.")
            }
        }
    }
}

/**
 * A page of the team website inside the app, already signed in. The app hands the website a Google token
 * (window.sorAppToken), and the website signs in with it instead of showing Google's page, which Google
 * doesn't allow inside an app's web view. The website hides its own tab bar here (html.in-app).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPageScreen(title: String, path: String) {
    val context = LocalContext.current
    var token by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val url = "${AppModel.SITE}/${path.trimStart('/')}"
    LaunchedEffect(Unit) {
        token = context.findActivity()?.let { M.googleTokenForWeb(it) }
        ready = true
    }
    BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }
    Page(title, actions = {
        IconButton(onClick = { openInCustomTab(context, url) }) { Icon(Icons.Default.OpenInBrowser, "Open in browser") }
    }) {
        Box(Modifier.fillMaxSize()) {
            if (ready) AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString + " SORTeamApp/${M.version}" // the website adds html.in-app for this
                    setBackgroundColor(AColor.TRANSPARENT)
                    val inject = token?.let { "window.sorAppToken = ${JSONObject().put("idToken", it).put("accessToken", JSONObject.NULL)};" }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                            if (inject != null) view.evaluateJavascript(inject, null)
                        }
                        override fun onPageFinished(view: WebView, u: String?) { loading = false }
                        /** The website's own pages stay here; everything else (Slack, Zoom, Google Docs, Maps) opens in its app or the browser. */
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val h = req.url.host ?: return false
                            if (h == AppModel.SITE_HOST || h == "frc6059-9e793.firebaseapp.com") return false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                            return true
                        }
                    }
                    if (inject != null) {
                        // Before the website's scripts run, so its sign-in check finds the token
                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, inject, setOf("https://${AppModel.SITE_HOST}"))
                        }
                    }
                    loadUrl(url)
                    web = this
                }
            }, modifier = Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}
