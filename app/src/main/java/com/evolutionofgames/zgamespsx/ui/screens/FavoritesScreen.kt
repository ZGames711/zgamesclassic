package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel

/**
 * Favorites: the games the user saved with the heart. Stored with a full
 * snapshot, so the list works offline and survives app restarts.
 */
@Composable
fun FavoritesScreen(
    viewModel: LibraryViewModel,
    imageLoader: ImageLoader,
    onGameClick: (Game) -> Unit,
    onNav: (NavDest) -> Unit
) {
    val favorites by viewModel.favorites.collectAsState()
    val installed by viewModel.library.games.collectAsState()
    val mapVersion by viewModel.installedByGameVersion.collectAsState()
    val installedIds = remember(favorites, installed, mapVersion) {
        favorites.filter { viewModel.installedUriFor(it) != null }.map { it.id }.toSet()
    }
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Favorites",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        )
        if (favorites.isEmpty()) {
            StateMessage(
                title = "No favorites yet",
                body = "Tap the heart on any game to save it here for later.",
                actionLabel = "Browse games",
                onAction = { onNav(NavDest.HOME) }
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = tileMinWidth()),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(favorites, key = { it.id }) { game ->
                    GameCard(
                        game = game,
                        imageLoader = imageLoader,
                        isDownloaded = game.id in installedIds,
                        isFavorite = game.id in favoriteIds,
                        onToggleFavorite = { viewModel.toggleFavorite(game) },
                        onClick = { onGameClick(game) }
                    )
                }
            }
        }
    }
}
