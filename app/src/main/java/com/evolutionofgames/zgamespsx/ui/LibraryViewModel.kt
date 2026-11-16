package com.evolutionofgames.zgamespsx.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evolutionofgames.zgamespsx.ads.AdsEngine
import com.evolutionofgames.zgamespsx.ads.BillingEngine
import com.evolutionofgames.zgamespsx.display.TitleSanitizer
import com.evolutionofgames.zgamespsx.download.DownloadEngine
import com.evolutionofgames.zgamespsx.download.DownloadStatus
import com.evolutionofgames.zgamespsx.emulator.DiscProbe
import com.evolutionofgames.zgamespsx.emulator.MemstickStorage
import com.evolutionofgames.zgamespsx.emulator.PathResolver
import com.evolutionofgames.zgamespsx.feed.DownloadLink
import com.evolutionofgames.zgamespsx.feed.GameCatalog
import com.evolutionofgames.zgamespsx.install.GameLibrary
import com.evolutionofgames.zgamespsx.install.GameChoice
import com.evolutionofgames.zgamespsx.install.InstallRequest
import com.evolutionofgames.zgamespsx.install.InstalledGame
import com.evolutionofgames.zgamespsx.install.ModInstaller
import com.evolutionofgames.zgamespsx.install.libraryKey
import com.evolutionofgames.zgamespsx.download.CapturedDownload
import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.model.SourceSite
import com.evolutionofgames.zgamespsx.prefs.AppPrefs
import com.evolutionofgames.zgamespsx.prefs.FavoritesStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The single ViewModel for the whole app (one activity, shared across
 * screens). It owns the engines and joins them:
 * catalogue <-> downloads <-> installer <-> library <-> launchers.
 *
 * A game is a catalogue [Game] until it is installed; an installed file
 * is an [InstalledGame]. The bridge between them is [installedUriFor]:
 * an explicit map written when an install finishes for a game, plus a
 * normalized-title fallback for files installed outside this flow.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Context = app.applicationContext

    val billing = BillingEngine(appCtx)
    val ads = AdsEngine(appCtx) { billing.isAdFree.value }
    val catalog = GameCatalog()
    val downloads = DownloadEngine.get(appCtx)
    val installer = ModInstaller(appCtx)
    val library = GameLibrary(appCtx)

    /**
     * Cover URLs remembered for installed games. The installed file's title
     * comes from its FILE NAME and rarely equals the catalogue post title,
     * so title matching alone left most rows with no image. A cover is now
     * saved when a download starts and attached to the installed file when
     * its install finishes (by file key), and survives app restarts.
     */
    private val coverPrefs = appCtx.getSharedPreferences("zg_covers", Context.MODE_PRIVATE)
    private fun saveCover(key: String, cover: String?) {
        if (cover.isNullOrBlank()) return
        try { coverPrefs.edit().putString(key, cover).apply() } catch (_: Exception) { }
    }

    private fun savePlatform(key: String, platform: String) {
        try { coverPrefs.edit().putString(key, platform).apply() } catch (_: Exception) { }
    }

    /** Console per installed file (key = library key). Filled on IO; read by the UI. */
    val consoleCache = androidx.compose.runtime.mutableStateMapOf<String, DiscProbe.Console>()

    init {
        // Probe every installed file in the background so Play routing is
        // ready (and never relies on a title guess) by the time it is tapped.
        viewModelScope.launch {
            library.games.collect { list ->
                withContext(Dispatchers.IO) {
                    for (g in list) {
                        if (!consoleCache.containsKey(g.key)) {
                            try { consoleCache[g.key] = consoleForInstalled(g) } catch (_: Exception) { }
                        }
                    }
                }
            }
        }
    }

    // ── catalogue ────────────────────────────────────────────────
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()
    private val gamesById = mutableMapOf<String, Game>()
    private val _loadingFeed = MutableStateFlow(false)
    val loadingFeed: StateFlow<Boolean> = _loadingFeed.asStateFlow()
    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()
    private var feedPage = 1
    private var feedExhausted = false
    /** True while the list on screen is the saved copy from the last online session. */
    private val _feedOffline = MutableStateFlow(false)
    val feedOffline: StateFlow<Boolean> = _feedOffline.asStateFlow()
    private val gson = com.google.gson.Gson()
    private val feedCacheFile = File(appCtx.filesDir, "feed_cache.json")

    private fun saveFeedCache(list: List<Game>) {
        viewModelScope.launch(Dispatchers.IO) {
            try { feedCacheFile.writeText(gson.toJson(list.take(40))) } catch (_: Exception) { }
        }
    }

    private suspend fun readFeedCache(): List<Game> = withContext(Dispatchers.IO) {
        try {
            if (!feedCacheFile.isFile) emptyList()
            else gson.fromJson<List<Game>>(
                feedCacheFile.readText(),
                object : com.google.gson.reflect.TypeToken<List<Game>>() {}.type
            ).orEmpty().filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── favourites / recently played ─────────────────────────────
    private val favoritesStore = FavoritesStore(appCtx)
    val favorites: StateFlow<List<Game>> = favoritesStore.items
    fun isFavorite(id: String): Boolean = favoritesStore.isFavorite(id)
    fun toggleFavorite(game: Game) {
        gamesById[game.id] = game
        val nowFav = favoritesStore.toggle(game)
        _notice.value = null
        toast(if (nowFav) "Saved to Favorites" else "Removed from Favorites")
    }

    private val playPrefs = appCtx.getSharedPreferences("zg_play", Context.MODE_PRIVATE)
    private val _lastPlayed = MutableStateFlow<Map<String, Long>>(
        try { playPrefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap() } catch (_: Exception) { emptyMap() }
    )
    /** Installed-game key -> last time it was started (epoch ms). */
    val lastPlayed: StateFlow<Map<String, Long>> = _lastPlayed.asStateFlow()
    private fun markPlayed(game: InstalledGame) {
        val now = System.currentTimeMillis()
        try { playPrefs.edit().putLong(game.key, now).apply() } catch (_: Exception) { }
        _lastPlayed.value = _lastPlayed.value + (game.key to now)
    }

    // ── search ───────────────────────────────────────────────────
    private val _searchResults = MutableStateFlow<List<Game>>(emptyList())
    val searchResults: StateFlow<List<Game>> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var searchPage = 1
    private var searchExhausted = false
    private var lastQuery = ""

    // ── detail ───────────────────────────────────────────────────
    private val _detailLinks = MutableStateFlow<List<DownloadLink>>(emptyList())
    val detailLinks: StateFlow<List<DownloadLink>> = _detailLinks.asStateFlow()
    private val _detailSlideshow = MutableStateFlow<com.evolutionofgames.zgamespsx.feed.WpSlideshow?>(null)
    val detailSlideshow = _detailSlideshow.asStateFlow()
    private val _detailDescription = MutableStateFlow("")
    val detailDescription = _detailDescription.asStateFlow()
    private val _detailTags = MutableStateFlow<List<String>>(emptyList())
    val detailTags = _detailTags.asStateFlow()
    private val _loadingLinks = MutableStateFlow(false)
    val loadingLinks: StateFlow<Boolean> = _loadingLinks.asStateFlow()
    /** True when the download links could not be fetched (network) — distinct from "no link exists". */
    private val _linksError = MutableStateFlow(false)
    val linksError: StateFlow<Boolean> = _linksError.asStateFlow()

    // ── dialogs / transient UI ───────────────────────────────────
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    /** Title of a download that just started (drives the "Download started" dialog). */
    /** Library keys that existed when the current install began; anything new after it is "just installed". */
    @Volatile private var knownKeys: Set<String> = emptySet()
    private val _justInstalled = MutableStateFlow<InstalledGame?>(null)
    /** The game the last successful install added: drives the Play button in the Installed dialog. */
    val justInstalled: StateFlow<InstalledGame?> = _justInstalled.asStateFlow()
    fun clearJustInstalled() { _justInstalled.value = null }

    private val _downloadStarted = MutableStateFlow<String?>(null)
    val downloadStarted: StateFlow<String?> = _downloadStarted.asStateFlow()
    fun dismissDownloadStarted() { _downloadStarted.value = null }
    /** True when no PS1 emulator is installed: MainActivity shows the "Get the emulator" dialog. */
    private val _needEmulator = MutableStateFlow(false)
    val needEmulator: StateFlow<Boolean> = _needEmulator.asStateFlow()
    fun dismissNeedEmulator() { _needEmulator.value = false }
    fun openEmulatorStore() {
        _needEmulator.value = false
        com.evolutionofgames.zgamespsx.emulator.Ps1Launcher.openEmulatorStore(appCtx)
    }
    fun isEmulatorInstalled(): Boolean = com.evolutionofgames.zgamespsx.emulator.Ps1Launcher.installedRetroArch(appCtx) != null



    /** Catalogue game id -> installed file uri (written when an install finishes for that game). */
    private val installedPrefs = appCtx.getSharedPreferences("zg_installed_map", Context.MODE_PRIVATE)
    private val installedByGame = mutableMapOf<String, String>().apply {
        try {
            for ((k, v) in installedPrefs.all) if (v is String) put(k, v)
        } catch (_: Exception) { }
    }
    private fun setInstalledMapping(gameId: String, uri: String) {
        installedByGame[gameId] = uri
        try { installedPrefs.edit().putString(gameId, uri).apply() } catch (_: Exception) { }
    }
    private val _installedByGameVersion = MutableStateFlow(0)
    val installedByGameVersion: StateFlow<Int> = _installedByGameVersion.asStateFlow()

    /** Download id -> catalogue game id (so completion can auto-install). */
    private val downloadGamePrefs = appCtx.getSharedPreferences("download_game_map", Context.MODE_PRIVATE)
    private val downloadGame = mutableMapOf<Long, String>().apply {
        downloadGamePrefs.all.forEach { (key, value) -> if (key.toLongOrNull() != null && value is String) put(key.toLong(), value) }
    }
    private fun rememberDownloadGame(id: Long, gameId: String) {
        downloadGame[id] = gameId
        downloadGamePrefs.edit().putString(id.toString(), gameId).commit()
    }
    private fun forgetDownloadGame(id: Long) {
        downloadGame.remove(id); downloadGamePrefs.edit().remove(id.toString()).apply()
    }
    private val autoInstalledDownloads = mutableSetOf<Long>()
    /**
     * Download id -> installed file uri, recorded when an install that
     * was started from a Downloads row finishes. This is what lets a
     * completed row show Play even when the catalogue-title fallback
     * cannot match (download titles and installed file titles differ,
     * e.g. "New Dragon Ball Z Shin Budokai 2 Power Up Sparking Zero
     * Mod" vs the installed "DBS Budokai Power Up Mod"). Without this
     * map, a completed-but-not-installed row was a dead end: no Play,
     * no Install, only Remove.
     */
    private val downloadInstalledUri = mutableMapOf<Long, String>()

    // ── 50% rewarded gate (ZGamez's halfway dialog) ───────────────
    /** Download ids that already showed the 50% dialog — once per download, survives navigation. */
    private val halfwayShownIds = mutableSetOf<Long>()
    /** Id of the download currently paused at 50% with its dialog up (null = none). */
    private val _halfwayDialogId = MutableStateFlow<Long?>(null)
    val halfwayDialogId: StateFlow<Long?> = _halfwayDialogId.asStateFlow()
    fun wasHalfwayShown(id: Long): Boolean = id in halfwayShownIds
    fun markHalfwayShown(id: Long) { halfwayShownIds.add(id) }
    fun showHalfwayDialog(id: Long) { _halfwayDialogId.value = id }
    fun dismissHalfwayDialog() { _halfwayDialogId.value = null }

    // Download ad pause: controlled from the plugin ("Session & ads" > Download ad pause).
    // Pauses a running download once at the configured percentage, then the dialog offers a
    // rewarded ad (or Premium). Off by default; never for ad-free players.
    private fun startHalfwayWatcher() {
        viewModelScope.launch {
            downloads.items.collect { list ->
                try {
                    val c = com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.session
                    val adCfg = com.evolutionofgames.zgamespsx.ads.AdConfig.current
                    if (!c.halfwayPause || !adCfg.rewardedHalfwayGate || !adCfg.adsEnabled || billing.isAdFree.value || _halfwayDialogId.value != null) return@collect
                    val hit = list.firstOrNull {
                        it.status == DownloadStatus.RUNNING && it.progressPercent >= c.halfwayPercent && !wasHalfwayShown(it.id)
                    } ?: return@collect
                    markHalfwayShown(hit.id)
                    downloads.pauseDownload(hit.id)
                    showHalfwayDialog(hit.id)
                    com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("halfway_shown")
                } catch (_: Throwable) { }
            }
        }
    }

    /** Watch Ad pressed: resume on reward; if no ad is ready resume only when the plugin allows it. */
    fun continueAfterHalfway(activity: android.app.Activity) {
        val id = _halfwayDialogId.value ?: return
        val freeOk = com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.session.halfwayFreeOk
        val resume = { downloads.resumeDownload(id); dismissHalfwayDialog(); com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("halfway_resumed") }
        ads.showRewarded(activity, onRewarded = { resume() }, onDismissed = { }, onNotAvailable = { if (freeOk) resume() })
    }

    // ── WebView file-page resolver ───────────────────────────────
    /** One pending resolver request (shown as a full-screen WebView by MainActivity). */
    data class ResolverRequest(val pageUrl: String, val gameTitle: String, val gameId: String?, val seq: Long, val expectedSha256: String? = null)
    private val _resolverRequest = MutableStateFlow<ResolverRequest?>(null)
    val resolverRequest: StateFlow<ResolverRequest?> = _resolverRequest.asStateFlow()
    private var resolverSeq = 0L
    /** Download ids already given the HTML-fallback resolver (never twice, never in a loop). */
    private val resolverFallbackDone = mutableSetOf<Long>()
    /** Download ids that already came out of the resolver (their HTML failures must NOT re-open it). */
    private val resolverDownloadIds = mutableSetOf<Long>()

    fun requestResolver(pageUrl: String, gameTitle: String, gameId: String?, expectedSha256: String? = null) {
        // Same page already open: don't stack a duplicate request.
        if (_resolverRequest.value?.pageUrl == pageUrl) return
        _resolverRequest.value = ResolverRequest(pageUrl, gameTitle, gameId, ++resolverSeq, expectedSha256)
    }

    fun clearResolver() { _resolverRequest.value = null }

    /** True for a MediaFire file-page URL (host mediafire.com, first path segment "file"). */
    fun isMediaFireFilePage(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            host.contains("mediafire.com") && uri.pathSegments.firstOrNull() == "file"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Hosts that only redirect (URL shorteners): the real destination — and therefore
     * the correct routing (resolver vs direct) — is only visible after following the
     * redirect. A post's download button can point at one of these: evo post 11368's
     * button is a bit.ly link that 301s to a MediaFire file page. Routing on the short
     * URL sent it down the direct path, OkHttp followed the redirect into the MediaFire
     * HTML page, and the HTML guard failed it — while the resolver trigger (a
     * mediafire.com host) never saw the link at all.
     */
    private val shortenerHosts = setOf(
        "bit.ly", "j.mp", "tinyurl.com", "t.co", "goo.gl", "is.gd", "shorturl.at",
        "rb.gy", "cutt.ly", "rebrand.ly", "tiny.cc", "ow.ly", "buff.ly", "s.id", "bit.do", "v.gd"
    )

    private fun isShortLink(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            host in shortenerHosts || shortenerHosts.any { host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Follows a short link's redirect chain with a HEAD request (no body is ever
     * downloaded) and returns the final URL. Returns the original URL on any failure
     * (HEAD blocked, timeout, non-2xx) so routing falls back to the previous behavior
     * instead of breaking. Only called for known shortener hosts — every other link is
     * routed untouched, so proven direct/Drive/MediaFire behavior cannot regress.
     */
    private suspend fun expandUrl(url: String): String {
        if (!isShortLink(url)) return url
        return try {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .head()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                    )
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) url else resp.request.url.toString()
                }
            }
        } catch (_: Exception) {
            url
        }
    }

    init {
        startHalfwayWatcher()
        knownKeys = library.games.value.map { it.key }.toSet()
        // Snapshot the library when an install starts working, so the game it
        // adds can be told apart from older ones (no guessing from titles).
        viewModelScope.launch {
            var wasWorking = false
            installer.state.collect { st ->
                val working = st is com.evolutionofgames.zgamespsx.install.InstallState.Working
                if (working && !wasWorking) knownKeys = library.games.value.map { it.key }.toSet()
                wasWorking = working
            }
        }
        installer.suggestionProvider = {
            library.games.value.mapNotNull { g ->
                g.gameId?.let { GameChoice(g.title, it) }
            }
        }
        viewModelScope.launch {
            // Refresh the library when any install finishes, and map a
            // game-initiated install to the newly installed file so its
            // Detail screen can offer Play. Guarded per event: an
            // uncaught throw here would kill this collector (and, via
            // the default handler, crash the app) right after an
            // install finishes — a failure must never do that.
            installer.events.collect { ev ->
                try {
                    if (ev is com.evolutionofgames.zgamespsx.install.InstallEvent.Finished) {
                        if (ev.success) {
                            library.refresh().join()
                            val fresh = library.games.value.filter { it.key !in knownKeys }
                            val freshNewest = fresh.maxByOrNull { it.addedAtMs }
                            knownKeys = library.games.value.map { it.key }.toSet()
                            val newestUriNow = freshNewest?.uri ?: findNewestInstalledUri()
                            _justInstalled.value = freshNewest
                            try {
                                val gid = if (ev.request.key.startsWith("game:")) ev.request.key.removePrefix("game:")
                                else ev.request.downloadId?.let { downloadGame[it] }
                                val newest = newestUriNow
                                val cover = gid?.let { coverPrefs.getString("gid:$it", null) }
                                if (newest != null && cover != null) saveCover("uri:${libraryKey(newest)}", cover)
                                val plat = gid?.let { coverPrefs.getString("plat:$it", null) }
                                if (newest != null && plat != null) savePlatform("platuri:${libraryKey(newest)}", plat)
                            } catch (_: Exception) { }
                            if (ev.request.key.startsWith("game:")) {
                                val gameId = ev.request.key.removePrefix("game:")
                                val uri = newestUriNow
                                if (uri != null) {
                                    setInstalledMapping(gameId, uri)
                                    _installedByGameVersion.value++
                                }
                            }
                            // Install started from a Downloads row
                            // (Install button recovery path): map the
                            // download id to the newly installed file
                            // so its row gains a Play button.
                            val downloadId = ev.request.downloadId
                            if (downloadId != null) downloadGamePrefs.edit().remove(downloadId.toString()).apply()
                            if (downloadId != null) {
                                val uri = newestUriNow
                                if (uri != null) {
                                    downloadInstalledUri[downloadId] = uri
                                    _installedByGameVersion.value++
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        viewModelScope.launch {
            downloads.items.collect { items ->
                for (item in items) {
                    // Same guard as above, per item: a single bad
                    // download row (odd URI, odd title) must never kill
                    // the collector — that would silently stop every
                    // later completion from auto-installing.
                    try {
                        handleDownloadItem(item)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        library.refresh()
        loadFeedPage(reset = true)
        ads.prewarm(appCtx)
    }

    /** Per-item body of the downloads collector (extracted so one bad row can't kill it). */
    private fun handleDownloadItem(item: com.evolutionofgames.zgamespsx.download.DownloadItem) {
        // HTML fallback (ZGamez mechanism's backstop): a download that
        // returned a web page instead of a file routes its link to the
        // WebView resolver — once per download, and never for downloads
        // that already came out of the resolver (no loop).
        if (item.status == DownloadStatus.FAILED &&
            item.errorMessage == downloads.htmlPageMessage &&
            item.id !in resolverFallbackDone &&
            item.id !in resolverDownloadIds &&
            _resolverRequest.value?.pageUrl != item.url
        ) {
            resolverFallbackDone.add(item.id)
            requestResolver(item.url, item.title, downloadGame[item.id], item.expectedSha256)
        }
        if (item.status == DownloadStatus.COMPLETED && item.id in downloadGame) {
            val counted = appCtx.getSharedPreferences("counted_downloads", Context.MODE_PRIVATE)
            val key = item.id.toString()
            if (!counted.getBoolean(key, false)) {
                counted.edit().putBoolean(key, true).apply()
                com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.gameDownload(downloadGame[item.id]!!)
            }
        }
        if (item.status == DownloadStatus.COMPLETED && item.id in downloadGame &&
            item.id !in autoInstalledDownloads && AppPrefs.autoInstall(appCtx)
        ) {
            val gameId = downloadGame[item.id]!!
            // NOTE: the attempted-mark used to be set BEFORE these
            // early returns, so one failed attempt (null uri, or a
            // filename that missed the gate) poisoned the download
            // forever: no install, no retry, and (before the Install
            // button) no recovery at all. Mark attempted ONLY when an
            // install is actually enqueued.
            val uri = item.contentUri ?: return
            val fileName = queryDisplayName(appCtx, Uri.parse(uri))
            // ZGamez's GameLibrary AUTO_EXT gate: only game/archive
            // extensions auto-install. Anything else stays a plain
            // completed download (its row still offers Install).
            if (!GameLibrary.isAutoInstallable(fileName ?: item.title)) return
            autoInstalledDownloads.add(item.id)
            // A killed installation is not safely restartable: don't automatically
            // run it twice on next launch, because archives may contain saves.
            downloadGamePrefs.edit().remove(item.id.toString()).commit()
            installer.enqueue(
                InstallRequest(
                    key = "game:$gameId",
                    title = item.title,
                    uri = uri,
                    sizeHint = item.totalBytes,
                    downloadId = item.id,
                    // A fresh, finished download must always install. Without force the installer
                    // silently ignores "game:<id>" once that game was installed before (the key is
                    // persisted), so re-downloading a game never auto-installed again.
                    force = true,
                    fileName = fileName
                )
            )
        }
    }

    // ── feed ─────────────────────────────────────────────────────

    fun loadFeedPage(reset: Boolean = false) {
        if (_loadingFeed.value) return
        if (!reset && feedExhausted) return
        viewModelScope.launch {
            _loadingFeed.value = true
            _feedError.value = null
            try {
                val page = if (reset) 1 else feedPage
                val pageGames = catalog.getGames(page)
                if (reset) {
                    _games.value = pageGames
                    feedPage = 2
                    _feedOffline.value = false
                    if (pageGames.isNotEmpty()) saveFeedCache(pageGames)
                } else {
                    _games.value = (_games.value + pageGames).distinctBy { it.id }
                    feedPage = page + 1
                }
                if (pageGames.isEmpty()) feedExhausted = true
                for (g in pageGames) gamesById[g.id] = g
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    if (_games.value.isEmpty()) {
                        // Offline: show the games saved from the last online session.
                        val cached = readFeedCache()
                        if (cached.isNotEmpty()) {
                            _games.value = cached
                            for (g in cached) gamesById[g.id] = g
                            _feedOffline.value = true
                            feedExhausted = true
                        } else {
                            _feedError.value = "Couldn't load games. Check your connection and retry."
                        }
                    } else {
                        _feedError.value = "Couldn't load games. Check your connection and retry."
                    }
                }
            } finally {
                _loadingFeed.value = false
            }
        }
    }

    fun retryFeed() {
        feedExhausted = false
        if (_feedOffline.value) _games.value = emptyList()
        loadFeedPage(reset = true)
    }

    fun gameById(id: String): Game? {
        return gamesById[id]
    }

    fun cacheGame(game: Game) {
        gamesById[game.id] = game
    }

    /**
     * Finds a game by id even after the process was recreated: memory,
     * then Favorites, then a one-post fetch from the site. Null = gone/offline.
     */
    suspend fun resolveGame(id: String): Game? {
        gamesById[id]?.let { return it }
        favoritesStore.get(id)?.let { gamesById[id] = it; return it }
        val parts = id.split(":")
        if (parts.size != 2) return null
        val site = SourceSite.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: return null
        val postId = parts[1].toLongOrNull() ?: return null
        return try {
            catalog.getGameById(site, postId)?.also { gamesById[id] = it }
        } catch (_: Exception) {
            null
        }
    }

    // ── search ───────────────────────────────────────────────────

    /** Bumped on every new query so a slow, older response can never overwrite newer results. */
    private var searchGen = 0

    fun search(query: String, reset: Boolean = true) {
        if (reset) {
            searchGen++
            lastQuery = query
            searchPage = 1
            searchExhausted = false
            _searching.value = false
            _searchResults.value = emptyList()
        }
        if (query.isBlank() || searchExhausted || _searching.value) return
        val gen = searchGen
        val q = lastQuery
        val page = searchPage
        viewModelScope.launch {
            _searching.value = true
            try {
                val pageGames = catalog.searchGames(q, page)
                if (gen != searchGen) return@launch // superseded by a newer query
                _searchResults.value = (_searchResults.value + pageGames).distinctBy { it.id }
                for (g in pageGames) gamesById[g.id] = g
                if (pageGames.isEmpty()) searchExhausted = true else searchPage++
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException && gen == searchGen) {
                    _notice.value = "Search failed. Check your connection and retry."
                }
            } finally {
                if (gen == searchGen) _searching.value = false
            }
        }
    }

    // ── detail ───────────────────────────────────────────────────

    private var linksGen = 0

    fun loadDetailLinks(game: Game) {
        val gen = ++linksGen
        viewModelScope.launch {
            _loadingLinks.value = true
            _linksError.value = false
            _detailLinks.value = emptyList()
            _detailSlideshow.value = null
            _detailDescription.value = ""
            _detailTags.value = emptyList()
            try {
                val found = catalog.getGameDetails(game)
                if (gen == linksGen) { _detailLinks.value = found.links; _detailSlideshow.value = found.slideshow; _detailDescription.value = found.description; _detailTags.value = found.tags }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException && gen == linksGen) {
                    _detailLinks.value = emptyList()
                    _linksError.value = true
                }
            } finally {
                // Only the newest request may clear the spinner, so opening a
                // second game quickly can't show the first game's links.
                if (gen == linksGen) _loadingLinks.value = false
            }
        }
    }

    /**
     * Downloads the game's link, routed like ZGamez:
     * - Short links are expanded FIRST, and routing runs on the DESTINATION:
     *   a bit.ly link to a MediaFire file page goes to the resolver, a bit.ly
     *   link to Drive keeps the engine's confirm+uuid fast path. Routing on
     *   the short URL used to send every such link down the direct path,
     *   where the HTML guard failed it (evo post 11368, proven live).
     * - MediaFire file-page URLs go DIRECTLY to the WebView resolver
     *   (scraping is proven unworkable — the page is JS-driven). No
     *   download is enqueued yet, so NO "Download started" dialog
     *   shows until the resolver actually captures a file (see
     *   [startCapturedDownload]).
     * - Drive view pages keep the engine's confirm+uuid fast path and
     *   direct file URLs download directly, via [DownloadEngine].
     * - Any other URL that ends as HTML falls back to the resolver
     *   from the downloads collector (see init), not a failure.
     * On a real start it raises the "Download started" dialog (with a
     * Go to Downloads action); completion auto-installs (see init).
     */
    fun downloadGame(game: Game, link: DownloadLink) {
        viewModelScope.launch {
            // Short links hide the real host: expand first, then route on the destination.
            val target = expandUrl(link.url)
            if (isMediaFireFilePage(target)) {
                requestResolver(target, game.title, game.id, link.sha256)
                return@launch
            }
            if (isShortLink(link.url)) {
                // Expansion failed (HEAD blocked, timeout): the WebView follows the
                // redirect itself, so route the original link to the resolver rather
                // than the direct path, where it would hit the HTML guard again.
                requestResolver(link.url, game.title, game.id, link.sha256)
                return@launch
            }
            // Links are mirrors only if an operator supplied the exact same SHA-256 for each.
            val mirrors = link.sha256?.let { hash -> detailLinks.value.filter { it.url != link.url && it.sha256.equals(hash, ignoreCase = true) && it.url.startsWith("https://") }.map { it.url } } ?: emptyList()
            val id = downloads.enqueue(target, game.title, link.sha256, mirrors)
            if (id < 0) {
                _notice.value = "Couldn't start the download for this link."
                return@launch
            }
            rememberDownloadGame(id, game.id)
            saveCover("gid:${game.id}", game.coverUrl)
            savePlatform("plat:${game.id}", game.platform.name)
            savePlatform("plat:title:${normalizeForMatch(game.title)}", game.platform.name)
            saveCover("title:${normalizeForMatch(game.title)}", game.coverUrl)
            com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("download_started")
            _downloadStarted.value = game.title
        }
    }

    /**
     * Resume from a Downloads row. For a row that failed with the HTML-page error, a
     * plain engine resume is a dead loop: the engine reuses the stored (short or page)
     * URL, the HTML guard fails it again, and the one-shot resolver fallback is already
     * consumed — exactly the stuck row in the user's screenshot. Instead, re-route from
     * scratch: expand the link and open the resolver for a MediaFire page (dropping the
     * dead row so the retry replaces it instead of stacking a second row), or reset the
     * engine's stored resolution and resume normally for anything else (Drive fast path
     * / direct file) with the fallback guard cleared so a repeat HTML failure can offer
     * the resolver again. Every other row resumes exactly as before.
     */
    fun resumeDownload(item: com.evolutionofgames.zgamespsx.download.DownloadItem) {
        if (!(item.status == DownloadStatus.FAILED && item.errorMessage == downloads.htmlPageMessage)) {
            downloads.resumeDownload(item.id)
            return
        }
        val gameId = downloadGame[item.id]
        viewModelScope.launch {
            val target = expandUrl(item.url)
            if (isMediaFireFilePage(target)) {
                downloads.removeDownload(item.id)
                forgetDownloadGame(item.id)
                resolverFallbackDone.remove(item.id)
                requestResolver(target, item.title, gameId, item.expectedSha256)
                return@launch
            }
            if (isShortLink(item.url)) {
                // Expansion failed: the WebView follows the redirect itself.
                downloads.removeDownload(item.id)
                forgetDownloadGame(item.id)
                resolverFallbackDone.remove(item.id)
                requestResolver(item.url, item.title, gameId, item.expectedSha256)
                return@launch
            }
            resolverFallbackDone.remove(item.id)
            downloads.resetResolution(item.id)
            downloads.resumeDownload(item.id)
        }
    }

    /**
     * Starts the download a resolver WebView captured, via the
     * WebView enqueue variant (browser UA / cookies / referer). The
     * "Download started" dialog shows ONLY when this returns a real
     * id (>= 0), mirroring ZGamez's InAppBrowser banner rule.
     */
    fun startCapturedDownload(gameTitle: String, gameId: String?, captured: CapturedDownload, expectedSha256: String? = null): Long {
        val id = downloads.enqueueWebView(
            url = captured.url,
            title = gameTitle,
            contentDisposition = captured.contentDisposition,
            mimeType = captured.mimeType,
            userAgent = captured.userAgent,
            cookie = captured.cookie,
            referer = captured.referer,
            expectedSha256 = expectedSha256
        )
        if (id < 0) {
            _notice.value = "Couldn't start the download for this link."
            return id
        }
        resolverDownloadIds.add(id)
        if (gameId != null) {
            rememberDownloadGame(id, gameId)
            gamesById[gameId]?.platform?.let { savePlatform("plat:$gameId", it.name) }
            gamesById[gameId]?.coverUrl?.let {
                saveCover("gid:$gameId", it)
                saveCover("title:${normalizeForMatch(gameTitle)}", it)
            }
        }
        com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("download_started")
        _downloadStarted.value = gameTitle
        return id
    }

    // ── installed <-> catalogue bridge ───────────────────────────

    /** Installed file uri for a catalogue game, if any. */
    fun installedUriFor(game: Game): String? {
        installedByGame[game.id]?.let { uri ->
            val list = library.games.value
            // Ignore a stale mapping (file deleted outside the app) once the library is loaded.
            if (list.isEmpty() || list.any { it.uri == uri }) return uri
        }
        // Fallback: exactly one installed game with a matching normalized title.
        val norm = normalizeForMatch(game.title)
        val hits = library.games.value.filter { normalizeForMatch(it.title) == norm }
        if (hits.size == 1) return hits.single().uri
        return null
    }

    fun installedGameFor(game: Game): InstalledGame? {
        val uri = installedUriFor(game) ?: return null
        return library.games.value.firstOrNull { it.uri == uri }
    }

    /**
     * Installed game for a completed download row, so Downloads can
     * offer Play. Resolution order:
     * 1. The download->installed map (recorded when an install started
     *    from this row finishes — the reliable path).
     * 2. The explicit download->game map (same-process auto-install).
     * 3. The download's REAL filename (from its content URI) matched
     *    against installed titles. This is the fix for the stuck-row
     *    class of bugs: catalogue titles ("New Dragon Ball Z Shin
     *    Budokai 2 Power Up Sparking Zero Mod") and installed file
     *    titles ("DBS Budokai Power Up Mod") never match, so the old
     *    catalogue-title-only fallback returned null even when the
     *    game WAS installed.
     * 4. The catalogue-title single match (legacy fallback).
     * The caller passes the collected installed list so recomposition
     * follows library refreshes.
     */
    fun installedGameForDownload(
        item: com.evolutionofgames.zgamespsx.download.DownloadItem,
        installed: List<InstalledGame>
    ): InstalledGame? {
        downloadInstalledUri[item.id]?.let { uri ->
            installed.firstOrNull { it.uri == uri }?.let { return it }
        }
        val gameId = downloadGame[item.id]
        if (gameId != null) {
            installedByGame[gameId]?.let { uri ->
                installed.firstOrNull { it.uri == uri }?.let { return it }
            }
        }
        // Real-filename match: the file the user downloaded, not the
        // catalogue post title.
        try {
            val contentUri = item.contentUri?.let { Uri.parse(it) }
            val fileName = contentUri?.let { queryDisplayName(appCtx, it) }
            if (!fileName.isNullOrBlank()) {
                val base = fileName.substringBeforeLast('.', fileName)
                val norm = normalizeForMatch(base)
                val hits = installed.filter { normalizeForMatch(it.title) == norm }
                if (hits.size == 1) return hits.single()
            }
        } catch (_: Exception) {
        }
        val norm = normalizeForMatch(item.title)
        val hits = installed.filter { normalizeForMatch(it.title) == norm }
        if (hits.size == 1) return hits.single()
        // Last resort: the installed title sharing the most meaningful words
        // with the download (catalogue and file names rarely match exactly).
        val want = significantTokens(norm)
        if (want.isNotEmpty()) {
            val scored = installed.map { g ->
                val have = significantTokens(normalizeForMatch(g.title))
                val inter = want.intersect(have).size.toDouble()
                val union = want.union(have).size.toDouble().coerceAtLeast(1.0)
                g to inter / union
            }.sortedByDescending { it.second }
            val best = scored.firstOrNull()
            if (best != null && best.second >= 0.5 && (scored.size < 2 || best.second > scored[1].second)) return best.first
        }
        return null
    }

    /**
     * Recovery install for a completed-but-not-installed download row
     * (the Install button). Uses force=true so the installer's silent
     * dedup (keys already in handled/installedKeys) can never turn
     * this into another dead end, and carries downloadId so the
     * Finished handler maps this row to its installed game for Play.
     * The installer still reports a readable error for genuinely
     * un-installable files — force only bypasses the dedup, never the
     * type checks.
     */
    fun installDownload(item: com.evolutionofgames.zgamespsx.download.DownloadItem) {
        val uri = item.contentUri
        if (uri.isNullOrBlank()) {
            _notice.value = "This download's file is no longer available. Download it again."
            return
        }
        val fileName = try {
            queryDisplayName(appCtx, Uri.parse(uri))
        } catch (_: Exception) {
            null
        }
        installer.enqueue(
            InstallRequest(
                key = "download:${item.id}",
                title = item.title,
                uri = uri,
                sizeHint = if (item.totalBytes > 0) item.totalBytes else 0L,
                downloadId = item.id,
                force = true,
                fileName = fileName
            )
        )
    }

    /**
     * Cover for an installed game, best match first:
     *  1. the cover saved for THIS installed file when its install finished;
     *  2. the cover saved for the same normalised title at download time;
     *  3. an exact normalised-title match in the loaded catalogue;
     *  4. a fuzzy match (shared significant words) in the loaded catalogue.
     */
    fun catalogCoverFor(installed: InstalledGame): String? {
        coverPrefs.getString("uri:${installed.key}", null)?.let { return it }
        val norm = normalizeForMatch(installed.title)
        coverPrefs.getString("title:$norm", null)?.let { return it }
        val pool = games.value + gamesById.values
        pool.firstOrNull { normalizeForMatch(it.title) == norm }?.coverUrl?.let { return it }
        val want = significantTokens(norm)
        if (want.size < 2) return null
        var best: Game? = null
        var bestScore = 0.0
        for (g in pool) {
            if (g.coverUrl == null) continue
            val have = significantTokens(normalizeForMatch(g.title))
            if (have.isEmpty()) continue
            val shared = want.intersect(have).size
            if (shared < 2) continue
            val score = shared.toDouble() / minOf(want.size, have.size)
            if (score > bestScore) { bestScore = score; best = g }
        }
        return if (bestScore >= 0.75) best?.coverUrl else null
    }

    private val coverStopWords = setOf(
        "mod", "mods", "download", "iso", "cso", "psp", "ps2", "ppsspp", "for", "android",
        "the", "new", "free", "game", "games", "apk", "full", "version", "latest", "and", "of"
    )

    private fun significantTokens(normalized: String): Set<String> =
        normalized.split(' ').filter { it.length > 1 && it !in coverStopWords }.toSet()

    private fun normalizeForMatch(s: String): String {
        return TitleSanitizer.displayTitle(s).lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun findNewestInstalledUri(): String? {
        return library.games.value.maxByOrNull { it.addedAtMs }?.uri
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── play ─────────────────────────────────────────────────────

    /** Resolves a real file path for an installed game (never copies). */
    fun resolveInstalledPath(game: InstalledGame): String? {
        val modsDir = PathResolver.modsDownloadsDir()
        val mediaStorePath = PathResolver.mediaStorePathFor(appCtx, game.uri)
        val path = PathResolver.resolveLaunchPath(game.uri, game.fileName, modsDir, mediaStorePath)
        if (path == game.uri) return null
        val f = File(path)
        return if (f.isFile) path else null
    }

    /**
     * Picks the console for an installed game, most reliable first:
     *  1. the disc structure itself (PSP_GAME folder => PSP, SYSTEM.CNF =>
     *     PS2), read from the real file or, for content:// files, through
     *     an open file descriptor;
     *  2. the platform the catalogue listed when this file was downloaded;
     *  3. file extension, then (last resort) title words.
     */
    fun consoleForInstalled(game: InstalledGame): DiscProbe.Console {
        val ext = game.fileName.substringAfterLast('.', "").lowercase()
        val path = resolveInstalledPath(game)
        if (path != null) {
            val file = File(path)
            if (file.isFile) DiscProbe.probeProven(file)?.let { return remember(game, it) }
        }
        probeUriProven(game.uri, ext)?.let { return remember(game, it) }
        // The disc itself could not prove it. Strong PSP title words (Tag Team,
        // TTT, Shin Budokai...) beat the catalogue category, which is often
        // wrong for mods listed under PS2; otherwise use what the catalogue said.
        if (DiscProbe.titleSaysPsp(game.title)) return DiscProbe.Console.PSP
        savedPlatformFor(game)?.let { return it }
        if (path != null) {
            val file = File(path)
            if (file.isFile) return DiscProbe.probeConsole(file, game.title)
        }
        return DiscProbe.consoleForTitle(game.title, ext)
    }

    /** A console proven from the disc replaces any wrong platform saved earlier (self-healing). */
    private fun remember(game: InstalledGame, c: DiscProbe.Console): DiscProbe.Console {
        try {
            savePlatform("platuri:${game.key}", c.name)
            savePlatform("plat:title:${normalizeForMatch(game.title)}", c.name)
        } catch (_: Exception) { }
        return c
    }

    private fun probeUriProven(uri: String, ext: String): DiscProbe.Console? {
        if (!uri.startsWith("content://")) return null
        return try {
            appCtx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                DiscProbe.probeProven(File("/proc/self/fd/${pfd.fd}"), ext)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun savedPlatformFor(game: InstalledGame): DiscProbe.Console? {
        val saved = coverPrefs.getString("platuri:${game.key}", null)
            ?: coverPrefs.getString("plat:title:${normalizeForMatch(game.title)}", null)
            ?: return null
        return when (saved) {
            "PS2" -> DiscProbe.Console.PS2
            "PSP" -> DiscProbe.Console.PSP
            else -> null
        }
    }

    /**
     * Plays an installed game from any screen: checks the file, shows the launch interstitial
     * (ad plugin switch), then opens it in the PS1 emulator. When no emulator is installed the
     * player gets a dialog that opens the store page.
     */
    fun playInstalled(activity: Activity, game: InstalledGame) {
        markPlayed(game)
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                com.evolutionofgames.zgamespsx.emulator.Ps1Launcher.play(
                    activity, ads, game,
                    onNeedEmulator = { _needEmulator.value = true },
                    onBlocked = { reason -> _notice.value = reason }
                )
            }
        }
    }

    /** Kept for MainActivity.onResume; nothing is pending with an external emulator. */
    fun consumeReturnNotice() = Unit

    fun dismissNotice() {
        _notice.value = null
    }

    fun toast(msg: String) {
        Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
    }

    // ── manage installed games ───────────────────────────────────

    /** Deletes the game's file(s) from the phone and forgets it everywhere. */
    fun deleteInstalledGame(game: InstalledGame) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { library.delete(game) }
            if (ok) {
                installedByGame.entries.removeAll { it.value == game.uri }
                try {
                    val e = installedPrefs.edit()
                    for ((k, v) in installedPrefs.all) if (v == game.uri) e.remove(k)
                    e.apply()
                    playPrefs.edit().remove(game.key).apply()
                } catch (_: Exception) { }
                _lastPlayed.value = _lastPlayed.value - game.key
                consoleCache.remove(game.key)
                _installedByGameVersion.value++
                library.refresh()
                _notice.value = "Deleted \"${TitleSanitizer.displayTitle(game.title)}\" and freed ${formatBytes(game.sizeBytes)}."
            } else {
                _notice.value = "Couldn't delete that file. It may be in use or already removed."
            }
        }
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1_073_741_824L -> String.format("%.1f GB", b / 1_073_741_824.0)
        b >= 1_048_576L -> String.format("%.0f MB", b / 1_048_576.0)
        else -> "${b / 1024} KB"
    }

    /** Settings: wipes the saved game list, covers and temp files (never the games themselves). */
    fun clearCaches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { feedCacheFile.delete() } catch (_: Exception) { }
                try { appCtx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) { }
            }
            _notice.value = "Cache cleared."
        }
    }

    /** True for a device that probably struggles with PS2 (under ~5.5 GB RAM). */
    fun isLowRamForPs2(): Boolean = try {
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem < 5_500L * 1024 * 1024
    } catch (_: Exception) {
        false
    }

    // ── settings helpers ─────────────────────────────────────────

    fun isMainStorageGranted(): Boolean = MemstickStorage.isMainStorageGranted(appCtx)

    fun openAllFilesAccess(activity: Activity): Boolean =
        MemstickStorage.openAllFilesAccessSettings(activity)

    /** The PSP folder the app actually uses (auto-selected at startup). */
    fun pspFolderPath(): String =
        try {
            MemstickStorage.currentMemstickDir(appCtx).absolutePath
        } catch (_: Exception) {
            ""
        }

    /** True when the user picked a PSP folder manually (advanced override). */
    fun hasSavedPspFolder(): Boolean = installer.hasSavedFolderUri()
}