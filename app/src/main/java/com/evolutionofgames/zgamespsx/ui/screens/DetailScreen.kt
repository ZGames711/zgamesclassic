package com.evolutionofgames.zgamespsx.ui.screens

import android.os.Bundle
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Brush
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.ads.AdsEngine
import com.evolutionofgames.zgamespsx.feed.DownloadLink
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import com.evolutionofgames.zgamespsx.install.InstallState
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.model.SourceSite
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Detail: full-bleed cover hero with a floating back button, title and
 * chips, a big Download button + Play, install progress, info cards and
 * every download link as its own row. No emulator names in UI text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    game: Game,
    viewModel: LibraryViewModel,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onDownloadClick: () -> Unit = {},
    onRelatedGame: (Game) -> Unit = {}
) {
    var showDownloadOptions by remember(game.id) { mutableStateOf(false) }
    val links by viewModel.detailLinks.collectAsState()
    val slideshow by viewModel.detailSlideshow.collectAsState()
    val catalogue by viewModel.games.collectAsState()
    val description by viewModel.detailDescription.collectAsState()
    val tags by viewModel.detailTags.collectAsState()
    val remoteDesign by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    val loadingLinks by viewModel.loadingLinks.collectAsState()
    val linksError by viewModel.linksError.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val isFavorite = favorites.any { it.id == game.id }
    val installState by viewModel.installer.state.collectAsState()
    val installed by viewModel.library.games.collectAsState()
    val mapVersion by viewModel.installedByGameVersion.collectAsState()
    val adFree by viewModel.billing.isAdFree.collectAsState()
    val consented by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    val context = LocalContext.current
    // True while the pre-download rewarded ad is loading/showing.
    var gating by remember { mutableStateOf(false) }

    /** Declining notification access never blocks the download. */
    // Link waiting behind the "Unlock download" rewarded-ad dialog (plugin: Session & ads).
    var unlockLink by remember(game.id) { mutableStateOf<DownloadLink?>(null) }

    fun startDownloadNow(link: DownloadLink) {
        onDownloadClick()
        viewModel.downloadGame(game, link)
    }

    fun beginDownload(link: DownloadLink) {
        val session = com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.session
        val needsUnlock = session.unlockOnDownload && !adFree &&
            viewModel.ads.rewardedEnabled() && findActivity(context) != null
        if (needsUnlock) unlockLink = link else startDownloadNow(link)
    }

    val installedUri = remember(installed, mapVersion) { viewModel.installedUriFor(game) }
    val isInstalled = installedUri != null
    val installing = installState is InstallState.Working ||
        installState is InstallState.NeedPassword ||
        installState is InstallState.NeedGameId

    LaunchedEffect(game.id) {
        viewModel.loadDetailLinks(game)
    }
    val title = TitleSanitizer.displayTitle(game.title)


    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // Hero cover with scrim and floating back button.
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (game.coverUrl != null) {
                        AsyncImage(
                            model = game.coverUrl,
                            contentDescription = title,
                            imageLoader = imageLoader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.SportsEsports,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(56.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .align(Alignment.BottomCenter)
                            .background(PsxColors.ScrimBrush)
                    )
                }
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 8.dp, top = 4.dp)
                        .size(42.dp)
                        .background(Color(0xCC10141F), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 8.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.toggleFavorite(game) },
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xCC10141F), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = if (isFavorite) "Remove from Favorites" else "Save to Favorites",
                            tint = if (isFavorite) Color(0xFFFF6B8B) else Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            try {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "$title — https://${game.sourceSite.host}/?p=${game.postId}"
                                    )
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Share game"))
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xCC10141F), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.Rounded.IosShare, contentDescription = "Share", tint = Color.White)
                    }
                }
            }

            slideshow?.takeIf { it.enabled && it.images.isNotEmpty() }?.let { slide ->
                val block = com.evolutionofgames.zgamespsx.remote.RBlock(
                    id = "site-slides", type = "slideshow", slot = "page", title = "", text = "", image = null,
                    accent = null, dismissible = false, action = null,
                    items = slide.images.map { com.evolutionofgames.zgamespsx.remote.RItem("", "", it, null) },
                    heightDp = (if (remoteDesign.siteSlidesHeight > 0) remoteDesign.siteSlidesHeight else slide.heightDp).coerceIn(120, 480),
                    intervalSec = if (slide.intervalSec == 0) 0 else slide.intervalSec.coerceIn(3, 30),
                    imageFit = if (slide.imageFit == "crop") "crop" else "fit"
                )
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { RemoteBlockView(block, imageLoader) }
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp
            )

            FlowRow(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip(
                    text = if (isInstalled) "Installed" else "Not installed",
                    highlighted = isInstalled,
                    highlightColor = PsxColors.Success
                )
            }

            // Small banner, always, right before Play / Download.
            PostAdSlot(viewModel, large = false, force = true)

            // Primary actions.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isInstalled) {
                    // Slow "breathing" pulse so Play is the obvious next step.
                    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "playPulse")
                    val pulseScale by pulse.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.025f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "playPulseScale"
                    )
                    Button(
                        onClick = onPlay,
                        colors = ButtonDefaults.buttonColors(containerColor = PsxColors.Success),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF06281D))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play", color = Color(0xFF06281D), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                RatingAction(game = game.id, modifier = Modifier.fillMaxWidth())
                val canDownload = !installing && !loadingLinks && !gating && links.isNotEmpty()
                val downloadModifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.84f)
                    .height(46.dp)
                val downloadLabel = when {
                    gating -> "Loading ad…"
                    loadingLinks -> "Finding download link…"
                    linksError -> "Couldn't reach the server"
                    links.isEmpty() -> "No download link found"
                    installing -> "Installing…"
                    isInstalled -> "Download again"
                    else -> "Download"
                }
                if (isInstalled) {
                    OutlinedButton(
                        onClick = { onDownloadClick(); if (links.size > 1) showDownloadOptions = true else links.firstOrNull()?.let { beginDownload(it) } },
                        enabled = canDownload,
                        modifier = downloadModifier
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(downloadLabel, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { onDownloadClick(); if (links.size > 1) showDownloadOptions = true else links.firstOrNull()?.let { beginDownload(it) } },
                        enabled = canDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = downloadModifier
                    ) {
                        if (loadingLinks || gating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = androidx.compose.material3.LocalContentColor.current
                            )
                        } else {
                            Icon(Icons.Filled.Download, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(downloadLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (!loadingLinks && links.isEmpty()) {
                    if (linksError) {
                        Text(
                            "Check your internet connection, then try again.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { viewModel.loadDetailLinks(game) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (linksError) "Retry" else "Check again")
                    }
                }
            }

            val working = installState as? InstallState.Working
            if (working != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${working.stage} ${working.currentFile}".trim(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PostDetailsContent(description, tags, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))

            // Heads-up for weaker phones. Applies to every game: the app picks
            // the right engine itself, so nothing here names a console.
            if (remember { viewModel.isLowRamForPs2() }) {
                Notice(
                    icon = Icons.Filled.Warning,
                    text = "This phone has limited memory. Heavier games may run slowly or close unexpectedly — 6 GB of RAM or more is recommended.",
                    tint = PsxColors.Gold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
                )
            }

            // Large banner, always, right after the description.
            PostAdSlot(viewModel, large = true, force = true)

            // Every download link, when there is more than one.
            if (links.size > 1) {
                Text(
                    text = "Download links",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                for (link in links) {
                    OutlinedButton(
                        onClick = { beginDownload(link) },
                        enabled = !installing && !gating,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${link.label} · ${link.host}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            val related = remember(game.id, game.categorySlugs, catalogue) {
                val categories = game.categorySlugs.orEmpty().toSet()
                if (categories.isEmpty()) emptyList() else catalogue.filter { candidate ->
                    candidate.id != game.id && candidate.sourceSite == game.sourceSite && candidate.categorySlugs.orEmpty().any { it in categories }
                }.take(8)
            }
            if (remoteDesign.flag("related_games", false) && related.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp)) {
                    Text("Related games", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(related.size) { i ->
                            val candidate = related[i]
                            Box(Modifier.width(160.dp)) {
                                GameCard(candidate, imageLoader, false, onClick = { onRelatedGame(candidate) })
                            }
                        }
                    }
                }
            }
            PostCommunity(game = game.id, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            Spacer(modifier = Modifier.height(24.dp))
        }
        PostAnchoredBanner(viewModel)
    }
    unlockLink?.let { pending ->
        val act = findActivity(context)
        val cfg = com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.session
        if (act == null) { unlockLink = null; startDownloadNow(pending) } else {
            DownloadUnlockDialog(
                ads = viewModel.ads,
                activity = act,
                timeoutSec = cfg.unlockTimeoutSec,
                grantWhenNoAd = cfg.grantWhenNoAd,
                onUnlocked = { unlockLink = null; startDownloadNow(pending) },
                onCancel = { unlockLink = null }
            )
        }
    }

    if (showDownloadOptions) {
        AlertDialog(
            onDismissRequest = { showDownloadOptions = false },
            title = { Text("Choose a download link") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a source. A different link starts a separate download; partial files are not combined.", style = MaterialTheme.typography.bodySmall)
                    links.forEach { link ->
                        OutlinedButton(onClick = { showDownloadOptions = false; beginDownload(link) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${link.label} · ${link.host}", maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDownloadOptions = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Chip(
    text: String,
    highlighted: Boolean = false,
    highlightColor: Color = PsxColors.Orange
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (highlighted) highlightColor.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) highlightColor else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InfoBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 300x250 medium-rectangle ad below the detail info cards. A failed
 * load shows a labeled generic placeholder (test build) rather than a
 * hole, so the slot is always visible when ads are on.
 */
@Composable
private fun MediumRectangleAd() {
    val failed = remember { mutableStateOf(false) }
    val loaded = remember { mutableStateOf(false) }
    val adView = remember { arrayOfNulls<AdView>(1) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { adView[0]?.destroy(); adView[0] = null }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (loaded.value && !failed.value) 16.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (failed.value) {
            // No ad filled: draw nothing (never a fake ad slot).
        } else {
            AndroidView(
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdSize(AdSize.MEDIUM_RECTANGLE)
                        adUnitId = AdsEngine.BANNER_UNIT
                        adView[0] = this
                        adListener = object : AdListener() {
                    override fun onAdImpression() { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.impression("Banner") }
                            override fun onAdLoaded() { loaded.value = true; com.evolutionofgames.zgamespsx.ads.AdDiagnostics.loaded("Detail rectangle") }
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                com.evolutionofgames.zgamespsx.ads.AdDiagnostics.failed("Detail rectangle", error.code)
                                failed.value = true
                            }
                        }
                        loadAd(AdRequest.Builder().build())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(if (loaded.value) 250.dp else 0.dp)
            )
        }
    }
}

/** Unwraps a Compose/themed Context down to its hosting Activity (null if none). */
private fun findActivity(context: android.content.Context): android.app.Activity? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun Notice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = PsxColors.Yellow
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** ISO date from the catalogue -> "Oct 30, 2026" (falls back to the raw date part). */
private fun formatPublished(iso: String): String = try {
    java.time.LocalDate.parse(iso.take(10))
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
} catch (_: Exception) {
    iso.take(10).ifBlank { "—" }
}
