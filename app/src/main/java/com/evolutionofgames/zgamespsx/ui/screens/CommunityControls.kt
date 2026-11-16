package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolutionofgames.zgamespsx.community.CommunityApi
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PostCommunity(game: String, modifier: Modifier = Modifier) {
    val config by WpConfig.content.collectAsState()
    val comments = config.flag("post_comments", false)
    val ratings = false
    if (!comments) return
    var open by remember(game) { mutableStateOf(false) }
    val ctx = LocalContext.current
    var page by remember(game) { mutableStateOf<JSONObject?>(null) }
    var failed by remember(game) { mutableStateOf(false) }
    var revision by remember(game) { mutableIntStateOf(0) }
    LaunchedEffect(game, revision) {
        try { page = CommunityApi.publicPost(ctx, game); failed = false }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed = true }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reviews & comments", style = MaterialTheme.typography.titleMedium)
        val rows = page?.optJSONArray("comments")
        if (failed) Text("Comments couldn't be loaded.", style = MaterialTheme.typography.bodySmall)
        else if (page == null) Text("Loading comments…", style = MaterialTheme.typography.bodySmall)
        else if (rows == null || rows.length() == 0) Text("No approved comments yet.", style = MaterialTheme.typography.bodySmall)
        else for (i in 0 until minOf(rows.length(), 3)) {
            val row = rows.optJSONObject(i) ?: continue
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.optString("name").ifBlank { "Guest" }, style = MaterialTheme.typography.labelLarge)
                    Text(row.optString("text"), style = MaterialTheme.typography.bodyMedium)
                    val reply = row.optString("reply")
                    if (reply.isNotBlank()) Text("ZGames team: $reply", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("View all & add comment") }
    }
    if (open) PostCommunityDialog(game, comments, ratings) { open = false; revision++ }
}

private var ratingsRevision by mutableIntStateOf(0)
private val ratingsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
private val ratingsLimit = kotlinx.coroutines.sync.Semaphore(4)

@Composable
fun RatingLabel(game: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    val config by WpConfig.content.collectAsState()
    if (!config.flag("post_ratings", false)) return
    val ctx = LocalContext.current
    var rating by remember(game) { mutableStateOf(ratingsCache[game]?.second ?: "") }
    val revision = ratingsRevision
    LaunchedEffect(game, revision) {
        val cached = ratingsCache[game]
        if (cached == null || System.currentTimeMillis() - cached.first > 300_000L) {
            try {
                ratingsLimit.acquire()
                try {
                    val r = CommunityApi.publicRating(ctx, game).optJSONObject("ratings")
                    val n = r?.optInt("count", 0) ?: 0
                    val label = if (n > 0) "★ %.1f (%d)".format(java.util.Locale.US, r!!.optDouble("average"), n) else "★ No ratings"
                    ratingsCache[game] = System.currentTimeMillis() to label
                    rating = label
                } finally { ratingsLimit.release() }
            } catch (_: Exception) { }
        }
    }
    if (rating.isNotEmpty()) Text(rating, modifier = modifier, color = color, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
}

@Composable
fun RatingAction(game: String, modifier: Modifier = Modifier) {
    val config by WpConfig.content.collectAsState()
    if (!config.flag("post_ratings", false)) return
    var open by remember(game) { mutableStateOf(false) }
    val shine = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) MaterialTheme.colorScheme.primary else com.evolutionofgames.zgamespsx.ui.theme.PsxColors.Gold
    OutlinedButton(onClick = { open = true }, modifier = modifier,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, shine),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = shine.copy(alpha = 0.12f))) {
        Text("★ Rate this game", color = shine)
        RatingLabel(game, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) PostCommunityDialog(game, false, true) { ratingsCache.remove(game); ratingsRevision++; open = false }
}

@Composable
private fun PostCommunityDialog(game: String, comments: Boolean, ratings: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember(game) { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var stars by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    suspend fun refresh() { try { page = CommunityApi.publicPost(ctx, game); error = "" } catch (e: Exception) { error = e.message ?: "Couldn't load." } }
    LaunchedEffect(game) { refresh() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Community") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            if (ratings) {
                val r = page?.optJSONObject("ratings")
                val count = r?.optInt("count", 0) ?: 0
                Text(if (count > 0) "Rating: ${String.format("%.1f", r?.optDouble("average", 0.0))} / 5 · $count ratings" else "No ratings yet")
                Row(Modifier.fillMaxWidth()) { (1..5).forEach { n -> TextButton(onClick = { stars = n }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(if (stars >= n) "★" else "☆") } } }
                Button(enabled = stars > 0 && !busy, onClick = {
                    busy = true; notice = ""; error = ""
                    scope.launch { try { CommunityApi.send(ctx, JSONObject().put("kind", "rating").put("game", game).put("rating", stars)); notice = "Rating saved."; refresh() } catch (e: Exception) { error = e.message ?: "Couldn't send." }; busy = false }
                }) { Text("Submit rating") }
            }
            if (comments) {
                Text("Comments", style = MaterialTheme.typography.titleMedium)
                val rows = page?.optJSONArray("comments")
                if (rows == null || rows.length() == 0) Text("No approved comments yet.")
                if (rows != null) for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    Text(row.optString("name", "Guest"), style = MaterialTheme.typography.labelLarge)
                    Text(row.optString("text"))
                    if (row.optString("reply").isNotBlank()) Text("ZGames team: ${row.optString("reply")}", color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = message, onValueChange = { message = it.take(750) }, label = { Text("Comment") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("Comments are public after moderation. Don't share private information.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && message.isNotBlank(), onClick = {
                    busy = true; notice = ""; error = ""
                    scope.launch { try { CommunityApi.send(ctx, JSONObject().put("kind", "comment").put("game", game).put("name", name.trim()).put("text", message.trim())); message = ""; notice = "Sent for moderation." } catch (e: Exception) { error = e.message ?: "Couldn't send." }; busy = false }
                }) { Text("Send comment") }
            }
        }
    }, confirmButton = { TextButton(onClick = close, enabled = !busy) { Text("Close") } })
}

