package com.evolutionofgames.zgamespsx.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolutionofgames.zgamespsx.download.DownloadItem
import com.evolutionofgames.zgamespsx.download.DownloadStatus
import com.evolutionofgames.zgamespsx.install.InstallState
import com.evolutionofgames.zgamespsx.install.InstalledGame
import androidx.compose.foundation.layout.Box
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel

/**
 * Downloads screen: downloading with progress + Cancel, downloaded
 * games with Play, same dark + blue system. Every completed download
 * auto-installs (like ZGamez) — there is no manual Install step here.
 * Rows show live downloaded/total size + current speed (item 5), and
 * completed rows offer Play when the game installed (item 1).
 *
 * 50% rewarded gate (ZGamez's halfway dialog, replicated): the first
 * RUNNING download reaching 50% pauses and shows "Halfway there!" —
 * Watch Ad (rewarded; resumes on rewarded, dismissed, or unavailable
 * — fail-open, exactly like ZGamez) or Go Premium (Settings). Once
 * per download, never for ad-free users, never dismissible without
 * choosing (Watch Ad always unblocks).
 */
@Composable
fun DownloadsScreen(
    viewModel: LibraryViewModel,
    onNav: (NavDest) -> Unit,
    activity: Activity,
    onPlay: (InstalledGame) -> Unit,
    onGoPremium: () -> Unit
) {
    val items by viewModel.downloads.items.collectAsState()
    val installState by viewModel.installer.state.collectAsState()
    val isAdFree by viewModel.billing.isAdFree.collectAsState()
    val installedGames by viewModel.library.games.collectAsState()
    val halfwayDialogId by viewModel.halfwayDialogId.collectAsState()
    val adCfg by AdConfig.snapshot.collectAsState()

    // The 50% pause + rewarded-ad dialog is global now (LibraryViewModel +
    // MainActivity.GlobalDialogs) so it also works away from this screen.

    Column(modifier = Modifier.fillMaxSize()) {
        val running = items.filter { it.status == DownloadStatus.RUNNING }
        val finished = items.filter { it.status == DownloadStatus.COMPLETED }
        val totalSpeed = running.sumOf { it.speedBytesPerSec }
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Downloads", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                if (running.isNotEmpty()) {
                    Text(
                        text = "${running.size} downloading" +
                                (if (totalSpeed > 0) " • ${formatSpeed(totalSpeed)}" else ""),
                        fontSize = 12.sp,
                        color = PsxColors.Yellow
                    )
                }
            }
            if (finished.isNotEmpty()) {
                TextButton(onClick = { finished.forEach { viewModel.downloads.removeDownload(it.id) } }) {
                    Text("Clear finished", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val remote by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
        if (remote.flag("download_foreground_only", true)) {
            Text("Keep the app open while downloading. Downloads pause in the background and resume when you return.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        if (installState is InstallState.Working) {
            val w = installState as InstallState.Working
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, PsxColors.Orange.copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Installing ${TitleSanitizer.displayTitle(w.title)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (w.totalBytes > 0) (w.doneBytes.toFloat() / w.totalBytes).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${w.currentFile} • ${w.doneFiles}/${w.totalFiles} files",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (items.isEmpty()) {
                StateMessage(
                    title = "No downloads yet",
                    body = "Open a game and tap Download. Progress, speed and Play buttons will show up here.",
                    actionLabel = "Browse games",
                    onAction = { onNav(NavDest.HOME) }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        // installedGames is collected above, so a
                        // just-finished auto-install gains its Play button
                        // as soon as the library refresh lands.
                        val installed = if (item.status == DownloadStatus.COMPLETED) {
                            viewModel.installedGameForDownload(item, installedGames)
                        } else null
                        DownloadRow(
                            item = item,
                            installed = installed,
                            onPause = { viewModel.downloads.pauseDownload(item.id) },
                            onResume = { viewModel.resumeDownload(item) },
                            onCancel = { viewModel.downloads.cancelDownload(item.id) },
                            onPlay = { installed?.let(onPlay) },
                            // Recovery: a completed row with no installed
                            // game MUST offer Install — a stuck row must
                            // never again be a dead end with only Remove.
                            onInstall = { viewModel.installDownload(item) }
                        )
                    }
                }
            }
        }
        BannerSlot(AdConfig.Placement.DOWNLOADS, viewModel)
    }
}

/** Human size: 208 MB, 1.4 GB, 512 KB — "—" when unknown. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** Current speed: 2.4 MB/s — "—" when idle/unknown. */
private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "—"
    return "${formatBytes(bytesPerSec)}/s"
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    installed: InstalledGame?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onInstall: () -> Unit
) {
    val statusColor = when (item.status) {
        DownloadStatus.RUNNING -> PsxColors.Orange
        DownloadStatus.PAUSED -> PsxColors.Gold
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadStatus.COMPLETED -> PsxColors.Success
    }
    val statusText = when (item.status) {
        DownloadStatus.RUNNING -> "${item.progressPercent}%"
        DownloadStatus.PAUSED -> "Paused ${item.progressPercent}%"
        DownloadStatus.FAILED ->
            if (item.errorMessage == com.evolutionofgames.zgamespsx.download.DownloadEngine.WIFI_ONLY_MESSAGE) "Waiting for Wi-Fi" else "Failed"
        DownloadStatus.COMPLETED -> if (installed != null) "Installed" else "Downloaded"
    }
    val sizeLine = when (item.status) {
        DownloadStatus.RUNNING ->
            if (item.totalBytes > 0) {
                "${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)} • ${formatSpeed(item.speedBytesPerSec)}"
            } else {
                "${formatBytes(item.bytesDownloaded)} downloaded • ${formatSpeed(item.speedBytesPerSec)}"
            }
        DownloadStatus.PAUSED ->
            if (item.totalBytes > 0) "${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}"
            else formatBytes(item.bytesDownloaded)
        DownloadStatus.COMPLETED -> (if (item.totalBytes > 0) formatBytes(item.totalBytes) else "") + (if (item.verified) (if (item.totalBytes > 0) " • " else "") + "SHA-256 verified" else "")
        DownloadStatus.FAILED -> item.errorMessage ?: "Download failed"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = TitleSanitizer.displayTitle(item.title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            if (sizeLine != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sizeLine,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.status) {
                    DownloadStatus.RUNNING -> {
                        OutlinedButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pause")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Remove")
                        }
                        Button(onClick = onResume, enabled = item.errorMessage?.startsWith("This link needs a browser session.") != true) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resume", color = Color.White)
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Remove")
                        }
                        if (installed != null) {
                            Button(
                                onClick = onPlay,
                                colors = ButtonDefaults.buttonColors(containerColor = PsxColors.Success)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF06281D), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play", color = Color(0xFF06281D), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            // Recovery path: completed but nothing installed
                            // (missed auto-install, restart, dedup).
                            Button(onClick = onInstall) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Install", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}