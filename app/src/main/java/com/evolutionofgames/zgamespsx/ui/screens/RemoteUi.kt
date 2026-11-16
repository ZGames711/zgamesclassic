package com.evolutionofgames.zgamespsx.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.evolutionofgames.zgamespsx.remote.RAction
import com.evolutionofgames.zgamespsx.remote.RBlock
import com.evolutionofgames.zgamespsx.remote.RItem
import com.evolutionofgames.zgamespsx.remote.RemoteActions
import com.evolutionofgames.zgamespsx.remote.WpConfig
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors

/*
 * Generic renderer for everything the WordPress plugin sends: banners, text
 * cards, button rows, carousels, link lists, full-screen pages and settings
 * menu rows. The vocabulary is fixed in the app; the CONTENT and its ORDER
 * come from the plugin, so new content needs no app update.
 */

/** Maps the plugin's icon names to bundled icons. Unknown names show a link icon. */
fun remoteIcon(name: String): ImageVector = when (name) {
    "info" -> Icons.Filled.Info
    "star" -> Icons.Filled.Star
    "shield" -> Icons.Filled.Shield
    "share" -> Icons.Filled.Share
    "download" -> Icons.Filled.Download
    "game" -> Icons.Filled.SportsEsports
    "mail" -> Icons.Filled.Email
    "gift" -> Icons.Filled.CardGiftcard
    "world" -> Icons.Filled.Public
    "news" -> Icons.Filled.Campaign
    else -> Icons.Filled.Link
}

private fun onColor(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF1F1200) else Color.White

private fun Modifier.tappable(ctx: android.content.Context, action: RAction?): Modifier =
    if (action == null) this else this.clickable { RemoteActions.perform(ctx, action) }

/** All blocks the plugin placed in [slot] ("home_top" / "home_bottom"). Draws nothing when there are none. */
@Composable
fun RemoteBlocks(slot: String, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
    val content by WpConfig.content.collectAsState()
    val dismissed by WpConfig.dismissedBlocks.collectAsState()
    val blocks = remember(content, dismissed, slot) {
        content.blocks.filter { it.slot == slot && it.id !in dismissed && !(content.appPostFeedSupported && it.id == "zg-app-posts") }
    }
    if (blocks.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEach { RemoteBlockView(it, imageLoader) }
    }
}

@Composable
fun RemoteBlockView(b: RBlock, imageLoader: ImageLoader) {
    val accent = b.accent?.let { Color(it) }
    when (b.type) {
        "banner" -> BannerBlock(b, accent, imageLoader)
        "text" -> TextBlock(b, accent)
        "buttons" -> ButtonsBlock(b, accent)
        "image" -> ImageBlock(b, imageLoader)
        "notice" -> NoticeBlock(b, accent)
        "carousel" -> CarouselBlock(b, accent, imageLoader)
        "slideshow" -> SlideshowBlock(b, imageLoader)
        "list" -> ListBlock(b, accent, imageLoader)
        "divider" -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
        else -> Unit // a block type this app build does not know: skip it
    }
}

