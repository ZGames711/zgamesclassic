package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.view.View.MeasureSpec
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.model.SourceSite
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Game grid pieces: adaptive columns (2 on phones, more on tablets /
 * landscape), 16:9 landscape card tops, a
 * save-to-Favorites heart over the cover, title + site below.
 *
 * Native ad tiles are full-width rows that are clearly different from a
 * game card (own background, gold border, "Ad" badge) so they can never
 * be mistaken for content — see [NativeAdTile]. One appears after every N
 * games on Home and Search, and never when ad-free.
 */

sealed interface GridItem {
    data class GameItem(val game: Game) : GridItem
    data class AdItem(val key: String, val banner: Boolean = false) : GridItem
    data class AppPostItem(val post: com.evolutionofgames.zgamespsx.remote.RAppPost) : GridItem
}

/** Inserts a game-tile-sized ad tile after every 8 games. Empty when ad-free. */
fun buildGridItems(
    games: List<Game>,
    adFree: Boolean,
    nativeEnabled: Boolean = true,
    everyN: Int = 8,
    banner: Boolean = false,
    appPosts: List<com.evolutionofgames.zgamespsx.remote.RAppPost> = emptyList(),
    sortAz: Boolean = false
): List<GridItem> {
    if (games.isEmpty() && appPosts.isEmpty()) return emptyList()
    val out = ArrayList<GridItem>()
    val combined = games.map { GridItem.GameItem(it) } + appPosts.map { GridItem.AppPostItem(it) }
    fun dateKey(it: GridItem): String = when (it) {
        is GridItem.GameItem -> it.game.publishedAt.take(19)
        is GridItem.AppPostItem -> try { java.time.Instant.ofEpochSecond(it.post.date).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().take(19) } catch (_: Exception) { "" }
        else -> ""
    }
    val sorted = if (appPosts.isEmpty()) combined else if (sortAz) combined.sortedBy { when (it) { is GridItem.GameItem -> it.game.title; is GridItem.AppPostItem -> it.post.title; else -> "" }.lowercase() }
    else combined.sortedByDescending { dateKey(it) }.sortedByDescending { it is GridItem.AppPostItem && it.post.pinned }
    for ((index, entry) in sorted.withIndex()) {
        out += entry
        if (!adFree && nativeEnabled && everyN > 0 && (index + 1) % everyN == 0) {
            out += GridItem.AdItem("ad-$index", banner)
        }
    }
    return out
}

fun LazyGridScope.gridItems(
    items: List<GridItem>,
    imageLoader: ImageLoader,
    loadNativeAd: ((NativeAd?) -> Unit) -> Unit,
    onGameClick: (Game) -> Unit,
    installedIds: Set<String> = emptySet(),
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: ((Game) -> Unit)? = null
) {
    val tileStyle = com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.tileStyle
    items(
        items = items,
        key = {
            when (it) {
                is GridItem.GameItem -> "game-${it.game.id}"
                is GridItem.AppPostItem -> "post-${it.post.page}"
                is GridItem.AdItem -> it.key
            }
        },
        span = { item ->
            if (item is GridItem.AdItem || tileStyle == "list" || tileStyle == "large") GridItemSpan(maxLineSpan) else GridItemSpan(1)
        }
    ) { item ->
        when (item) {
            is GridItem.GameItem -> GameCard(
                game = item.game,
                imageLoader = imageLoader,
                isDownloaded = item.game.id in installedIds,
                isFavorite = item.game.id in favoriteIds,
                onToggleFavorite = onToggleFavorite?.let { cb -> { cb(item.game) } },
                onClick = { onGameClick(item.game) }
            )
            is GridItem.AppPostItem -> AppPostTile(item.post, imageLoader)
            is GridItem.AdItem -> if (item.banner) FeedBannerSlot() else NativeAdTile(loadNativeAd = loadNativeAd)
        }
    }
}

@Composable
fun GameCard(
    game: Game,
    imageLoader: ImageLoader,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null
) {
    val rc by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    val entrance = remember(game.id, rc.tileEffect) { Animatable(if (rc.tileEffect == "none") 1f else 0f) }
    LaunchedEffect(game.id, rc.tileEffect) { if (rc.tileEffect != "none") entrance.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer {
        alpha = entrance.value
        translationY = if (rc.tileEffect == "slide") (1f - entrance.value) * 24.dp.toPx() else 0f
    }) {
        GameCardBody(game, imageLoader, isDownloaded, onClick, isFavorite, onToggleFavorite)
        DownloadCountBadge(game.id, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp))
    }
}

