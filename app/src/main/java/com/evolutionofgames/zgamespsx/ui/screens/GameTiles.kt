package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.model.SourceSite
import com.evolutionofgames.zgamespsx.remote.WpConfig
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors

/** Grid column width for the tile design the plugin selected. List / large rows span the full width. */
@Composable
fun tileMinWidth(): Dp {
    val rc by WpConfig.content.collectAsState()
    return when (rc.tileStyle) {
        "vertical" -> 118.dp
        "arcade_grid" -> 110.dp
        "compact" -> 128.dp
        "poster" -> 140.dp
        "large" -> 300.dp
        else -> 170.dp
    }
}

/**
 * The plugin-selectable game card layouts (everything except "classic", which
 * stays in GameCard). Colours always come from the active colour design.
 */
@Composable
fun GameTileVariant(
    style: String,
    radiusDp: Int,
    game: Game,
    imageLoader: ImageLoader,
    isDownloaded: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onClick: () -> Unit
) {
    val visual by WpConfig.content.collectAsState()
    val title = TitleSanitizer.displayTitle(game.title)
    if (style == "vertical") {
        VerticalGameCard(game, title, radiusDp, imageLoader, isDownloaded, isFavorite, onToggleFavorite, onClick)
        return
    }
    val shape = RoundedCornerShape(radiusDp.dp)
    val border = BorderStroke(
        1.dp,
        if (isDownloaded) PsxColors.Yellow.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp)
            .shadow(if (visual.visualEffect == "shadow") 8.dp else 0.dp, shape)
            .clickable(onClickLabel = "Open $title") { onClick() },
        shape = shape,
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        when (style) {
            "arcade_grid" -> Column(Modifier.background(Color(0xFF101010))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isDownloaded) "INSTALLED" else if (game.id.startsWith("app:")) "GAME" else game.platform.name, color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    RatingLabel(game.id, color = Color(0xFFFFD638))
                }
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(Color(0xFF292929))) {
                    Cover(game, title, imageLoader)
                    if (onToggleFavorite != null) Box(Modifier.align(Alignment.TopEnd)) { FavButton(isFavorite, onToggleFavorite) }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 5.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            "list" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(132.dp).aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Cover(game, title, imageLoader)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RatingLabel(game.id)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isDownloaded) "Installed" else "PS1", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                StatusDot(isDownloaded)
                if (onToggleFavorite != null) FavButton(isFavorite, onToggleFavorite)
                Spacer(Modifier.width(6.dp))
            }
            "compact" -> Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Cover(game, title, imageLoader)
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { StatusDot(isDownloaded) }
                }
                Text(title, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RatingLabel(game.id, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
            }
            else -> { // overlay / poster / large: title sits on the cover
                val ratio = when (style) { "poster" -> 3f / 4f; "large" -> 16f / 9f; else -> 4f / 3f }
                val big = style == "large"
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Cover(game, title, imageLoader)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 120f)))
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp).padding(end = if (big) 0.dp else 28.dp)) {
                        Text(title, color = Color.White, fontSize = if (big) 18.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        RatingLabel(game.id, color = PsxColors.Yellow)
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { StatusDot(isDownloaded) }
                    if (onToggleFavorite != null) {
                        Box(Modifier.align(Alignment.BottomEnd)) { FavButton(isFavorite, onToggleFavorite) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(game: Game, title: String, imageLoader: ImageLoader) {
    if (game.coverUrl != null) {
        AsyncImage(
            model = game.coverUrl,
            contentDescription = title,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.SportsEsports, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun StatusDot(isDownloaded: Boolean) {
    Box(
        Modifier.size(26.dp).background(if (isDownloaded) PsxColors.Success else Color(0xCC000000), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isDownloaded) Icons.Filled.PlayArrow else Icons.Filled.Download,
            contentDescription = if (isDownloaded) "Installed" else "Not installed",
            tint = if (isDownloaded) Color(0xFF06281D) else Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun FavButton(isFavorite: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
        Box(Modifier.size(26.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from Favorites" else "Save to Favorites",
                tint = if (isFavorite) Color(0xFFFF6B8B) else Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * "Game case" card: tall 2:3 cover with a soft accent glow, rating chip on the cover, the title
 * under it, and a GET / PLAY pill. Designed for a dense 3-column vertical grid.
 */
@Composable
private fun VerticalGameCard(
    game: Game,
    title: String,
    radiusDp: Int,
    imageLoader: ImageLoader,
    isDownloaded: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "vcPress"
    )
    val shape = RoundedCornerShape(radiusDp.coerceIn(10, 24).dp)
    val accent = MaterialTheme.colorScheme.primary
    val edge = if (isDownloaded) Brush.linearGradient(listOf(PsxColors.Success, PsxColors.Success.copy(alpha = 0.15f)))
    else Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.05f)))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "Open $title") { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(10.dp, shape, ambientColor = accent, spotColor = accent)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, edge, shape)
        ) {
            Cover(game, title, imageLoader)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xF2000000)
                    )
                )
            )
            Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 7.dp)) {
                RatingLabel(game.id, color = PsxColors.Yellow)
            }
            if (onToggleFavorite != null) {
                Box(Modifier.align(Alignment.TopEnd)) { FavButton(isFavorite, onToggleFavorite) }
            }
        }
        Spacer(Modifier.size(8.dp))
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
        Spacer(Modifier.size(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("PS1", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (isDownloaded) PsxColors.Success else accent)
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                Text(
                    if (isDownloaded) "PLAY" else "GET",
                    color = if (isDownloaded) Color(0xFF06281D) else MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