@Composable
private fun SlideshowBlock(b: RBlock, imageLoader: ImageLoader) {
    val images = remember(b.items) { b.items.filter { it.image != null } }
    if (images.isEmpty()) return
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { images.size })
    val ratios = remember(images) { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
    LaunchedEffect(pager, images.size, b.intervalSec) {
        if (images.size > 1 && b.intervalSec > 0) while (true) {
            kotlinx.coroutines.delay(b.intervalSec * 1000L)
            pager.animateScrollToPage((pager.currentPage + 1) % images.size)
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxHeight = b.heightDp.coerceIn(120, 480).dp
        val ratio = ratios[pager.currentPage]
        val height = if (b.imageFit != "crop" && ratio != null && ratio > 0f) {
            (maxWidth / ratio).coerceAtMost(maxHeight).coerceAtLeast(1.dp)
        } else maxHeight
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))) {
            androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = images[page].image,
                    contentDescription = "Slide ${page + 1} of ${images.size}",
                    imageLoader = imageLoader,
                    onSuccess = { state ->
                        val image = state.result.image
                        if (image.width > 0 && image.height > 0) ratios[page] = image.width.toFloat() / image.height.toFloat()
                    },
                    contentScale = if (b.imageFit == "crop") ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            if (images.size > 1) Text(
                "${pager.currentPage + 1} / ${images.size}",
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color(0xBB000000), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun BlockTitle(title: String) {
    if (title.isBlank()) return
    Text(
        text = title,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = PsxColors.Yellow
    )
}

@Composable
private fun BannerBlock(b: RBlock, accent: Color?, imageLoader: ImageLoader) {
    val ctx = LocalContext.current
    val base = accent ?: PsxColors.OrangeDark
    val sizeMod = if (b.image != null) Modifier.aspectRatio(16f / 7f) else Modifier.heightIn(min = 110.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(sizeMod)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(base, PsxColors.Surface)))
            .tappable(ctx, b.action)
    ) {
        if (b.image != null) {
            AsyncImage(
                model = b.image,
                contentDescription = b.title.ifBlank { null },
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD07090F))))
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .padding(end = if (b.dismissible) 32.dp else 0.dp)
        ) {
            if (b.title.isNotBlank()) {
                Text(b.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (b.text.isNotBlank()) {
                Text(b.text, color = Color(0xFFEDE3CF), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (b.dismissible) {
            IconButton(onClick = { WpConfig.dismissBlock(ctx, b.id) }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ImageBlock(b: RBlock, imageLoader: ImageLoader) {
    val ctx = LocalContext.current
    if (b.image == null) return
    AsyncImage(
        model = b.image,
        contentDescription = b.title.ifBlank { null },
        imageLoader = imageLoader,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .tappable(ctx, b.action)
    )
}

@Composable
private fun NoticeBlock(b: RBlock, accent: Color?) {
    val ctx = LocalContext.current
    val c = accent ?: PsxColors.Yellow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = 0.16f))
            .tappable(ctx, b.action)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Campaign, contentDescription = null, tint = c)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (b.title.isNotBlank()) Text(b.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (b.text.isNotBlank()) Text(b.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (b.dismissible) {
            IconButton(onClick = { WpConfig.dismissBlock(ctx, b.id) }) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = c)
            }
        }
    }
}

@Composable
private fun TextBlock(b: RBlock, accent: Color?) {
    if (b.postTags) {
        PostDetailsContent("", b.text.split(" · ").map { it.trim() }.filter { it.isNotEmpty() })
        return
    }
    if (b.collapsible) {
        PostDetailsContent(b.text, emptyList())
        return
    }
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .tappable(ctx, b.action)
                .padding(16.dp)
        ) {
            if (b.title.isNotBlank()) {
                Text(b.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent ?: MaterialTheme.colorScheme.onSurface)
            }
            if (b.text.isNotBlank()) {
                if (b.title.isNotBlank()) Spacer(Modifier.height(4.dp))
                Text(b.text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ButtonsBlock(b: RBlock, accent: Color?) {
    val ctx = LocalContext.current
    if (b.items.isEmpty()) return
    Column {
        BlockTitle(b.title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(b.items) { it: RItem ->
                val bg = accent ?: MaterialTheme.colorScheme.primary
                Button(
                    onClick = { RemoteActions.perform(ctx, it.action) },
                    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = onColor(bg))
                ) { Text(it.title, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun CarouselBlock(b: RBlock, accent: Color?, imageLoader: ImageLoader) {
    val ctx = LocalContext.current
    if (b.items.isEmpty()) return
    Column {
        BlockTitle(b.title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(b.items) { it: RItem ->
                Card(
                    modifier = Modifier.width(200.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, accent?.copy(alpha = 0.7f) ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.tappable(ctx, it.action)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (it.image != null) {
                                AsyncImage(
                                    model = it.image,
                                    contentDescription = it.title.ifBlank { null },
                                    imageLoader = imageLoader,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(it.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (it.subtitle.isNotBlank()) {
                                Text(it.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            val appPost = it.action?.value.orEmpty()
                            if (b.id == "zg-app-posts" && it.action?.type == "page" && appPost.startsWith("ap-") && appPost.removePrefix("ap-").isNotEmpty() && appPost.removePrefix("ap-").all { c -> c.isDigit() }) {
                                RatingLabel("app:" + appPost.removePrefix("ap-"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListBlock(b: RBlock, accent: Color?, imageLoader: ImageLoader) {
    val ctx = LocalContext.current
    if (b.items.isEmpty()) return
    Column {
        BlockTitle(b.title)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, accent?.copy(alpha = 0.7f) ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column {
                b.items.forEachIndexed { i, it ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tappable(ctx, it.action)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (it.image != null) {
                            AsyncImage(
                                model = it.image,
                                contentDescription = null,
                                imageLoader = imageLoader,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(it.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (it.subtitle.isNotBlank()) {
                                Text(it.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (it.action != null) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen page requested by a banner, menu row, button or push tap.
 * Hosted once, above the NavHost, next to the notice dialog.
 */
@Composable
fun RemotePageHost(imageLoader: ImageLoader, viewModel: com.evolutionofgames.zgamespsx.ui.LibraryViewModel) {
    val postAds by com.evolutionofgames.zgamespsx.ads.AdConfig.snapshot.collectAsState()
    val id by WpConfig.openPageId.collectAsState()
    val content by WpConfig.content.collectAsState()
    val pageId = id ?: return
    val page = content.pages.firstOrNull { it.id == pageId }
    if (page == null) {
        // Config not downloaded yet (push tapped on a cold start): wait for it.
        // Once it HAS loaded and the page still isn't there, give up quietly.
        LaunchedEffect(content.loaded, pageId) { if (content.loaded) WpConfig.closePage() }
        return
    }
    Dialog(
        onDismissRequest = { WpConfig.closePage() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { WpConfig.closePage() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(page.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                if (page.kind == "web" && page.url != null) {
                    RemoteWebView(page.url)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val postId = pageId.removePrefix("ap-")
                        val isAppPost = pageId.startsWith("ap-") && postId.isNotEmpty() && postId.all { it.isDigit() }
                        if (isAppPost && page.blocks.none { it.id == "cta" }) item { RatingAction(game = "app:" + postId, modifier = Modifier.fillMaxWidth()) }
                        items(page.blocks) { block ->
                            if (isAppPost && block.id == "cta") RatingAction(game = "app:" + postId, modifier = Modifier.fillMaxWidth())
                            val isPost = pageId.startsWith("ap-")
                            if (isPost && block.id == "cta" && postAds.postAdPosition == "before") PostAdSlot(viewModel, large = false)
                            RemoteBlockView(block, imageLoader)
                            if (isPost && block.id == "cta" && postAds.postAdPosition == "after") PostAdSlot(viewModel, large = false)
                        }
                        if (pageId.startsWith("ap-")) item { PostAdSlot(viewModel, large = true) }
                        if (pageId.startsWith("ap-") && pageId.removePrefix("ap-").all { it.isDigit() } && pageId.length > 3) {
                            item { PostCommunity(game = "app:" + pageId.removePrefix("ap-"), modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                    if (pageId.startsWith("ap-") && pageId.removePrefix("ap-").isNotEmpty() && pageId.removePrefix("ap-").all { it.isDigit() }) PostAnchoredBanner(viewModel)
                }
            }
        }
    }
}

/**
 * A page from the plugin's own website shown inside the app: this is how whole
 * new features can ship without an app update. Navigation stays on the site;
 * any link to another host opens in the normal browser. The URL was already
 * checked in [com.evolutionofgames.zgamespsx.remote.RemoteContent.parse].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RemoteWebView(url: String) {
    val siteHost = remember { Uri.parse(WpConfig.SITE).host?.lowercase().orEmpty() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(android.graphics.Color.parseColor("#07090F"))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        val host = u.host?.lowercase().orEmpty()
                        val onSite = u.scheme == "https" && (host == siteHost || host.endsWith(".$siteHost"))
                        if (onSite) return false
                        try {
                            if (u.scheme == "https" || u.scheme == "http") {
                                c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        } catch (_: Throwable) { }
                        return true
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() }
    )
}