@Composable
private fun GameCardBody(
    game: Game, imageLoader: ImageLoader, isDownloaded: Boolean, onClick: () -> Unit,
    isFavorite: Boolean, onToggleFavorite: (() -> Unit)?
) {
    val rc by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    if (rc.tileStyle != "classic") {
        GameTileVariant(rc.tileStyle, rc.tileRadius, game, imageLoader, isDownloaded, isFavorite, onToggleFavorite, onClick)
        return
    }
    val title = TitleSanitizer.displayTitle(game.title)
    // Press feedback: the card dips slightly while held, springs back on release.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "cardPress"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp)
            .shadow(if (rc.visualEffect == "shadow") 8.dp else 0.dp, RoundedCornerShape(rc.tileRadius.dp))
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Open $title"
            ) { onClick() },
        shape = RoundedCornerShape(rc.tileRadius.dp),
        border = BorderStroke(
            1.dp,
            if (isDownloaded) PsxColors.Yellow.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
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
                            .size(34.dp)
                    )
                }
                // Status badge: play when installed, download arrow otherwise.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .background(
                            if (isDownloaded) PsxColors.Success else Color(0xCC000000),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDownloaded) Icons.Filled.PlayArrow else Icons.Filled.Download,
                        contentDescription = if (isDownloaded) "Installed" else "Not installed",
                        tint = if (isDownloaded) Color(0xFF06281D) else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(40.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0x99000000), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from Favorites" else "Save to Favorites",
                                tint = if (isFavorite) Color(0xFFFF6B8B) else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
            Text(
                text = title,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RatingLabel(game.id, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = "PS1",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * In-feed native ad, built to Google AdMob native-ad policy:
 *  - spans the full row and looks clearly different from a game card
 *    (own background, accent border, "Ad" badge) — it never imitates content;
 *  - every asset is registered on the NativeAdView (icon, headline, body,
 *    advertiser, MediaView, call-to-action) so clicks/impressions count;
 *  - MediaView is full-width 16:9 (always above the 120dp minimum);
 *  - AdChoices is left untouched in the top-right corner;
 *  - the ad is destroyed when the tile leaves composition (no leak);
 *  - no fake/placeholder ad: when nothing loads, nothing is drawn.
 */
@Composable
fun NativeAdTile(loadNativeAd: ((NativeAd?) -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        var active = true
        loadNativeAd { ad -> if (active) nativeAd = ad else ad?.destroy() }
        onDispose { active = false; nativeAd?.destroy(); nativeAd = null }
    }
    val ad = nativeAd
    if (ad != null) {
        AndroidView(
            factory = { ctx -> buildNativeAdView(ctx, ad) },
            modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)
        )
    }
}

/**
 * A FrameLayout that is ALWAYS exactly 16:9 (height = width * 9 / 16),
 * measured deterministically — no post-layout height fixups. This is
 * what makes the ad tile pixel-identical in size to [GameCard]'s
 * aspectRatio(16/9) cover: the old code set the MediaView height in
 * a post{} callback, which fired before the cell was measured (width
 * 0) or after the media loaded, so the cover — and the whole tile —
 * came out the wrong height.
 */
private class AspectRatioFrameLayout(ctx: android.content.Context) : android.widget.FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 9 / 16
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}

/**
 * Native ad card. Layout: [icon] [Ad badge + headline / advertiser]   (AdChoices
 * sits top-right, so the header keeps a 28dp right margin), then the
 * 16:9 media, the body text and a full-width call-to-action button.
 */
