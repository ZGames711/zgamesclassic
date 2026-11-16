package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import com.evolutionofgames.zgamespsx.emulator.DiscProbe
import com.evolutionofgames.zgamespsx.install.InstalledGame
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel

/**
 * My Games: every installed game in one list — recently played first,
 * then newest. Shows how much space the games use, a PSP/PS2 badge,
 * when each was last played, and lets the user delete a game (with a
 * confirmation) to free space. The right core is picked automatically
 * from the game file itself; the user is never asked.
 */
@Composable
fun GamesScreen(
    viewModel: LibraryViewModel,
    imageLoader: ImageLoader,
    onNav: (NavDest) -> Unit,
    onPlay: (InstalledGame) -> Unit
) {
    val installed by viewModel.library.games.collectAsState()
    val scanning by viewModel.library.scanning.collectAsState()
    val lastPlayed by viewModel.lastPlayed.collectAsState()
    val playTimes by com.evolutionofgames.zgamespsx.emulator.PlaytimeStats.totals.collectAsState()
    val remoteConfig by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    var pendingDelete by remember { mutableStateOf<InstalledGame?>(null) }
    var mostPlayed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var settingsNotice by remember { mutableStateOf<String?>(null) }
    var showPs2Help by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("ALL") }

    val ordered = remember(installed, lastPlayed, playTimes, mostPlayed, remoteConfig) {
        if (mostPlayed && remoteConfig.flag("playtime_stats", false)) {
            installed.sortedWith(compareByDescending<InstalledGame> { playTimes[it.key] ?: 0L }.thenByDescending { lastPlayed[it.key] ?: 0L })
        } else {
            installed.sortedWith(compareByDescending<InstalledGame> { lastPlayed[it.key] ?: 0L }.thenByDescending { it.addedAtMs })
        }
    }
    val totalBytes = remember(installed) { installed.sumOf { it.sizeBytes.coerceAtLeast(0L) } }

    val canSort = installed.isNotEmpty() && remoteConfig.flag("playtime_stats", false)
    val shown = remember(ordered, filter, viewModel.consoleCache.size) {
        when (filter) {
            "PSP" -> ordered.filter { viewModel.consoleCache[it.key] == DiscProbe.Console.PSP }
            "PS2" -> ordered.filter { viewModel.consoleCache[it.key] == DiscProbe.Console.PS2 }
            else -> ordered
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "My Games", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                if (installed.isNotEmpty()) {
                    Text(
                        text = "${installed.size} installed · ${formatGameSize(totalBytes)} used",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                IconButton(onClick = { viewModel.library.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh installed games")
                }
            }
            // One tidy menu instead of a row of buttons above the list.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Emulator settings and sorting")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Emulator settings") },
                        leadingIcon = { Icon(Icons.Filled.Settings, null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; showPs2Help = true }
                    )
                    if (canSort) {
                        DropdownMenuItem(
                            text = { Text(if (mostPlayed) "Sort by recent" else "Sort by most played") },
                            onClick = { menuOpen = false; mostPlayed = !mostPlayed }
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (installed.isEmpty()) {
                StateMessage(
                    title = "No games installed yet",
                    body = "Download a game and it will appear here, ready to play.",
                    actionLabel = "Browse games",
                    onAction = { onNav(NavDest.HOME) }
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 20.dp)
                ) {
                    gridItems(shown, key = { it.key }) { game ->
                        InstalledCard(
                            game = game,
                            coverUrl = viewModel.catalogCoverFor(game),
                            imageLoader = imageLoader,
                            tileRadius = remoteConfig.tileRadius,
                            onPlay = { onPlay(game) },
                            onDelete = { pendingDelete = game }
                        )
                    }
                }
            }
        }
        BannerSlot(AdConfig.Placement.GAMES, viewModel)
    }

    if (showPs2Help) AlertDialog(onDismissRequest = { showPs2Help = false }, title = { Text("Emulator settings") }, text = { Text("Opens RetroArch, where you change graphics, controls, BIOS and memory cards for your PS1 games.") },
        confirmButton = { TextButton(onClick = {
            showPs2Help = false
            try {
                val pkg = com.evolutionofgames.zgamespsx.emulator.Ps1Launcher.installedRetroArch(context)
                    ?: throw IllegalStateException("No PS1 emulator is installed yet. Get RetroArch first.")
                context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("Could not open the emulator."))
            } catch (e: Exception) { settingsNotice = e.message ?: "Could not open the emulator." }
        }) { Text("Open emulator") } }, dismissButton = { TextButton(onClick = { showPs2Help = false }) { Text("Cancel") } })
    if (settingsNotice != null) AlertDialog(onDismissRequest = { settingsNotice = null }, title = { Text("Emulator settings") }, text = { Text(settingsNotice.orEmpty()) }, confirmButton = { TextButton(onClick = { settingsNotice = null }) { Text("OK") } })
    pendingDelete?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this game?") },
            text = {
                Text(
                    "“${TitleSanitizer.displayTitle(game.title)}” (${formatGameSize(game.sizeBytes)}) " +
                            "will be removed from your phone. You can download it again later. " +
                            "Your save files are not touched."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteInstalledGame(game)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GameRow(
    game: InstalledGame,
    coverUrl: String?,
    console: DiscProbe.Console?,
    lastPlayedMs: Long?,
    playtimeMs: Long?,
    imageLoader: ImageLoader,
    tileRadius: Int,
    visualEffect: String,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val title = TitleSanitizer.displayTitle(game.title)
    val shape = RoundedCornerShape(tileRadius.coerceIn(8, 28).dp)
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .shadow(if (visualEffect == "shadow") 7.dp else 0.dp, shape),
        shape = shape,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.22f)),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (visualEffect == "glass") Brush.linearGradient(
                        listOf(colors.surfaceVariant.copy(alpha = 0.55f), colors.surface.copy(alpha = 0.2f))
                    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(118.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // The placeholder stays behind the image, including when image loading fails.
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(30.dp)
                )
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f).heightIn(min = 118.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (console != null) {
                            Text(
                                text = console.name,
                                modifier = Modifier
                                    .background(colors.primary.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                color = colors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = formatGameSize(game.sizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    val sub = buildList {
                        lastPlayedMs?.let { add("Played ${relativeTime(it)}") }
                        playtimeMs?.let { add("${it / 60_000} min total") }
                    }.joinToString("  ·  ")
                    if (sub.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.weight(1f).height(42.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play", maxLines = 1, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete $title",
                            tint = colors.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

internal fun relativeTime(ms: Long): String {
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
    val min = diff / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        min < 60 * 24 * 30 -> "${min / (60 * 24)} d ago"
        else -> "a while ago"
    }
}

internal fun formatGameSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
/** Vertical "game case" tile for an installed game: tap = play, trash = delete. */
@Composable
private fun InstalledCard(
    game: InstalledGame,
    coverUrl: String?,
    imageLoader: ImageLoader,
    tileRadius: Int,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val title = TitleSanitizer.displayTitle(game.title)
    val shape = RoundedCornerShape(tileRadius.coerceIn(10, 24).dp)
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 8.dp)
            .clickable(onClickLabel = "Play $title") { onPlay() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(10.dp, shape, ambientColor = colors.primary, spotColor = colors.primary)
                .clip(shape)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.primary.copy(alpha = 0.35f), shape)
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SportsEsports, null, tint = colors.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(34.dp))
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xF2000000))))
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = colors.onPrimary, modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Box(Modifier.size(26.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete game", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            modifier = Modifier.padding(horizontal = 3.dp),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Bold,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
