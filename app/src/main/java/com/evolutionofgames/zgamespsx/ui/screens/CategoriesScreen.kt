package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.remote.WpConfig
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel

/** Categories cover loaded site posts and eligible app posts, not the entire remote catalogue. */
@Composable
fun CategoriesScreen(viewModel: LibraryViewModel, imageLoader: ImageLoader, onGameClick: (Game) -> Unit) {
    val remote by WpConfig.content.collectAsState()
    val games by viewModel.games.collectAsState()
    val loading by viewModel.loadingFeed.collectAsState()
    val installed by viewModel.library.games.collectAsState()
    val mapVersion by viewModel.installedByGameVersion.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    var selected by rememberSaveable { mutableStateOf("") }
    if (!remote.flag("categories_nav", false)) {
        Text("Categories are currently unavailable.", Modifier.padding(24.dp))
        return
    }
    val posts = remember(remote) { remote.categoryPosts.filter { post -> remote.pages.any { it.id == post.page } } }
    val categories = remember(games, posts) { (games.flatMap { it.categorySlugs.orEmpty() } + posts.flatMap { it.categories }).distinct().sorted() }
    val matchingGames = remember(games, selected) { games.filter { selected in it.categorySlugs.orEmpty() } }
    val matchingPosts = remember(posts, selected) { posts.filter { selected in it.categories } }
    val installedIds = remember(games, installed, mapVersion) { games.filter { viewModel.installedUriFor(it) != null }.map { it.id }.toSet() }
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val entries = remember(matchingGames, matchingPosts) { buildGridItems(matchingGames, true, false, appPosts = matchingPosts) }
    LazyVerticalGrid(columns = GridCells.Adaptive(tileMinWidth()), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Categories", style = MaterialTheme.typography.headlineSmall)
                Text("Browse categories from loaded games and app posts. Load more to discover additional website games.", style = MaterialTheme.typography.bodySmall)
                if (selected.isNotEmpty()) TextButton(onClick = { selected = "" }) { Text("‹ All categories") }
            }
        }
        if (selected.isEmpty()) {
            categories.forEach { slug ->
                item(key = "cat-$slug", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = { selected = slug }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
                        Text(slug.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() })
                    }
                }
            }
            if (categories.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No categories in the loaded posts yet.", Modifier.padding(12.dp)) }
        } else {
            if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No loaded posts in this category.", Modifier.padding(12.dp)) }
            gridItems(entries, imageLoader, { cb -> viewModel.ads.loadNativeAd(cb) }, onGameClick, installedIds, favoriteIds, { viewModel.toggleFavorite(it) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = { viewModel.loadFeedPage() }, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(6.dp)) {
                Text(if (loading) "Loading…" else "Load more website games")
            }
        }
    }
}
