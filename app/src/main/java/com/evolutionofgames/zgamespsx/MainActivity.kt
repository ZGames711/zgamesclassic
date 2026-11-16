package com.evolutionofgames.zgamespsx

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.evolutionofgames.zgamespsx.consent.ConsentManager
import com.evolutionofgames.zgamespsx.prefs.AppPrefs
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import com.evolutionofgames.zgamespsx.ui.screens.AppHeader
import com.evolutionofgames.zgamespsx.ui.screens.BottomNavBar
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.evolutionofgames.zgamespsx.crash.CrashReportDialog
import com.evolutionofgames.zgamespsx.crash.CrashReporter
import com.evolutionofgames.zgamespsx.crash.CrashState
import com.evolutionofgames.zgamespsx.download.DownloadStatus
import com.evolutionofgames.zgamespsx.install.InstallState
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import com.evolutionofgames.zgamespsx.ui.screens.DetailScreen
import com.evolutionofgames.zgamespsx.ui.screens.DownloadsScreen
import com.evolutionofgames.zgamespsx.ui.screens.FavoritesScreen
import com.evolutionofgames.zgamespsx.ui.screens.FilePageResolverScreen
import com.evolutionofgames.zgamespsx.ui.screens.GamesScreen
import com.evolutionofgames.zgamespsx.ui.screens.HomeScreen
import com.evolutionofgames.zgamespsx.ui.screens.NavDest
import com.evolutionofgames.zgamespsx.ui.screens.SearchScreen
import com.evolutionofgames.zgamespsx.ui.screens.SettingsScreen
import com.evolutionofgames.zgamespsx.ui.theme.PsxTheme
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Sample 2 app entry: dark theme, 2-column grid Home (no filter row),
 * Detail, Search, Downloads, Settings (Library removed entirely).
 * Top-level screens share one icon nav bar (below the header); Detail
 * keeps its Back button. No blog, no articles — a game library:
 * browse games, download, play. A pending file-page resolver request
 * replaces the NavHost with the WebView resolver screen until it is
 * captured or closed.
 *
 * Global dialogs live here (above the NavHost) so installer prompts
 * (password / game ID / folder), the one-time BIOS dialog, the
 * "Download started" dialog, and boot notices show on whatever screen
 * the user is on.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels()

    private lateinit var imageLoader: ImageLoader
    private var consentFinished by mutableStateOf(false)


    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                val error = viewModel.installer.setFolder(uri)
                if (error == null) {
                    viewModel.installer.resumePending()
                } else {
                    viewModel.toast(error)
                }
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result not needed */ }

    /** Android 13+: ask once after splash and the initial privacy flow. */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (AppPrefs.notificationPermissionAsked(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        try { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); AppPrefs.setNotificationPermissionAsked(this) } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (core-splashscreen): must run before super.onCreate.
        // It shows only the background colour, then hands over to the
        // animated logo splash drawn in Compose below.
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        try { com.evolutionofgames.zgamespsx.remote.WpConfig.handleIntent(intent) } catch (_: Throwable) { }
        // Coil images use the same OkHttp client shape as feeds: the
        // zgamesandroid firewall 403s any request carrying an
        // Accept-Encoding header, so the network interceptor strips it.
        val imageClient = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Accept-Encoding").build())
            }
            .build()
        imageLoader = ImageLoader.Builder(this)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            .build()

        // Ad consent (UMP). Ads stay off until this says they may be requested.
        viewModel.ads.clearCachedAds()
        ConsentManager.gather(this, onReset = { viewModel.ads.clearCachedAds() }, onFinished = { consentFinished = true }) { viewModel.ads.prewarm(applicationContext) }

        setContent {
            PsxTheme {
                val appearance by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
                val light by com.evolutionofgames.zgamespsx.remote.WpConfig.light.collectAsState()
                androidx.compose.runtime.SideEffect {
                    val insets = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    val useDarkIcons = light && appearance.flag("light_mode", false)
                    insets.isAppearanceLightStatusBars = useDarkIcons
                    insets.isAppearanceLightNavigationBars = useDarkIcons
                }
                // rememberSaveable: the splash plays once per launch, not
                // again after a rotation or process restore.
                var showSplash by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    if (showSplash) {
                        delay(1500)
                        showSplash = false
                    }
                }
                // App-open ad right after the splash (plugin switch: app_open_on_launch).
                LaunchedEffect(showSplash) {
                    if (!showSplash) viewModel.ads.showAppOpenAfterSplash(this@MainActivity)
                }
                LaunchedEffect(showSplash, consentFinished) {
                    if (!showSplash && consentFinished) { delay(400); maybeAskNotificationPermission() }
                }
                Crossfade(
                    targetState = showSplash,
                    animationSpec = tween(350),
                    label = "splash"
                ) { splash ->
                    if (splash) SplashScreen() else MainContent()
                }
            }
        }
    }

    @Composable
    private fun MainContent() {
        var showPremiumPrompt by remember { mutableStateOf(false) }
        val navController = rememberNavController()
        val resolver by viewModel.resolverRequest.collectAsState()
        val adFree by viewModel.billing.isAdFree.collectAsState()
        val backStack by navController.currentBackStackEntryAsState()
        val currentDest = NavDest.fromRoute(backStack?.destination?.route)
        val pendingResolver = resolver
        val chromeVisible = currentDest != null && pendingResolver == null

        // Screen requested by a banner, menu row or tapped push (WordPress plugin).
        val wantedScreen by com.evolutionofgames.zgamespsx.remote.WpConfig.pendingScreen.collectAsState()
        LaunchedEffect(wantedScreen) {
            val route = wantedScreen ?: return@LaunchedEffect
            com.evolutionofgames.zgamespsx.remote.WpConfig.consumeScreen()
            NavDest.fromRoute(route)?.let { navigateTop(navController, it) }
        }

        val design by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
        val bg = MaterialTheme.colorScheme.background
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.secondary
        val backgroundBrush = when (design.visualEffect) {
            "glass" -> Brush.verticalGradient(listOf(primary.copy(alpha = 0.15f).compositeOver(bg), bg, secondary.copy(alpha = 0.09f).compositeOver(bg)))
            "aurora" -> Brush.linearGradient(listOf(primary.copy(alpha = 0.20f).compositeOver(bg), bg, Color(0xFF2878A5).copy(alpha = 0.18f).compositeOver(bg)))
            "midnight" -> Brush.verticalGradient(listOf(Color(0xFF41589B).copy(alpha = 0.22f).compositeOver(bg), bg, Color(0xFF703E96).copy(alpha = 0.14f).compositeOver(bg)))
            "sunset" -> Brush.linearGradient(listOf(Color(0xFFEE8739).copy(alpha = 0.20f).compositeOver(bg), bg, Color(0xFFD1496D).copy(alpha = 0.15f).compositeOver(bg)))
            "sapphire" -> Brush.verticalGradient(listOf(Color(0xFF2479E8).copy(alpha = 0.23f).compositeOver(bg), bg, Color(0xFF6A5AE0).copy(alpha = 0.14f).compositeOver(bg)))
            "emerald" -> Brush.linearGradient(listOf(Color(0xFF17A77E).copy(alpha = 0.20f).compositeOver(bg), bg, Color(0xFF44B9A9).copy(alpha = 0.12f).compositeOver(bg)))
            "rose" -> Brush.verticalGradient(listOf(Color(0xFFDB548A).copy(alpha = 0.19f).compositeOver(bg), bg, Color(0xFF9B62DE).copy(alpha = 0.14f).compositeOver(bg)))
            "neon" -> Brush.linearGradient(listOf(Color(0xFF16A5C5).copy(alpha = 0.20f).compositeOver(bg), bg, Color(0xFFB84BCC).copy(alpha = 0.18f).compositeOver(bg)))
            else -> Brush.verticalGradient(listOf(bg, bg))
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().background(backgroundBrush),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                if (chromeVisible) {
                    Box(modifier = Modifier.statusBarsPadding()) {
                        AppHeader(
                            onSettingsClick = { navigateTop(navController, NavDest.SETTINGS) },
                            onFavoritesClick = { navigateTop(navController, NavDest.FAVORITES) },
                            onPremiumClick = if (currentDest == NavDest.SETTINGS) null
                            else ({ navigateTop(navController, NavDest.SETTINGS) }),
                            isAdFree = adFree
                        )
                    }
                }
            },
            bottomBar = {
                if (chromeVisible) {
                    BottomNavBar(
                        current = currentDest,
                        onNavigate = { navigateTop(navController, it) }
                    )
                }
            }
        ) { inner ->
            // Detail draws its cover edge-to-edge under the status bar, so
            // it takes only the bottom inset; every other screen takes all.
            val contentPadding =
                if (currentDest == null && pendingResolver == null) {
                    PaddingValues(bottom = inner.calculateBottomPadding())
                } else inner
            Box(modifier = Modifier.padding(contentPadding)) {
                // File-page resolver (WebView): replaces the NavHost while
                // open. Capture starts the real download; closing without
                // a capture starts nothing.
                if (pendingResolver != null) {
                    FilePageResolverScreen(
                        pageUrl = pendingResolver.pageUrl,
                        gameTitle = pendingResolver.gameTitle,
                        onFileCaptured = { captured ->
                            viewModel.startCapturedDownload(
                                pendingResolver.gameTitle,
                                pendingResolver.gameId,
                                captured,
                                pendingResolver.expectedSha256
                            )
                            viewModel.clearResolver()
                        },
                        onClose = { viewModel.clearResolver() }
                    )
                } else {
                    AppNav(navController) {
                        if (!viewModel.billing.isAdFree.value) {
                            val prefs = getSharedPreferences("premium_prompt", MODE_PRIVATE)
                            val clicks = prefs.getInt("game_opens", 0) + 1
                            prefs.edit().putInt("game_opens", clicks).apply()
                            if (clicks >= 5 && (clicks - 5) % 30 == 0) showPremiumPrompt = true
                        }
                    }
                }
            }
        }
        if (showPremiumPrompt && !adFree) {
            AlertDialog(onDismissRequest = { showPremiumPrompt = false },
                title = { Text("Enjoy ZGames PSX ad-free") },
                text = { Text("Go Premium to remove ads. You can review the subscription price and terms before buying.") },
                confirmButton = { TextButton(onClick = { showPremiumPrompt = false; navigateTop(navController, NavDest.SETTINGS) }) { Text("See Ad-Free") } },
                dismissButton = { TextButton(onClick = { showPremiumPrompt = false }) { Text("Not now") } })
        }
        GlobalDialogs(
            onGoToDownloads = { navigateTop(navController, NavDest.DOWNLOADS) },
            onGoPremium = { navigateTop(navController, NavDest.SETTINGS) }
        )
        // Crash report from the last run (if any): shown once, dismissible.
        var crashReport by remember {
            mutableStateOf(CrashReporter.readReport(applicationContext))
        }
        crashReport?.let { report ->
            CrashReportDialog(
                report = report,
                onDismiss = { crashReport = null },
                onClear = {
                    CrashReporter.clearReport(applicationContext)
                    crashReport = null
                }
            )
        }
    }

    /** Single-top navigation for the 5 top-level tabs, with state restored on return. */
    private fun navigateTop(navController: NavHostController, dest: NavDest) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try { com.evolutionofgames.zgamespsx.remote.WpConfig.handleIntent(intent) } catch (_: Throwable) { }
    }

    /** Bumped on every resume so the storage-access prompt re-checks after the user returns from Settings. */
    private var storageTick by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        storageTick++
        // Re-check the WordPress plugin (throttled to every 10 min).
        try { com.evolutionofgames.zgamespsx.remote.WpConfig.setForeground(this, true) } catch (_: Throwable) { }
        // Notifications published by the plugin since the last check.
        Thread { try { com.evolutionofgames.zgamespsx.push.PushManager.poll(applicationContext); com.evolutionofgames.zgamespsx.community.CommunityApi.pollReplies(applicationContext) } catch (_: Throwable) { } }.start()
        // Back from a PSP/PS2 game: show the exit interstitial (once) and
        // keep the app-open ad out of the way.
        val returningFromGame = com.evolutionofgames.zgamespsx.emulator.EmulatorSession.consumeExit()
        viewModel.ads.onForeground(this, suppressAppOpen = returningFromGame)
        viewModel.consumeReturnNotice()
        if (returningFromGame) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) viewModel.ads.showExitInterstitial(this)
            }, 450L)
        }
    }

    override fun onPause() {
        try { com.evolutionofgames.zgamespsx.remote.WpConfig.setForeground(this, false) } catch (_: Throwable) { }
        viewModel.ads.onBackground()
        super.onPause()
    }

    @Composable
    private fun AppNav(navController: NavHostController, onGameOpened: () -> Unit) {
        // Crash-report context: record the current screen so a saved
        // report says where the app was when it crashed.
        DisposableEffect(navController) {
            val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                try {
                    CrashState.screen = destination.route ?: "unknown"
                } catch (_: Throwable) {
                }
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
        val goTop: (NavDest) -> Unit = { dest -> navigateTop(navController, dest) }
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    imageLoader = imageLoader,
                    onGameClick = { game ->
                        viewModel.cacheGame(game)
                        navController.navigate("detail/${game.id}")
                        onGameOpened()
                    },
                    onNav = goTop,
                    onPlayInstalled = { installed ->
                        viewModel.playInstalled(this@MainActivity, installed)
                    }
                )
            }
            composable(
                route = "detail/{gameId}",
                arguments = listOf(navArgument("gameId") { type = NavType.StringType })
            ) { backStackEntry ->
                val gameId = backStackEntry.arguments?.getString("gameId") ?: ""
                // After the process was killed the in-memory cache is empty:
                // resolve the game again (saved favourites, then one network fetch).
                var resolved by remember(gameId) { mutableStateOf(viewModel.gameById(gameId)) }
                var resolving by remember(gameId) { mutableStateOf(resolved == null) }
                LaunchedEffect(gameId, resolving) {
                    if (resolved == null && resolving) {
                        resolved = viewModel.resolveGame(gameId)
                        resolving = false
                    }
                }
                val game = resolved
                if (game == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (resolving) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Loading game…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    "Couldn't load this game. Check your connection and try again.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = {
                                    resolving = true
                                }) { Text("Retry") }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = {
                                    if (!navController.popBackStack()) navigateTop(navController, NavDest.HOME)
                                }) { Text("Back to library") }
                            }
                        }
                    }
                } else {
                    DetailScreen(
                        game = game,
                        viewModel = viewModel,
                        imageLoader = imageLoader,
                        onBack = { navController.popBackStack() },
                        onDownloadClick = {},
                        onRelatedGame = { related -> viewModel.cacheGame(related); navController.navigate("detail/${related.id}"); onGameOpened() },
                        onPlay = {
                            viewModel.installedGameFor(game)?.let { installed ->
                                viewModel.playInstalled(this@MainActivity, installed)
                            }
                        }
                    )
                }
            }
            composable("categories") {
                com.evolutionofgames.zgamespsx.ui.screens.CategoriesScreen(viewModel, imageLoader) { game ->
                    viewModel.cacheGame(game); navController.navigate("detail/${game.id}"); onGameOpened()
                }
            }
            composable("search") {
                SearchScreen(
                    viewModel = viewModel,
                    imageLoader = imageLoader,
                    onGameClick = { game ->
                        viewModel.cacheGame(game)
                        navController.navigate("detail/${game.id}")
                        onGameOpened()
                    },
                    onNav = goTop
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    viewModel = viewModel,
                    onNav = goTop,
                    activity = this@MainActivity,
                    onPlay = { installed ->
                        viewModel.playInstalled(this@MainActivity, installed)
                    },
                    onGoPremium = { goTop(NavDest.SETTINGS) }
                )
            }
            composable("games") {
                GamesScreen(
                    viewModel = viewModel,
                    imageLoader = imageLoader,
                    onNav = goTop,
                    onPlay = { installed ->
                        viewModel.playInstalled(this@MainActivity, installed)
                    }
                )
            }
            composable("favorites") {
                FavoritesScreen(
                    viewModel = viewModel,
                    imageLoader = imageLoader,
                    onGameClick = { game ->
                        viewModel.cacheGame(game)
                        navController.navigate("detail/${game.id}")
                        onGameOpened()
                    },
                    onNav = goTop
                )
            }
            composable("help") { com.evolutionofgames.zgamespsx.ui.screens.HelpScreen() }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onNav = goTop,
                    onPrivacyOptions = { viewModel.ads.clearCachedAds(); ConsentManager.showPrivacyOptions(this@MainActivity) { viewModel.ads.prewarm(applicationContext) } },
                    // Advanced manual override: the normal flow never
                    // needs this (the folder is auto-created + selected
                    // at startup), but a user who wants their own folder
                    // can still pick it here; future installs use it.
                    onChooseFolderManually = { folderPicker.launch(null) }
                )
            }
        }
    }

    @Composable
    private fun GlobalDialogs(onGoToDownloads: () -> Unit, onGoPremium: () -> Unit) {
        val installState by viewModel.installer.state.collectAsState()
        val downloadItems by viewModel.downloads.items.collectAsState()
        // Crash-report context: whether a download/install was running
        // when a crash happened (helps tell an ad crash from a
        // download/install crash in the saved report).
        LaunchedEffect(installState) {
            try {
                CrashState.installRunning = installState is InstallState.Working
            } catch (_: Throwable) {
            }
        }
        LaunchedEffect(downloadItems) {
            try {
                CrashState.downloadRunning =
                    downloadItems.any { it.status == DownloadStatus.RUNNING }
            } catch (_: Throwable) {
            }
        }
        // Maintenance / update / announcement from the WordPress plugin.
        com.evolutionofgames.zgamespsx.ui.screens.RemoteNoticeHost()
        com.evolutionofgames.zgamespsx.ui.screens.RemotePageHost(imageLoader, viewModel)
        val notice by viewModel.notice.collectAsState()
        val downloadStarted by viewModel.downloadStarted.collectAsState()
        val needEmulator by viewModel.needEmulator.collectAsState()

        // "Download started" dialog (ZGamez-style): shown once a download
        // really starts, with a Go to Downloads action.
        downloadStarted?.let { title ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissDownloadStarted() },
                title = { Text("Download started") },
                text = {
                    Text(
                        "“${com.evolutionofgames.zgamespsx.display.TitleSanitizer.displayTitle(title)}” " +
                                "is downloading. You can follow it in Downloads."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.dismissDownloadStarted()
                        onGoToDownloads()
                    }) { Text("Go to Downloads") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDownloadStarted() }) { Text("Dismiss") }
                }
            )
        }

        // Download ad pause (plugin: Session & ads). Paused at the set % until the player continues.
        val halfwayId by viewModel.halfwayDialogId.collectAsState()
        halfwayId?.let {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Halfway there!") },
                text = { Text("Your download is paused. Watch a short ad to continue, or go Premium for ad-free downloads.") },
                confirmButton = {
                    Button(onClick = { viewModel.continueAfterHalfway(this@MainActivity) }) { Text("Watch ad to continue") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissHalfwayDialog(); onGoPremium() }) { Text("Go Premium") }
                }
            )
        }

        // Plain notice (boot failure, install errors surfaced by screens).
        notice?.let { message ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissNotice() },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissNotice() }) { Text("OK") }
                },
                text = { Text(message) }
            )
        }

        // No PS1 emulator installed: explain and open the store page.
        if (needEmulator) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissNeedEmulator() },
                title = { Text("Emulator not available") },
                text = {
                    Text(
                        "This build doesn't include the PS1 emulator core. " +
                            "Developer: rebuild with internet so Gradle downloads libswanstation_libretro_android.so, " +
                            "or place it in app/src/main/jniLibs/arm64-v8a/ (see CORE_README.md). " +
                            "Meanwhile you can play with the free RetroArch app + the SwanStation core."
                    )
                },
                confirmButton = { Button(onClick = { viewModel.openEmulatorStore() }) { Text("Get RetroArch") } },
                dismissButton = { TextButton(onClick = { viewModel.dismissNeedEmulator() }) { Text("Later") } }
            )
        }

        when (val state = installState) {
            is InstallState.NeedPassword -> {
                var password by remember(state.title) { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { viewModel.installer.dismiss() },
                    title = { Text("Password needed") },
                    text = {
                        ColumnContent {
                            Text(
                                if (state.wrongPassword) {
                                    "That password was wrong (${state.attemptsLeft} tries left). Enter the archive password."
                                } else {
                                    "“${com.evolutionofgames.zgamespsx.display.TitleSanitizer.displayTitle(state.title)}” is password-protected. Enter the archive password."
                                }
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                singleLine = true,
                                placeholder = { Text("Archive password") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.installer.providePassword(password) },
                            enabled = password.isNotEmpty()
                        ) { Text("Install") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.installer.cancelPassword() }) { Text("Cancel") }
                    }
                )
            }
            is InstallState.NeedGameId -> {
                var manualId by remember(state.title) { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { viewModel.installer.dismiss() },
                    title = { Text("Which game is this for?") },
                    text = {
                        ColumnContent {
                            Text("These textures need a game ID (4 letters + 5 digits, e.g. ULUS10041). Pick one of your games, or type the ID.")
                            if (state.error != null) {
                                Text(state.error, color = MaterialTheme.colorScheme.error)
                            }
                            for (suggestion in state.suggestions) {
                                OutlinedButton(
                                    onClick = { viewModel.installer.provideGameId(suggestion.gameId) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${com.evolutionofgames.zgamespsx.display.TitleSanitizer.displayTitle(suggestion.title)} (${suggestion.gameId})")
                                }
                            }
                            OutlinedTextField(
                                value = manualId,
                                onValueChange = { manualId = it.uppercase() },
                                singleLine = true,
                                placeholder = { Text("ULUS10041") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.installer.provideGameId(manualId) },
                            enabled = manualId.isNotBlank()
                        ) { Text("Use this ID") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.installer.dismiss() }) { Text("Skip textures") }
                    }
                )
            }
            is InstallState.NeedFolder -> {
                AlertDialog(
                    onDismissRequest = { viewModel.installer.dismiss() },
                    title = { Text("Choose your game folder") },
                    text = {
                        Text(
                            (state.problem ?: "") +
                                    (if (state.problem != null) "\n\n" else "") +
                                    "Pick the main game folder (the one that contains GAME, SAVEDATA and TEXTURES)."
                        )
                    },
                    confirmButton = {
                        Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.installer.dismiss() }) { Text("Cancel") }
                    }
                )
            }
            is InstallState.Done -> {
                val shown = com.evolutionofgames.zgamespsx.display.TitleSanitizer.displayTitle(state.title)
                // The game to offer Play for: the one this install just added,
                // else the installed game the mod's game id belongs to.
                val fresh by viewModel.justInstalled.collectAsState()
                val installedNow by viewModel.library.games.collectAsState()
                val playTarget = fresh ?: state.gameId?.let { gid -> installedNow.firstOrNull { it.gameId == gid } }
                val closeDone = { viewModel.clearJustInstalled(); viewModel.installer.dismiss() }
                val body = when {
                    state.games > 0 && state.textureFiles > 0 ->
                        "“$shown” is installed with its textures. Tap Play to start it now."
                    state.games > 0 ->
                        "“$shown” is installed. Tap Play to start it now."
                    state.textureFiles > 0 && state.texturesSkipped ->
                        "Some textures from “$shown” were installed, but others couldn't be matched to a game and were skipped."
                    state.textureFiles > 0 ->
                        "“$shown” is a texture mod. It was applied to your installed game automatically — just start that game from My Games.\n\n" +
                                "If you don't have the base game yet, download it first, then install this mod again."
                    state.texturesSkipped ->
                        "“$shown” only contains textures, and no installed game matched them. Install the base game first, then install this mod again."
                    state.saveFiles > 0 ->
                        "“$shown” saved data was installed. Start the matching game to use it."
                    else -> "“$shown” was installed."
                }
                AlertDialog(
                    onDismissRequest = { closeDone() },
                    icon = {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = com.evolutionofgames.zgamespsx.ui.theme.PsxColors.Yellow
                        )
                    },
                    title = { Text("Installed") },
                    text = { Text(body) },
                    confirmButton = {
                        if (playTarget != null) {
                            Button(onClick = {
                                closeDone()
                                viewModel.playInstalled(this@MainActivity, playTarget)
                            }) { Text("Play", fontWeight = FontWeight.SemiBold) }
                        } else {
                            TextButton(onClick = { closeDone() }) { Text("OK") }
                        }
                    },
                    dismissButton = {
                        if (playTarget != null) TextButton(onClick = { closeDone() }) { Text("Later") }
                    }
                )
            }
            is InstallState.Error -> {
                AlertDialog(
                    onDismissRequest = { viewModel.installer.dismiss() },
                    title = { Text("Couldn't install") },
                    text = {
                        // Plain-text details only (e.g. the UNKNOWN
                        // diagnosis: HTML page vs unknown extension).
                        // Stack-trace details are never shown in UI.
                        val plainDetails = state.details?.takeIf { !it.contains("\n  at ") }
                        Text(
                            state.message +
                                    (plainDetails?.let { "\n\n$it" } ?: "")
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.installer.dismiss() }) { Text("OK") }
                    }
                )
            }
            is InstallState.Info -> {
                AlertDialog(
                    onDismissRequest = { viewModel.installer.dismiss() },
                    text = { Text(state.message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.installer.dismiss() }) { Text("OK") }
                    }
                )
            }
            else -> Unit
        }
    }

    @Composable
    private fun ColumnContent(content: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

private data class FloatSymbol(
    val x: Float, val speed: Float, val offset: Float, val size: Float, val kind: Int, val spin: Float
)

private val splashSymbols = listOf(
    FloatSymbol(0.08f, 1.0f, 0.00f, 14f, 0, 1f),
    FloatSymbol(0.22f, 0.7f, 0.35f, 10f, 1, -1f),
    FloatSymbol(0.36f, 1.2f, 0.62f, 12f, 2, 1f),
    FloatSymbol(0.50f, 0.8f, 0.15f, 16f, 3, -1f),
    FloatSymbol(0.64f, 1.1f, 0.80f, 11f, 0, -1f),
    FloatSymbol(0.78f, 0.6f, 0.48f, 15f, 1, 1f),
    FloatSymbol(0.92f, 0.9f, 0.27f, 12f, 2, -1f),
    FloatSymbol(0.15f, 0.9f, 0.90f, 9f, 3, 1f),
    FloatSymbol(0.43f, 0.6f, 0.72f, 9f, 1, 1f),
    FloatSymbol(0.71f, 1.3f, 0.05f, 9f, 2, -1f),
    FloatSymbol(0.88f, 0.75f, 0.58f, 10f, 0, 1f),
    FloatSymbol(0.30f, 1.0f, 0.20f, 8f, 0, -1f)
)

/** Controller-button shapes: triangle, circle, cross, square. */
private fun DrawScope.drawPsSymbol(kind: Int, c: Offset, r: Float, color: Color, rot: Float) {
    val stroke = Stroke(width = r * 0.24f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    rotate(rot, c) {
        when (kind) {
            0 -> {
                val p = Path().apply {
                    moveTo(c.x, c.y - r)
                    lineTo(c.x + r * 0.87f, c.y + r * 0.5f)
                    lineTo(c.x - r * 0.87f, c.y + r * 0.5f)
                    close()
                }
                drawPath(p, color, style = stroke)
            }
            1 -> drawCircle(color, r, c, style = stroke)
            2 -> {
                drawLine(color, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Offset(c.x + r * 0.8f, c.y + r * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(c.x + r * 0.8f, c.y - r * 0.8f), Offset(c.x - r * 0.8f, c.y + r * 0.8f), stroke.width, StrokeCap.Round)
            }
            else -> drawRect(color, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = stroke)
        }
    }
}

/**
 * Launch splash. Layers, back to front:
 *  1. midnight gradient + three slowly drifting colour orbs (blue / cyan / green)
 *  2. controller-button symbols (triangle, circle, cross, square) floating up
 *  3. the logo on a rounded card with a pulsing glow and a rotating
 *     neon ring, revealed with a scale + fade
 *  4. tagline that slides in, then a gradient progress bar with a status line
 * Everything is drawn in Canvas / Compose: no extra assets, never stretched.
 */
@Composable
private fun SplashScreen() {
    // Palette: midnight backdrop, blue + cyan glow around the logo.
    val bg = Color(0xFF05070C)
    val gold = Color(0xFF6CC4FF)
    val orange = Color(0xFF3D7BFF)
    val blue = Color(0xFF2F9BFF)
    // Classic PlayStation symbol colours: triangle, circle, cross, square.
    val symbolColors = listOf(Color(0xFF38D39F), Color(0xFFFF4D5E), Color(0xFF4D8DFF), Color(0xFFFF7BC6))

    val infinite = rememberInfiniteTransition(label = "splash")
    val phase: Float by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "phase"
    )
    val glow: Float by infinite.animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )
    val ring: Float by infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "ring"
    )
    val reveal = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        launch {
            delay(550)
            tagline.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
        progress.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
    }
    val status = when {
        progress.value < 0.4f -> "Preparing your library…"
        progress.value < 0.8f -> "Loading games…"
        else -> "Almost ready…"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0F1A), bg, Color(0xFF020308))))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val t: Float = phase * 2f * Math.PI.toFloat()
            fun orb(cx: Float, cy: Float, r: Float, color: Color, a: Float) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), Offset(cx, cy), r),
                    r, Offset(cx, cy)
                )
            }
            orb(w * (0.25f + 0.10f * sin(t)), h * (0.28f + 0.05f * cos(t)), w * 0.65f, blue, 0.10f)
            orb(w * (0.80f + 0.08f * cos(t * 1f)), h * (0.55f + 0.06f * sin(t)), w * 0.60f, orange, 0.07f)
            orb(w * (0.45f + 0.10f * sin(t + 2f)), h * (0.85f + 0.04f * cos(t)), w * 0.70f, gold, 0.09f)
            // Slow neon scan line sweeping down the screen.
            val scanY = h * ((phase * 2f) % 1f)
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, gold.copy(alpha = 0.05f), Color.Transparent),
                    startY = scanY - h * 0.08f, endY = scanY + h * 0.08f
                ),
                Offset(0f, scanY - h * 0.08f), Size(w, h * 0.16f)
            )
            for ((i, sym) in splashSymbols.withIndex()) {
                val prog = (phase * sym.speed + sym.offset) % 1f
                val y = h * (1.08f - prog * 1.2f)
                val x = w * sym.x + sin(t * 2f + i) * 10f
                val fade = (1f - kotlin.math.abs(prog - 0.5f) * 2f).coerceIn(0f, 1f)
                drawPsSymbol(
                    sym.kind, Offset(x, y), sym.size * density,
                    symbolColors[sym.kind].copy(alpha = 0.16f * fade), phase * 360f * sym.spin
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // The logo is a transparent PNG: it floats directly on the scene
            // (no card, no crop) with a pulsing neon glow behind it.
            Image(
                painter = painterResource(R.drawable.psx_splash_logo),
                contentDescription = "ZGames PSX",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .aspectRatio(250f / 272f)
                    .graphicsLayer {
                        alpha = reveal.value
                        val sc = 0.72f + 0.28f * reveal.value
                        scaleX = sc
                        scaleY = sc
                        translationY = (glow - 0.72f) * 14.dp.toPx()
                    }
                    .drawBehind {
                        val r = size.maxDimension * 0.78f
                        drawCircle(
                            Brush.radialGradient(
                                listOf(
                                    gold.copy(alpha = 0.26f * glow),
                                    orange.copy(alpha = 0.08f * glow),
                                    Color.Transparent
                                ),
                                center, r
                            ),
                            r
                        )
                    }
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "YOUR GAMES  •  ONE LIBRARY",
                modifier = Modifier.graphicsLayer {
                    alpha = tagline.value
                    translationY = (1f - tagline.value) * 18.dp.toPx()
                },
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(gold, orange, blue)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF192033))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(gold, orange, blue)))
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = status, color = Color(0xFF9AA6C4), fontSize = 12.sp, letterSpacing = 0.5.sp)
        }
    }
}