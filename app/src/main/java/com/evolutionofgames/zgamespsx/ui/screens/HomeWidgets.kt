package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import com.evolutionofgames.zgamespsx.install.InstalledGame
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors

/** Home list header: a neon "Latest games" title and a sort toggle. No console filters. */
@Composable
fun FilterBar(
    sortAz: Boolean,
    onToggleSort: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PsxColors.AccentBrush)
        )
        Text(
            text = if (sortAz) "All games" else "Latest games",
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        FilterChip(
            selected = sortAz,
            onClick = onToggleSort,
            label = { Text(if (sortAz) "A–Z" else "Newest", fontWeight = FontWeight.SemiBold) },
            leadingIcon = {
                Icon(Icons.Filled.SwapVert, contentDescription = "Change sort order", modifier = Modifier.size(18.dp))
            },
            shape = RoundedCornerShape(50),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = PsxColors.Orange.copy(alpha = 0.22f),
                selectedLabelColor = PsxColors.Yellow,
                selectedLeadingIconColor = PsxColors.Yellow
            )
        )
    }
}

/** "Showing saved games" strip with a Retry action. */
@Composable
fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CloudOff, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
        )
        Text(
            "You're offline — showing saved games.",
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry) { Text("Retry", color = PsxColors.Yellow, fontWeight = FontWeight.Bold) }
    }
}

/** Section title with an optional small caption. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        fontSize = 16.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** Horizontal "Continue playing" strip: most recently played installed games first. */
@Composable
fun ContinuePlayingRow(
    games: List<InstalledGame>,
    coverFor: (InstalledGame) -> String?,
    imageLoader: ImageLoader,
    onPlay: (InstalledGame) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("Continue playing")
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(games, key = { it.key }) { game ->
                val title = TitleSanitizer.displayTitle(game.title)
                val cover = coverFor(game)
                Column(
                    modifier = Modifier
                        .width(156.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClickLabel = "Play $title") { onPlay(game) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = title,
                                imageLoader = imageLoader,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Filled.SportsEsports, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(30.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color(0x99000000))
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .background(PsxColors.Success, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow, contentDescription = "Play",
                                tint = Color(0xFF06281D), modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        text = title,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(6.dp))
    }
}
