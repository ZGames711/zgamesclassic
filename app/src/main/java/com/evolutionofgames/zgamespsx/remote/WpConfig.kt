package com.evolutionofgames.zgamespsx.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.evolutionofgames.zgamespsx.ads.AdConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The always-connected link to the "ZGames PSX Controller" WordPress plugin.
 *
 * HOW IT STAYS CONNECTED WHEN YOU UPDATE THE PLUGIN
 *  - The endpoint (/wp-json/zgpsx/v1/config) and every key already published
 *    never change meaning. New plugin versions only ADD keys.
 *  - The app reads only what it understands and ignores the rest (unknown
 *    block types, new fields, new actions), so an old app never breaks on a
 *    new plugin, and a new plugin feature shows up in old apps as soon as the
 *    app can render it (banners, cards, pages, menu rows, switches, theme...).
 *  - The last good copy is cached; a failed fetch never changes what is shown.
 *  - If the pretty REST URL is blocked, a second URL (?rest_route=) is tried.
 *  - The plugin pings devices with a silent push when settings change, so open
 *    or backgrounded apps re-sync within seconds (and on every resume / timer).
 *
 *  Everything fails soft: the app never depends on the site being up.
 */
object WpConfig {

    /** Change this if the plugin lives on another site. */
    const val SITE = "https://evolutionofgames.com"
    const val CONFIG_URL = "$SITE/wp-json/zgpsx/v1/config"

    /** Highest config schema this app build understands (sent to the plugin). */
    private const val APP_SCHEMA = 2

    enum class Kind { MAINTENANCE, UPDATE, ANNOUNCEMENT }

    data class Notice(
        val kind: Kind,
        val id: String,
        val title: String,
        val message: String,
        val url: String?,
        /** Blocks the app (maintenance / mandatory update): no dismiss. */
        val force: Boolean
    )

    const val EXTRA_SCREEN = "zg_screen"
    const val EXTRA_PAGE = "zg_page"

    private const val PREFS = "psx_remote"
    private const val KEY_JSON = "config_json"
    private const val KEY_DISMISSED = "dismissed_ids"
    private const val KEY_DISMISSED_BLOCKS = "dismissed_blocks"
    private const val MIN_RETRY_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _content = MutableStateFlow(RemoteContent.EMPTY)
    /** Banners, pages, menu rows, switches and theme from the plugin. */
    val content: StateFlow<RemoteContent> = _content.asStateFlow()

    private val _openPage = MutableStateFlow<String?>(null)
    /** Id of the in-app page that should be on screen (null = none). */
    val openPageId: StateFlow<String?> = _openPage.asStateFlow()

    private val _pendingScreen = MutableStateFlow<String?>(null)
    /** A screen route ("downloads", "games"...) that MainActivity should navigate to. */
    val pendingScreen: StateFlow<String?> = _pendingScreen.asStateFlow()

    private val _dismissedBlocks = MutableStateFlow<Set<String>>(emptySet())
    val dismissedBlocks: StateFlow<Set<String>> = _dismissedBlocks.asStateFlow()

    private val _light = MutableStateFlow(false)
    val light: StateFlow<Boolean> = _light.asStateFlow()
    fun setLight(ctx: Context, enabled: Boolean) { _light.value = enabled; sp(ctx).edit().putBoolean("light_mode", enabled).apply() }

    private val _theme = MutableStateFlow("")
    /** The design the user picked in Settings ("" = whatever the site's default is). */
    val selectedTheme: StateFlow<String> = _theme.asStateFlow()

    fun selectTheme(ctx: Context, id: String) {
        _theme.value = id
        try { sp(ctx).edit().putString("theme_choice", id).apply() } catch (_: Throwable) { }
    }