@Composable
fun CommunityInboxAction(modifier: Modifier = Modifier) {
    val flags by WpConfig.content.collectAsState()
    if (!flags.flag("support_requests", false) && !flags.flag("post_comments", false)) return
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("Help & replies") }
    if (open) CommunityInboxDialog(flags.flag("support_requests", false)) { open = false }
}

@Composable
private fun CommunityInboxDialog(support: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() { try { page = CommunityApi.inbox(ctx); error = "" } catch (e: Exception) { error = e.message ?: "Couldn't load." } }
    LaunchedEffect(Unit) { refresh() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Help & replies") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Replies belong to this installation and cannot be restored after reinstall. Don't include passwords or sensitive details.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            val items = page?.optJSONArray("items")
            if (items == null || items.length() == 0) Text("No requests or replies yet.")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val isSupport = item.optString("kind") == "support"
                Text(if (isSupport) "Help request #${item.optLong("id")}" else "Comment · ${item.optString("game")}", style = MaterialTheme.typography.titleSmall)
                Text(item.optString("text"))
                if (isSupport) {
                    val thread = item.optJSONArray("thread")
                    if (thread != null) for (j in 0 until thread.length()) {
                        val part = thread.optJSONObject(j) ?: continue
                        Text((if (part.optString("from") == "admin") "ZGames team: " else "You: ") + part.optString("text"))
                    }
                    if (support) TextButton(onClick = { target = item.optLong("id"); text = "" }) { Text("Reply to this request") }
                } else {
                    if (!item.optBoolean("approved")) Text("Awaiting moderation", style = MaterialTheme.typography.bodySmall)
                    val reply = item.optJSONObject("reply")?.optString("text").orEmpty()
                    if (reply.isNotBlank()) Text("ZGames team: $reply", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (support) {
                if (target != null) TextButton(onClick = { target = null; text = "" }) { Text("Start a new request instead") }
                OutlinedTextField(value = text, onValueChange = { text = it.take(750) }, label = { Text(if (target == null) "New help request" else "Your reply") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(enabled = text.isNotBlank() && !busy, onClick = {
                    busy = true; error = ""
                    scope.launch { try {
                        val data = JSONObject().put("kind", if (target == null) "support" else "followup").put("text", text.trim())
                        target?.let { data.put("id", it) }
                        CommunityApi.send(ctx, data); text = ""; target = null; refresh()
                    } catch (e: Exception) { error = e.message ?: "Couldn't send." }; busy = false }
                }) { Text(if (busy) "Sending…" else "Send") }
            }
        }
    }, confirmButton = { TextButton(onClick = close, enabled = !busy) { Text("Close") } })
}

@Composable
fun NotificationInboxAction(modifier: Modifier = Modifier, iconOnly: Boolean = false) {
    val flags by WpConfig.content.collectAsState()
    if (!flags.flag("notification_inbox", false)) return
    val ctx = LocalContext.current
    val entries by com.evolutionofgames.zgamespsx.push.NotificationInbox.items.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (iconOnly) androidx.compose.material3.IconButton(onClick = { open = true }, modifier = modifier) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.NotificationsActive, contentDescription = "Notifications", tint = MaterialTheme.colorScheme.onSurface)
    } else OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("Notification inbox") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Notifications") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) Text("No notifications received yet.")
            entries.forEach { notice ->
                Text(notice.title, style = MaterialTheme.typography.titleSmall)
                Text(notice.body)
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(notice.time)), style = MaterialTheme.typography.bodySmall)
                if ((notice.action == "open_page" && notice.page.isNotBlank()) || (notice.action == "open_screen" && notice.screen.isNotBlank()) || (notice.action == "open_url" && notice.url.startsWith("https://"))) {
                    TextButton(onClick = {
                        open = false
                        when (notice.action) {
                            "open_page" -> WpConfig.openPage(notice.page)
                            "open_screen" -> WpConfig.requestScreen(notice.screen)
                            "open_url" -> try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(notice.url))) } catch (_: Exception) { }
                        }
                    }) { Text("Open") }
                }
            }
            if (entries.isNotEmpty()) TextButton(onClick = { com.evolutionofgames.zgamespsx.push.NotificationInbox.clear(ctx) }) { Text("Clear inbox") }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