private fun buildNativeAdView(ctx: android.content.Context, ad: NativeAd): NativeAdView {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Float) = (v * density + 0.5f).toInt()
    fun lp(w: Int, h: Int) = android.widget.LinearLayout.LayoutParams(w, h)
    val match = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
    val wrap = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT

    val adView = NativeAdView(ctx).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18f).toFloat()
            setColor(android.graphics.Color.parseColor("#1A160F"))
            setStroke(dp(1f), android.graphics.Color.parseColor("#80FFC107"))
        }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }
    val column = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = lp(match, wrap)
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
    }

    // ---- header: icon + (badge, headline) + advertiser ----
    val header = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = lp(match, wrap).apply { setMargins(0, 0, dp(28f), dp(10f)) }
    }
    var iconView: android.widget.ImageView? = null
    val iconDrawable = ad.icon?.drawable
    if (iconDrawable != null) {
        iconView = android.widget.ImageView(ctx).apply {
            setImageDrawable(iconDrawable)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(android.graphics.Color.parseColor("#192033"))
            }
            layoutParams = lp(dp(44f), dp(44f)).apply { setMargins(0, 0, dp(10f), 0) }
        }
        header.addView(iconView)
    }
    val titleColumn = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = lp(0, wrap).apply { weight = 1f }
    }
    val badgeRow = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = lp(match, wrap)
    }
    val badge = android.widget.TextView(ctx).apply {
        text = "Ad"
        textSize = 10f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#1A1200"))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(5f).toFloat()
            setColor(android.graphics.Color.parseColor("#FFC107"))
        }
        setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
        layoutParams = lp(wrap, wrap).apply { setMargins(0, 0, dp(8f), 0) }
    }
    val advertiser = android.widget.TextView(ctx).apply {
        text = ad.advertiser ?: ""
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#9AA6C4"))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = lp(0, wrap).apply { weight = 1f }
    }
    badgeRow.addView(badge)
    badgeRow.addView(advertiser)
    val headline = android.widget.TextView(ctx).apply {
        text = ad.headline
        textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#EAF0FF"))
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = lp(match, wrap).apply { setMargins(0, dp(3f), 0, 0) }
    }
    titleColumn.addView(badgeRow)
    titleColumn.addView(headline)
    header.addView(titleColumn)
    column.addView(header)

    // ---- media (required by policy when the ad has media) ----
    var mediaView: MediaView? = null
    if (ad.mediaContent != null) {
        val frame = AspectRatioFrameLayout(ctx).apply {
            layoutParams = lp(match, wrap)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(android.graphics.Color.parseColor("#0E0C08"))
            }
        }
        val media = MediaView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        }
        frame.addView(media)
        column.addView(frame)
        mediaView = media
    }

    // ---- body ----
    var bodyView: android.widget.TextView? = null
    if (!ad.body.isNullOrBlank()) {
        bodyView = android.widget.TextView(ctx).apply {
            text = ad.body
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#D6CBB8"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = lp(match, wrap).apply { setMargins(0, dp(10f), 0, 0) }
        }
        column.addView(bodyView)
    }

    // ---- call-to-action: a real, prominent button ----
    val cta = android.widget.TextView(ctx).apply {
        text = ad.callToAction ?: "Learn more"
        textSize = 14f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.WHITE)
        gravity = android.view.Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                android.graphics.Color.parseColor("#3D7BFF"),
                android.graphics.Color.parseColor("#6CC4FF")
            )
        ).apply { cornerRadius = dp(12f).toFloat() }
        layoutParams = lp(match, dp(46f)).apply { setMargins(0, dp(12f), 0, 0) }
    }
    column.addView(cta)

    adView.addView(column)
    adView.iconView = iconView
    adView.headlineView = headline
    adView.advertiserView = advertiser
    adView.bodyView = bodyView
    adView.mediaView = mediaView
    adView.callToActionView = cta
    adView.setNativeAd(ad)
    return adView
}


@Composable
private fun AppPostTile(post: com.evolutionofgames.zgamespsx.remote.RAppPost, imageLoader: ImageLoader) {
    val id = post.page.removePrefix("ap-").toLongOrNull() ?: return
    val game = Game(id = "app:$id", postId = id, sourceSite = SourceSite.EVO, title = post.title, coverUrl = post.image, platform = com.evolutionofgames.zgamespsx.model.Platform.PS1, downloadLink = null, publishedAt = "", categorySlugs = post.categories)
    GameCard(game = game, imageLoader = imageLoader, isDownloaded = false, onClick = { com.evolutionofgames.zgamespsx.remote.WpConfig.openPage(post.page) })
}