    @Volatile private var lastFetchMs = 0L
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var fetching = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // The zgamesandroid firewall 403s requests that carry Accept-Encoding.
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Accept-Encoding").build())
            }
            .build()
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Feature switch from the plugin. Safe from anywhere; falls back to [default] until the plugin answers. */
    fun flag(key: String, default: Boolean): Boolean = _content.value.flag(key, default)

    /** Application.onCreate: apply the cached copy at once, then refresh in the background. */
    fun init(ctx: Context) {
        try {
            _light.value = sp(ctx).getBoolean("light_mode", false)
            _theme.value = sp(ctx).getString("theme_choice", "").orEmpty()
            _dismissedBlocks.value = sp(ctx).getStringSet(KEY_DISMISSED_BLOCKS, emptySet())?.toSet() ?: emptySet()
            sp(ctx).getString(KEY_JSON, null)?.let { applyJson(ctx, JSONObject(it)) }
        } catch (t: Throwable) {
            Log.w("WpConfig", "cached config unreadable", t)
        }
        refresh(ctx, force = true)
    }

    /** Live connection state, shown in Settings so a broken link is visible, not silent. */
    data class Status(
        val lastOkMs: Long = 0L,
        val lastError: String = "",
        val pluginVersion: String = "",
        val via: String = "",
        val blocks: Int = 0,
        val syncing: Boolean = false
    )
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var foreground = false
    @Volatile private var loopStarted = false

    /** MainActivity: true in onResume, false in onPause. While true the config re-syncs on the plugin's interval. */
    fun setForeground(ctx: Context, on: Boolean) {
        foreground = on
        if (on) {
            refresh(ctx, force = true)
            if (!loopStarted) {
                loopStarted = true
                val app = ctx.applicationContext
                scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(maxOf(60, _content.value.refreshSec) * 1000L)
                        if (foreground) refresh(app, force = true)
                    }
                }
            }
        }
    }

    /**
     * Fetches the config now (at most once per 30 s so a failing site is never hammered).
     * Called on start, on every resume, on a timer while the app is open and from the
     * background worker, so a change saved in the plugin shows up within a minute.
     */
    fun refresh(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (fetching) return
        if (now - lastAttemptMs < MIN_RETRY_MS) return
        if (!force && now - lastFetchMs < _content.value.refreshSec * 1000L) return
        fetching = true
        lastAttemptMs = now
        _status.value = _status.value.copy(syncing = true)
        val app = ctx.applicationContext
        scope.launch {
            try {
                fetchOnce(app)
            } catch (t: Throwable) {
                Log.w("WpConfig", "config fetch failed: ${t.message}")
                _status.value = _status.value.copy(lastError = t.message ?: "unknown error")
            } finally {
                fetching = false
                _status.value = _status.value.copy(syncing = false)
            }
        }
    }

    /**
     * Every way into the plugin, most specific first. If one is blocked (firewall,
     * security plugin, cache, permalinks) the next one is tried, so the link survives.
     */
    private fun candidates(app: Context): List<Pair<String, HttpUrl>> {
        val code = versionCode(app).toString()
        val nc = (System.currentTimeMillis() / 30_000L).toString() // defeats CDN / page caches
        fun HttpUrl.Builder.p() = addQueryParameter("app", code)
            .addQueryParameter("schema", APP_SCHEMA.toString())
            .addQueryParameter("nc", nc)
        val root = "$SITE/".toHttpUrl()
        return listOf(
            "wp-json" to CONFIG_URL.toHttpUrl().newBuilder().p().build(),
            "wp-json (plain)" to CONFIG_URL.toHttpUrl(),
            "rest_route" to root.newBuilder().addQueryParameter("rest_route", "/zgpsx/v1/config").p().build(),
            "zgac" to root.newBuilder().addQueryParameter("zgac", "config").p().build(),
            "zgac (plain)" to root.newBuilder().addQueryParameter("zgac", "config").build()
        )
    }

    private fun fetchOnce(app: Context) {
        val prefs = sp(app)
        val errors = ArrayList<String>()
        for ((label, url) in candidates(app)) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ZGamesPSX/1.0 Android")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { errors.add("$label: HTTP ${resp.code}"); return@use }
                    val body = resp.body?.string().orEmpty()
                    val json = try { JSONObject(body) } catch (_: Throwable) {
                        errors.add("$label: not JSON (a firewall or cache page?)"); return@use
                    }
                    if (!json.has("ads") && !json.has("blocks") && !json.has("update")) {
                        errors.add("$label: not the plugin's config"); return@use
                    }
                    prefs.edit().putString(KEY_JSON, body).apply()
                    lastFetchMs = System.currentTimeMillis()
                    applyJson(app, json)
                    _status.value = _status.value.copy(
                        lastOkMs = lastFetchMs,
                        lastError = "",
                        pluginVersion = json.optString("plugin_version"),
                        via = label,
                        blocks = _content.value.blocks.size
                    )
                    return
                }
            } catch (t: Throwable) {
                errors.add("$label: ${t.javaClass.simpleName}")
                Log.w("WpConfig", "fetch via $label failed: ${t.message}")
            }
        }
        _status.value = _status.value.copy(lastError = errors.take(3).joinToString("; "))
    }

    private fun versionCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (_: Throwable) { 0L }

    private fun dismissed(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()

    /** "Later" / "OK" on a dismissible notice: never show that id again. */
    fun dismiss(ctx: Context, n: Notice) {
        if (n.force) return
        try {
            val set = HashSet(dismissed(ctx)); set.add(n.id)
            sp(ctx).edit().putStringSet(KEY_DISMISSED, set).apply()
        } catch (_: Throwable) { }
        _notice.value = null
    }

    /** The user closed a dismissible banner: keep it closed on this phone. */
    fun dismissBlock(ctx: Context, id: String) {
        try {
            val set = HashSet(_dismissedBlocks.value); set.add(id)
            _dismissedBlocks.value = set
            sp(ctx).edit().putStringSet(KEY_DISMISSED_BLOCKS, set).apply()
        } catch (_: Throwable) { }
    }

    // ---- navigation requests (from banners, menu rows and push taps) ----------

    fun openPage(id: String) { _openPage.value = id }
    fun closePage() { _openPage.value = null }
    fun requestScreen(route: String) { _pendingScreen.value = route }
    fun consumeScreen() { _pendingScreen.value = null }

    /** MainActivity calls this with the launch / new intent (e.g. a tapped push). */
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        try {
            intent.getStringExtra(EXTRA_PAGE)?.takeIf { it.isNotBlank() }?.let {
                openPage(it); intent.removeExtra(EXTRA_PAGE)
            }
            intent.getStringExtra(EXTRA_SCREEN)?.takeIf { it.isNotBlank() }?.let {
                requestScreen(it); intent.removeExtra(EXTRA_SCREEN)
            }
        } catch (_: Throwable) { }
    }

    private fun applyJson(ctx: Context, json: JSONObject) {
        AdConfig.applyWordPress(json.optJSONObject("ads"))
        try { com.evolutionofgames.zgamespsx.push.PushManager.onRemoteConfig(ctx, json.optJSONObject("push")) } catch (_: Throwable) { }
        try { _content.value = RemoteContent.parse(json) } catch (t: Throwable) { Log.w("WpConfig", "content parse failed", t) }
        _notice.value = pickNotice(ctx, json)
    }

    private fun pickNotice(ctx: Context, json: JSONObject): Notice? {
        val seen = dismissed(ctx)
        val m = json.optJSONObject("maintenance")
        if (m != null && m.optBoolean("enabled", false)) {
            return Notice(
                Kind.MAINTENANCE, "maintenance", "We'll be right back",
                m.optString("message").ifBlank { "The app is being updated. Please try again in a little while." },
                null, force = true
            )
        }
        val u = json.optJSONObject("update")
        if (u != null) {
            val cur = versionCode(ctx)
            val latest = u.optLong("latest_version_code", 0L)
            val min = u.optLong("min_version_code", 0L)
            val url = u.optString("url").ifBlank { "https://play.google.com/store/apps/details?id=${ctx.packageName}" }
            val msg = u.optString("message")
            if (min > cur) {
                return Notice(
                    Kind.UPDATE, "update-min-$min", "Update required",
                    msg.ifBlank { "This version is no longer supported. Please update to keep playing." },
                    url, force = true
                )
            }
            if (latest > cur && "update-$latest" !in seen) {
                return Notice(
                    Kind.UPDATE, "update-$latest", "Update available",
                    msg.ifBlank { "A new version is ready with fixes and new features." },
                    url, force = false
                )
            }
        }
        val a = json.optJSONObject("announcement")
        if (a != null && a.optBoolean("enabled", false)) {
            val id = "ann-" + a.optString("id", "1")
            if (id !in seen) {
                return Notice(
                    Kind.ANNOUNCEMENT, id,
                    a.optString("title").ifBlank { "News" },
                    a.optString("message"),
                    a.optString("url").ifBlank { null },
                    force = false
                )
            }
        }
        return null
    }
}
