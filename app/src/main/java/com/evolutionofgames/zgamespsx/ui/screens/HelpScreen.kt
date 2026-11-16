package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.evolutionofgames.zgamespsx.community.CommunityApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evolutionofgames.zgamespsx.remote.WpConfig

@Composable
fun HelpScreen() {
    val config by WpConfig.content.collectAsState()
    val inbox = config.flag("support_requests", false) || config.flag("post_comments", false)
    val broken = config.flag("report_broken_link", false)
    val request = config.flag("request_game", false)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.HeadsetMic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                Text("How can we help?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Get support, check your replies, or help us improve your games.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (inbox || broken || request) {
            Text("GET IN TOUCH", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
        } else {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text("Help options are currently unavailable. Please check back later.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (inbox) HelpOption(Icons.Filled.HeadsetMic, "Help & replies", "Contact the team and see replies to your messages.") {
            CommunityInboxAction(Modifier.fillMaxWidth())
        }
        if (broken) HelpOption(Icons.Filled.BrokenImage, "Broken download?", "Let the team know which game link needs fixing.") {
            FeedbackAction(kind = "report_broken_link", modifier = Modifier.fillMaxWidth())
        }
        if (request) HelpOption(Icons.Filled.SportsEsports, "Looking for a game?", "Suggest a game you’d like to see added.") {
            FeedbackAction(kind = "request_game", modifier = Modifier.fillMaxWidth())
        }
        if (inbox) InboxRecovery()
        if (inbox) Text("For your safety, never share passwords or sensitive information in a message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp))
    }
}

@Composable
private fun HelpOption(icon: ImageVector, title: String, description: String, action: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            action()
        }
    }
}

@Composable
private fun InboxRecovery() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) { runCatching {
                val data = CommunityApi.recoveryBackup(ctx)
                (ctx.contentResolver.openOutputStream(uri) ?: error("Could not open file.")).use { it.write(data.toByteArray(Charsets.UTF_8)) }
                "Backup saved. Keep it private: anyone with this file can access your help messages."
            }.getOrElse { it.message ?: "Backup failed." } }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) { runCatching {
                val bytes = (ctx.contentResolver.openInputStream(uri) ?: error("Could not open file.")).use { java.io.ByteArrayOutputStream().also { out -> val b = ByteArray(1024); while (true) { val n = it.read(b); if (n < 0) break; require(out.size() + n <= 4096) { "Invalid recovery file." }; out.write(b, 0, n) } }.toByteArray() }
                require(bytes.size <= 4096) { "Invalid recovery file." }
                CommunityApi.restoreRecovery(ctx, bytes.toString(Charsets.UTF_8))
                "Inbox restored. Open Help & replies to see your messages."
            }.getOrElse { it.message ?: "Restore failed." } }
        }
    }
    HelpOption(Icons.Filled.HeadsetMic, "Back up your inbox", "Keep a private recovery file to restore help and replies after reinstalling or changing devices.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { prompt = "backup" }, modifier = Modifier.weight(1f)) { Text("Back up") }
            OutlinedButton(onClick = { prompt = "restore" }, modifier = Modifier.weight(1f)) { Text("Restore") }
        }
    }
    if (prompt != null) AlertDialog(onDismissRequest = { prompt = null }, title = { Text(if (prompt == "backup") "Protect your backup" else "Switch inbox?") },
        text = { Text(if (prompt == "backup") "Anyone with the recovery file can read and send help messages. Store it privately; it is not encrypted." else "Restoring replaces this device's inbox identity. Back up the current inbox first or its messages may become inaccessible. The backup must be for this site.") },
        confirmButton = { TextButton(onClick = { val which = prompt; prompt = null; if (which == "backup") export.launch("zgames-help-backup.json") else restore.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } })
    if (message != null) AlertDialog(onDismissRequest = { message = null }, title = { Text("Inbox recovery") }, text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
}
