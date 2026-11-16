package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val feedbackClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader("Accept-Encoding").build()) }
    .build()

/** Only shown after a plugin config explicitly enables the selected feature. */
@Composable
fun FeedbackAction(kind: String, game: String = "", gameId: String = "", modifier: Modifier = Modifier) {
    val config by WpConfig.content.collectAsState()
    if (!config.flag(kind, false)) return
    var open by remember(kind, gameId) { mutableStateOf(false) }
    val report = kind == "report_broken_link"
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text(if (report) "Report a broken link" else "Request a game")
    }
    if (open) FeedbackDialog(kind, game, gameId, onClose = { open = false })
}

@Composable
private fun FeedbackDialog(kind: String, game: String, gameId: String, onClose: () -> Unit) {
    val report = kind == "report_broken_link"
    var name by remember { mutableStateOf(if (report) game.take(160) else "") }
    var details by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (report) "Report a broken link" else "Request a game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sent) {
                    Text("Thanks! Your message was sent to the ZGames team.")
                } else {
                    if (report && game.isNotBlank()) Text("Game: $game") else OutlinedTextField(
                        value = name, onValueChange = { name = it.take(160) },
                        label = { Text("Game name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = details, onValueChange = { details = it.take(750) },
                        label = { Text(if (report) "Which link is broken? (optional)" else "Details (optional)") },
                        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Sent anonymously. Please don't include passwords or personal information.", style = MaterialTheme.typography.bodySmall)
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (sent) TextButton(onClick = onClose) { Text("Done") }
            else Button(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                result = ""
                scope.launch {
                    result = sendFeedback(kind, name.trim(), gameId, details.trim())
                    busy = false
                    sent = result.isEmpty()
                }
            }) { Text(if (busy) "Sending…" else "Send") }
        },
        dismissButton = { if (!sent) TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } }
    )
}

private suspend fun sendFeedback(kind: String, game: String, gameId: String, message: String): String = withContext(Dispatchers.IO) {
    try {
        val payload = JSONObject().put("kind", kind).put("game", game).put("game_id", gameId).put("message", message)
        val urls = listOf("${WpConfig.SITE}/wp-json/zgpsx/v1/feedback", "${WpConfig.SITE}/?rest_route=/zgpsx/v1/feedback")
        var result = "Couldn't send your message. Please try again."
        for ((index, url) in urls.withIndex()) {
            val request = Request.Builder().url(url).post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            feedbackClient.newCall(request).execute().use { response ->
                if (response.code == 404 && index == 0) return@use
                result = when {
                    response.isSuccessful -> ""
                    response.code == 429 -> "Please wait a minute before sending again."
                    response.code == 403 -> "This feature is currently unavailable."
                    else -> "Couldn't send your message. Please try again."
                }
                return@withContext result
            }
        }
        result
    } catch (_: Exception) { "Couldn't connect. Check your internet and try again." }
}
