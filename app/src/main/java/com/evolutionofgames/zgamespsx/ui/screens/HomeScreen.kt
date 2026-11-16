package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.evolutionofgames.zgamespsx.install.InstalledGame
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Home: one unified game grid, pull-to-refresh, skeleton loading, a real
 * error/empty state and an inline "load more" footer. Chrome (top bar +
 * bottom nav) is hosted by MainActivity, not here.
 */
@Composable
fun HomeScreen(
    viewModel: LibraryViewModel,
    imageLoader: ImageLoader,
    onGameClick: (Game) -> Unit,
    onNav: (NavDest) -> Unit,
    onPlayInstalled: (InstalledGame) -> Unit
) {
    val games by viewModel.games.collectAsState()
    val loading by viewModel.loadingFeed.collectAsState()
    val feedError by viewModel.feedError.collectAsState()
    val installed by viewModel.library.games.collectAsState()
    val adFree by viewModel.billing.isAdFree.collectAsState()
    val mapVersion by viewModel.installedByGameVersion.collectAsState()

    val feedOffline by viewModel.feedOffline.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val lastPlayed by viewModel.lastPlayed.collectAsState()

    // One unified list. Which core runs a game is decided automatically
    // (disc probe) when it is played; the Home screen never mentions it.
    var sortAz by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("") }
    val remoteContent by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    val categoriesOn = remoteContent.flag("home_categories", false)
    val categories = remember(games, remoteContent) { (games.flatMap { it.categorySlugs.orEmpty() } + remoteContent.appPosts.flatMap { it.categories }).distinct().sorted() }
    val visibleGames = remember(games, sortAz, category, categoriesOn) {
        val filtered = if (categoriesOn && category.isNotEmpty()) games.filter { category in it.categorySlugs.orEmpty() } else games
        if (sortAz) filtered.sortedBy { it.title.lowercase() } else filtered
    }
    val recent = remember(installed, lastPlayed) {
        installed.filter { lastPlayed.containsKey(it.key) }
            .sortedByDescending { lastPlayed[it.key] ?: 0L }
            .take(8)
    }

    val installedIds = remember(games, installed, mapVersion) {
        games.filter { viewModel.installedUriFor(it) != null }.map { it.id }.toSet()
    }
    val adCfg by AdConfig.snapshot.collectAsState()
    val consentedForFeed by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    val nativeOn = adCfg.adsEnabled && adCfg.feedHomeEnabled && consentedForFeed && (adCfg.nativeFeedEnabled || adCfg.feedBannerEnabled)
    val feedPosts = remember(remoteContent, category, categoriesOn) {
        remoteContent.appPosts.filter { post -> remoteContent.pages.any { it.id == post.page } && (!categoriesOn || category.isEmpty() || category in post.categories) }
    }
    val gridItems = remember(visibleGames, feedPosts, sortAz, adFree, nativeOn, adCfg.nativeFeedEveryN, adCfg.feedBannerEnabled) {
        buildGridItems(visibleGames, adFree, nativeOn, adCfg.nativeFeedEveryN, adCfg.feedBannerEnabled, feedPosts, sortAz)
    }

    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(loading) { if (!loading) refreshing = false }

    val gridState = rememberLazyGridState()
    // Restarts when the list size changes so the next page is requested
    // as the user nears the end of the list.
    LaunchedEffect(gridState, gridItems.size, feedError) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .map { it ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= gridItems.size - 6 && feedError == null) {
                    viewModel.loadFeedPage()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
        val homePageBlocks = remember(remoteContent) {
            if (remoteContent.homeMode == "page") remoteContent.pages.firstOrNull { it.id == remoteContent.homePage && it.kind == "blocks" }?.blocks else null
        }
        when {
            // The plugin replaced Home with one of its pages.
            homePageBlocks != null -> {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                ) {
                    items(homePageBlocks.size) { i ->
                        com.evolutionofgames.zgamespsx.ui.screens.RemoteBlockView(homePageBlocks[i], imageLoader)
                    }
                }
            }
            feedError != null && games.isEmpty() && feedPosts.isEmpty() -> {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(horizontal = 10.dp)) {
                        com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_hero", imageLoader)
                        com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_top", imageLoader)
                    }
                    Box(Modifier.weight(1f)) {
                        StateMessage(
                            title = "Can't reach the game library",
                            body = feedError ?: "Check your connection and try again.",
                            actionLabel = "Try again",
                            onAction = { viewModel.retryFeed() }
                        )
                    }
                }
            }
            !loading && games.isEmpty() && feedPosts.isEmpty() -> {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(horizontal = 10.dp)) {
                        com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_hero", imageLoader)
                        com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_top", imageLoader)
                    }
                    Box(Modifier.weight(1f)) {
                        StateMessage(
                            title = "No games yet",
                            body = "The library came back empty. Pull down or tap below to refresh.",
                            actionLabel = "Refresh",
                            onAction = { viewModel.retryFeed() }
                        )
                    }
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        viewModel.retryFeed()
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = tileMinWidth()),
                        state = gridState,
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (feedOffline) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                OfflineBanner(onRetry = { viewModel.retryFeed() })
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_hero", imageLoader)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_top", imageLoader)
                        }
                        if (recent.isNotEmpty() && remoteContent.flag("home_continue_playing", true)) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ContinuePlayingRow(
                                    games = recent,
                                    coverFor = { viewModel.catalogCoverFor(it) },
                                    imageLoader = imageLoader,
                                    onPlay = onPlayInstalled
                                )
                            }
                        }
                        if (remoteContent.flag("home_filter_bar", true)) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                FilterBar(
                                    sortAz = sortAz,
                                    onToggleSort = { sortAz = !sortAz }
                                )
                            }
                        }
                        if (categoriesOn && categories.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = category.isEmpty(), onClick = { category = "" }, label = { Text("All") })
                                    categories.forEach { slug ->
                                        FilterChip(selected = category == slug, onClick = { category = slug }, label = {
                                            Text(slug.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() })
                                        })
                                    }
                                }
                            }
                        }
                        if (games.isEmpty() && feedPosts.isEmpty() && loading) {
                            items(6) { SkeletonCard() }
                        } else if (visibleGames.isEmpty() && feedPosts.isEmpty() && !loading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (categoriesOn && category.isNotEmpty()) "No matching games in the loaded pages." else "No games loaded yet.\nPull down to refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                                        if (categoriesOn && category.isNotEmpty()) OutlinedButton(onClick = { viewModel.loadFeedPage() }) { Text("Load more games") }
                                    }
                                }
                            }
                        } else {
                            gridItems(
                                items = gridItems,
                                imageLoader = imageLoader,
                                loadNativeAd = { cb -> viewModel.ads.loadNativeAd(cb) },
                                onGameClick = onGameClick,
                                installedIds = installedIds,
                                favoriteIds = favoriteIds,
                                onToggleFavorite = { viewModel.toggleFavorite(it) }
                            )
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        feedError != null -> OutlinedButton(
                                            onClick = { viewModel.loadFeedPage() }
                                        ) { Text("Couldn't load more — retry") }
                                        loading -> CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 3.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        else -> Spacer(modifier = Modifier.height(1.dp))
                                    }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                com.evolutionofgames.zgamespsx.ui.screens.RemoteBlocks("home_bottom", imageLoader)
                            }
                        }
                    }
                }
            }
        }
        }
        BannerSlot(AdConfig.Placement.HOME, viewModel)
    }
}

/** Animated sweep used by the loading placeholders. */
@Composable
private fun shimmerBrush(): androidx.compose.ui.graphics.Brush {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "shimmerX"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    return androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(base, base.copy(alpha = 0.55f).compositeOver(Color(0xFF3D7BFF).copy(alpha = 0.25f)), base),
        start = androidx.compose.ui.geometry.Offset(x, 0f),
        end = androidx.compose.ui.geometry.Offset(x + 400f, 200f)
    )
}

@Composable
private fun SkeletonCard() {
    val shimmer = shimmerBrush()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(shimmer)
        )
        Box(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer)
        )
        Box(
            modifier = Modifier
                .padding(start = 12.dp, bottom = 16.dp)
                .fillMaxWidth(0.4f)
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer)
        )
    }
}

/** Centered icon + title + body + one action. Reused for error / empty states. */
@Composable
fun StateMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
