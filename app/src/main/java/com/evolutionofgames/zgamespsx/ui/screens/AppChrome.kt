package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolutionofgames.zgamespsx.R
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors

/**
 * Shared app chrome, hosted ONCE by MainActivity's Scaffold (not by each
 * screen): a slim top bar with the wordmark + Ad-Free / Settings icons,
 * and a bottom navigation bar (thumb-reachable, Material 3) with the five
 * top-level destinations. Detail and the file-page resolver show no chrome.
 */
enum class NavDest(val route: String) {
    HOME("home"),
    SEARCH("search"),
    DOWNLOADS("downloads"),
    GAMES("games"),
    FAVORITES("favorites"),
    HELP("help"),
    CATEGORIES("categories"),
    SETTINGS("settings");

    companion object {
        fun fromRoute(route: String?): NavDest? = entries.firstOrNull { it.route == route }
    }
}

@Composable
fun AppHeader(
    onSettingsClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onPremiumClick: (() -> Unit)? = null,
    isAdFree: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Home icon: the official logo mark (transparent PNG), never stretched.
        Image(
            painter = painterResource(R.drawable.psx_home_logo),
            contentDescription = "ZGames PSX",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(40.dp)
                .aspectRatio(235f / 256f)
        )
        Text(
            text = "PSX",
            modifier = Modifier.padding(start = 5.dp),
            style = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) TextStyle(
                color = MaterialTheme.colorScheme.primary, fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp
            ) else TextStyle(
                brush = PsxColors.AccentBrush, fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp
            )
        )
        Box(modifier = Modifier.weight(1f))
        IconButton(onClick = onFavoritesClick, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) { Icon(Icons.Filled.FavoriteBorder, contentDescription = "Favorites", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
        NotificationInboxAction(modifier = Modifier.padding(start = 3.dp).size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), iconOnly = true)
        if (onPremiumClick != null) {
            IconButton(onClick = onPremiumClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.WorkspacePremium,
                    contentDescription = if (isAdFree) "Ad-Free active" else "Go Ad-Free",
                    tint = if (isAdFree) PsxColors.Gold
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private data class NavBarItem(val dest: NavDest, val icon: ImageVector, val outlined: ImageVector, val label: String)

private val navItems = listOf(
    NavBarItem(NavDest.HOME, Icons.Filled.Home, Icons.Outlined.Home, "Home"),
    NavBarItem(NavDest.SEARCH, Icons.Filled.Explore, Icons.Outlined.Explore, "Search"),
    NavBarItem(NavDest.DOWNLOADS, Icons.Filled.FileDownload, Icons.Outlined.FileDownload, "Downloads"),
    NavBarItem(NavDest.GAMES, Icons.Filled.VideogameAsset, Icons.Outlined.VideogameAsset, "My Games"),
    NavBarItem(NavDest.HELP, Icons.Filled.SupportAgent, Icons.Outlined.SupportAgent, "Help")
)

/** Bottom navigation. Re-tapping the selected tab does nothing (no duplicate screens). */
@Composable
fun BottomNavBar(current: NavDest?, onNavigate: (NavDest) -> Unit) {
    val remote by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    val navStyle = remote.navStyle
    val surface = (remote.navColor?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.surface).copy(alpha = remote.navOpacity / 100f)
    val accent = remote.navAccent?.let { androidx.compose.ui.graphics.Color(it) } ?: PsxColors.Yellow
    val effectiveSurface = surface.compositeOver(MaterialTheme.colorScheme.background)
    val foreground = if (effectiveSurface.luminance() > 0.45f) androidx.compose.ui.graphics.Color(0xFF24201A) else androidx.compose.ui.graphics.Color(0xFFF8F4ED)
    val contrast = (maxOf(accent.luminance(), effectiveSurface.luminance()) + 0.05f) / (minOf(accent.luminance(), effectiveSurface.luminance()) + 0.05f)
    val selectedColor = if (contrast >= 3f) accent else foreground
    // Neon hairline on top of the bar ties the chrome to the logo.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(
                        androidx.compose.ui.graphics.Color.Transparent,
                        PsxColors.Yellow.copy(alpha = 0.55f),
                        PsxColors.Yellow.copy(alpha = 0.55f),
                        androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            )
    )
    NavigationBar(
        modifier = Modifier
            .then(if (navStyle in setOf("floating", "capsule", "arcade")) Modifier.padding(horizontal = 12.dp, vertical = 5.dp) else Modifier)
            .clip(RoundedCornerShape(if (navStyle == "capsule") 40.dp else remote.navRadius.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                when (navStyle) {
                    "glossy" -> listOf(accent.copy(alpha = 0.32f), surface, surface)
                    "3d" -> listOf(accent.copy(alpha = 0.25f).compositeOver(surface), surface, if (effectiveSurface.luminance() > 0.45f) surface else androidx.compose.ui.graphics.Color.Black)
                    "arcade" -> listOf(accent.copy(alpha = 0.38f), surface, surface)
                    else -> listOf(surface, surface)
                }
            )),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = if (navStyle == "3d") 8.dp else 0.dp,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val visibleItems = if (remote.flag("categories_nav", false)) navItems + NavBarItem(NavDest.CATEGORIES, Icons.Filled.Category, Icons.Outlined.Category, "Categories") else navItems
        for (item in visibleItems) {
            val selected = item.dest == current
            val iconScale by animateFloatAsState(if (selected && remote.navEffect == "pop") 1.18f else 1f, label = "navScale")
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(item.dest) },
                icon = { Icon(if (selected) item.icon else item.outlined, contentDescription = null, modifier = Modifier.size(remote.navIconSize.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }) },
                label = { Text(item.label, fontSize = if (visibleItems.size > 5) 9.sp else 11.sp, lineHeight = 10.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selectedColor,
                    selectedTextColor = selectedColor,
                    indicatorColor = accent.copy(alpha = if (remote.navEffect == "glow" || navStyle == "capsule") 0.30f else 0.14f),
                    unselectedIconColor = foreground.copy(alpha = 0.78f),
                    unselectedTextColor = foreground.copy(alpha = 0.78f)
                )
            )
        }
    }
}
