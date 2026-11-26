package org.systemoverload.team.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.systemoverload.team.R
import org.systemoverload.team.data.AppModel
import org.systemoverload.team.data.startDemo
import org.systemoverload.team.ui.*

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun SignInScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    AppBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.logo), null, Modifier.size(150.dp).shadow(20.dp, CircleShape).clip(CircleShape))
            Text("SOR Team Board", style = T.largeTitle, modifier = Modifier.padding(top = 28.dp))
            Text("Tasks, meetings and check-ins for FRC 6059 System Overload.", style = T.body, color = P.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable(enabled = !busy) {
                        val a = context.findActivity() ?: return@clickable
                        busy = true
                        scope.launch { M.signIn(a); busy = false }
                    },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp) else GoogleMark()
                Spacer(Modifier.width(10.dp))
                Text("Continue with Google", style = T.headline, color = Color.Black)
            }
            Text("Use the Google account you use for the team. A Board member approves new people.", style = T.foot, color = P.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { other = true }) { Text("Other sign-in", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { M.startDemo() }) { Text("Look around the demo", fontWeight = FontWeight.SemiBold) }
            }
            Text("By signing in you agree to the team's Community Rules: nothing offensive, abusive or private about others. The Board removes anything that breaks them and can remove the person.",
                style = T.caption, color = P.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp))
            Row(Modifier.padding(bottom = 12.dp)) {
                TextButton(onClick = { openInCustomTab(context, "${AppModel.SITE}/support#rules") }) { Text("Community Rules", style = T.foot) }
                TextButton(onClick = { openInCustomTab(context, "${AppModel.SITE}/privacy") }) { Text("Privacy Policy", style = T.foot) }
            }
        }
    }
    if (other) EmailSignInDialog { other = false }
}

/** The four-colour G, drawn so there's no image asset to license. */
@Composable
fun GoogleMark() {
    Text("G", fontSize = 20.sp, fontWeight = FontWeight.Bold,
        style = androidx.compose.ui.text.TextStyle(brush = Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853)))))
}

/**
 * Sign in with an email and password instead of Google, for accounts an admin made in Firebase (Authentication ›
 * Users). A new account still waits for a Board member's approval.
 */
@Composable
private fun EmailSignInDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (email.isEmpty() || password.isEmpty() || busy) return
        busy = true; error = null
        scope.launch {
            error = M.signIn(email, password)
            busy = false
            if (error == null) onDismiss()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(P.sheet).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Other sign-in", style = T.title3)
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
            Text(error ?: "For accounts the team set up with an email and password. Team members usually use Continue with Google.",
                style = T.foot, color = if (error == null) P.secondary else Brand.red)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { go() }, enabled = email.isNotEmpty() && password.isNotEmpty() && !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text("Sign in")
                }
            }
        }
    }
}

/** The questions everyone answers once (mod-app QUESTIONS), then waiting for a Board member's approval. */
@Composable
fun OnboardingScreen() {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var female by remember { mutableStateOf<Boolean?>(null) }
    var side by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(type: String) {
        saving = true; error = null
        scope.launch {
            try { M.saveAnswers(female ?: false, type) } catch (e: Exception) { error = "Couldn't save your answers: ${AppModel.friendly(e)}" }
            saving = false
        }
    }
    AppBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LinearProgressIndicator(progress = { (step + 1) / 3f }, Modifier.fillMaxWidth().padding(top = 12.dp))
            Spacer(Modifier.weight(1f))
            AnimatedContent(step, label = "question") { s ->
                when (s) {
                    0 -> Question("Do you identify as female?", "Every in-person meeting needs the right adults there, so the team keeps track of this. Only you and team leads can see your answer.",
                        listOf("yes" to "Yes", "no" to "No"), saving) { female = it == "yes"; step = 1 }
                    1 -> Question("Are you a student or a parent?", null, listOf("student" to "Student", "adult" to "Parent or mentor"), saving) { side = it; step = 2 }
                    else -> Question(if (side == "adult") "How do you help the team?" else "What's your role on the team?", null,
                        if (side == "adult") listOf("mentor" to "Mentor", "parent" to "Parent volunteer") else listOf("student" to "Student", "lead" to "Lead", "board" to "Board"),
                        saving) { save(it) }
                }
            }
            Spacer(Modifier.weight(1f))
            error?.let {
                Text(it, style = T.foot, color = Brand.red)
                TextButton(onClick = { M.skipAnswers() }) { Text("Skip for now") }
            }
            if (step > 0) TextButton(onClick = { step -= 1 }, enabled = !saving) { Text("Back") }
        }
    }
}

@Composable
private fun Question(title: String, why: String?, options: List<Pair<String, String>>, saving: Boolean, pick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = T.largeTitle)
        if (why != null) Text(why, style = T.body, color = P.secondary)
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !saving) { pick(value) }.glass(18.dp).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = T.headline, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = P.tertiary)
            }
        }
    }
}

@Composable
fun PendingScreen() {
    AppBackground {
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(M.profile?.name ?: "", M.authPhoto, 96.dp)
            Text("Almost in, ${M.profile?.firstName ?: ""}", style = T.title)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(Brand.amber))
                Text(buildAnnotatedString {
                    append("A Board member needs to approve ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(M.authEmail) }
                    append(". The app opens by itself as soon as they do.")
                }, style = T.body, color = P.secondary, textAlign = TextAlign.Center)
            }
            Text(buildAnnotatedString {
                append("Signed up as ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(M.profile?.memberTypeLabel ?: "a team member") }
            }, style = T.foot, color = P.secondary)
            OutlinedButton(onClick = { M.signOut() }, Modifier.padding(top = 12.dp)) { Text("Use a different account") }
        }
    }
}
