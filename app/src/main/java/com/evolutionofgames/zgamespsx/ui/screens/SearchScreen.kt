package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Search: live (debounced) results as you type, clear button, keyboard
 * Search action, restores the query after rotation, pages in more results.
 */
@Composable
fun SearchScreen(
    viewModel: LibraryViewModel,
    imageLoader: ImageLoader,
    onGameClick: (Game) -> Unit,
    onNav: (NavDest) -> Unit
) {
    val results by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val adFree by viewModel.billing.isAdFree.collectAsState()
    val installed by viewModel.library.games.collectAsState()
    val mapVersion by viewModel.installedByGameVersion.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val installedIds = remember(results, installed, mapVersion) {
        results.filter { viewModel.installedUriFor(it) != null }.map { it.id }.toSet()
    }
    val adCfg by AdConfig.snapshot.collectAsState()
    val consentedForFeed by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    val nativeOn = adCfg.adsEnabled && adCfg.feedSearchEnabled && consentedForFeed && (adCfg.nativeFeedEnabled || adCfg.feedBannerEnabled)
    val gridItems = remember(results, adFree, nativeOn, adCfg.nativeFeedEveryN, adCfg.feedBannerEnabled) {
        buildGridItems(results, adFree, nativeOn, adCfg.nativeFeedEveryN, adCfg.feedBannerEnabled)
    }
    val gridState = rememberLazyGridState()

    // Only grab focus on a fresh, empty search so returning from a result
    // does not pop the keyboard over the list.
    LaunchedEffect(Unit) {
        if (query.isBlank() && results.isEmpty()) focusRequester.requestFocus()
    }
    // Debounced live search; a blank query clears the results.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            viewModel.search("")
        } else {
            delay(450)
            viewModel.search(q)
        }
    }
    LaunchedEffect(gridState, gridItems.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .map { it ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= gridItems.size - 6 && query.isNotBlank()) {
                    viewModel.search(query.trim(), reset = false)
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("Search PS1 games", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                viewModel.search(query.trim())
                keyboard?.hide()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focusRequester)
        )
        when {
            searching && results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            results.isEmpty() && query.isNotBlank() && !searching -> {
                CenteredHint("No games found for “${query.trim()}”.\nTry a shorter or different name.")
            }
            results.isNotEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = tileMinWidth()),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(
                        items = gridItems,
                        imageLoader = imageLoader,
                        loadNativeAd = { cb -> viewModel.ads.loadNativeAd(cb) },
                        onGameClick = onGameClick,
                        installedIds = installedIds,
                        favoriteIds = favoriteIds,
                        onToggleFavorite = { viewModel.toggleFavorite(it) }
                    )
                    if (searching) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            else -> CenteredHint("Type a game name to search the library.")
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}
