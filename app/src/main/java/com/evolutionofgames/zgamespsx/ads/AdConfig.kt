package com.evolutionofgames.zgamespsx.ads

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every ad setting in one place, controllable from Firebase Remote Config.
 *
 * HOW IT WORKS
 *  - [snapshot] holds the current values. It starts with the built-in
 *    defaults below (Google test units in debug; configured units in release), so the app works with no Firebase.
 *  - [init] (called from Application.onCreate) fetches Remote Config when
 *    Firebase is set up (app/google-services.json present) and publishes a
 *    new snapshot; the UI collects [snapshot], so banners/native react live.
 *  - Nothing here can crash: every Remote Config call is guarded.
 *
 * REMOTE CONFIG KEYS (create these in Firebase console -> Remote Config)
 *   ads_enabled                     bool   master switch for ALL ads            (true)
 *   ad_interstitial_unit            string AdMob interstitial unit id
 *   ad_rewarded_unit                string AdMob rewarded unit id
 *   ad_app_open_unit                string AdMob app-open unit id
 *   ad_banner_unit                  string AdMob banner unit id
 *   ad_native_unit                  string AdMob native unit id
 *   interstitial_enabled            bool   timer interstitials on/off           (true)
 *   interstitial_interval_sec       long   seconds between interstitials        (180)
 *   interstitial_on_launch          bool   interstitial when pressing Play      (false)
 *   interstitial_on_exit            bool   interstitial when leaving a game      (true)
 *   app_open_enabled                bool   app-open ad on/off                   (true)
 *   app_open_min_gap_min            long   minutes between app-open ads         (240)
 *   app_open_background_sec         long   seconds in background before it      (60)
 *   rewarded_before_download        bool   rewarded ad before a download starts (true)
 *   rewarded_halfway_gate           bool   pause at 50% + rewarded ad to continue (true)
 *   banner_type                     string "banner" or "native" for banner slots ("banner")
 *   banner_collapsible              bool   collapsible (expandable) banner      (true)
 *   banner_large                    bool   LARGE anchored adaptive banner (taller, video-ready; up to
 *                                          150dp / 20% of the screen)         (true)
 *   banner_refresh_sec              long   re-request banner every N sec, 0=off (0)
 *   banner_detail_enabled           bool   banner on game detail                (true)
 *   banner_downloads_enabled        bool   banner on Downloads                  (true)
 *   banner_games_enabled            bool   banner on My Games                   (true)
 *   banner_home_enabled             bool   banner on Home                       (false)
 *   native_feed_enabled             bool   native ads inside the game grid      (true)
 *   native_feed_every_n_games       long   one native ad every N games          (8)
 *
 * NOT remote-controllable: the AdMob APP ID. Google's SDK reads it from the
 * manifest at process start (build.gradle.kts -> manifestPlaceholders
 * ["admobAppId"]); it can only be changed by shipping an app update.
 */
object AdConfig {

    const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    // Google's native ADVANCED VIDEO test unit: serves image AND video creatives
    // (video creatives often earn a higher eCPM than static ones).
    const val TEST_NATIVE = "ca-app-pub-3940256099942544/1044960115"
    const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
    // Google's ADAPTIVE banner test unit (the old fixed 320x50/468x60 unit
    // can't show an anchored-adaptive / collapsible banner properly).
    const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"

    /** projectId of the inert FirebaseApp created when there is no google-services.json. */
    const val PLACEHOLDER_PROJECT = "zgames-local-placeholder"
    const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"

    enum class Placement { DETAIL, DOWNLOADS, GAMES, HOME }

    data class Snapshot(
        val adsEnabled: Boolean = true,
        val interstitialUnit: String = if (com.evolutionofgames.zgamespsx.BuildConfig.DEBUG) TEST_INTERSTITIAL else "ca-app-pub-5936725114299330/1562867136",
        val rewardedUnit: String = if (com.evolutionofgames.zgamespsx.BuildConfig.DEBUG) TEST_REWARDED else "ca-app-pub-5936725114299330/1064173687",
        val appOpenUnit: String = if (com.evolutionofgames.zgamespsx.BuildConfig.DEBUG) TEST_APP_OPEN else "ca-app-pub-5436837284789675/2705331523",
        val bannerUnit: String = if (com.evolutionofgames.zgamespsx.BuildConfig.DEBUG) TEST_BANNER else "ca-app-pub-5936725114299330/6727563902",
        val nativeUnit: String = if (com.evolutionofgames.zgamespsx.BuildConfig.DEBUG) TEST_NATIVE else "ca-app-pub-5936725114299330/6400260841",
        val interstitialEnabled: Boolean = true,
        val interstitialIntervalMs: Long = 180_000L,
        val interstitialOnLaunch: Boolean = false,
        val interstitialOnExit: Boolean = true,
        val appOpenEnabled: Boolean = true,
        /** Show one app-open ad right after the splash screen on every cold start. */
        val appOpenOnLaunch: Boolean = true,
        val appOpenMinGapMs: Long = 4 * 60 * 60 * 1000L,
        val appOpenBackgroundMs: Long = 60_000L,
        val rewardedBeforeDownload: Boolean = true,
        val rewardedHalfwayGate: Boolean = true,
        val bannerAsNative: Boolean = false,
        val bannerCollapsible: Boolean = true,
        val bannerLarge: Boolean = true,
        val bannerRefreshSec: Long = 0L,
        val postSmallEnabled: Boolean = false,
        val postLargeEnabled: Boolean = false,
        val postAdPosition: String = "after",
        val postAdType: String = "banner",
        val bannerDetail: Boolean = true,
        val bannerDownloads: Boolean = true,
        val bannerGames: Boolean = true,
        val bannerHome: Boolean = false,
        val nativeFeedEnabled: Boolean = true,
        val feedBannerEnabled: Boolean = false,
        val feedHomeEnabled: Boolean = true,
        val feedSearchEnabled: Boolean = true,
        val diagnosticsEnabled: Boolean = true,
        val nativeFeedEveryN: Int = 8
    ) {
        fun bannerFor(p: Placement): Boolean = adsEnabled && when (p) {
            Placement.DETAIL -> bannerDetail
            Placement.DOWNLOADS -> bannerDownloads
            Placement.GAMES -> bannerGames
            Placement.HOME -> bannerHome
        }
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Current values (use from non-Compose code). */
    val current: Snapshot get() = _snapshot.value

    @Volatile private var remote: FirebaseRemoteConfig? = null

    /**
     * Values pushed by the ZGames WordPress plugin (see remote/WpConfig).
     * They take priority over Firebase Remote Config, which takes priority
     * over the built-in defaults. Same key names as the list above.
     */
    @Volatile private var wp: org.json.JSONObject? = null

    /** Called by WpConfig after a fetch (or when the cached copy is loaded). */
    fun applyWordPress(json: org.json.JSONObject?) {
        wp = json
        try { publish() } catch (t: Throwable) { Log.e("AdConfig", "publish(wp) failed", t) }
    }

    fun init(context: Context) {
        try {
            val app = context.applicationContext
            if (FirebaseApp.getApps(app).isEmpty() ||
                FirebaseApp.getInstance().options.projectId == PLACEHOLDER_PROJECT
            ) {
                Log.i("AdConfig", "Firebase not configured (no google-services.json): using defaults + WordPress")
                return
            }
            val rc = FirebaseRemoteConfig.getInstance()
            remote = rc
            rc.setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(900)
                    .build()
            )
            // Values cached from a previous run apply immediately.
            publish()
            rc.fetchAndActivate().addOnCompleteListener {
                try { publish() } catch (t: Throwable) { Log.e("AdConfig", "publish failed", t) }
            }
        } catch (t: Throwable) {
            Log.e("AdConfig", "Remote Config init failed; defaults stay active", t)
        }
    }

    private fun publish() {
        val rc = remote
        val w = wp
        fun str(key: String, def: String): String {
            try { if (w != null && w.has(key) && !w.isNull(key)) return w.optString(key, def).trim().ifEmpty { def } } catch (_: Throwable) { }
            if (rc == null) return def
            return try {
                val v = rc.getValue(key)
                if (v.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) v.asString().trim().ifEmpty { def } else def
            } catch (_: Throwable) { def }
        }
        fun bool(key: String, def: Boolean): Boolean {
            try { if (w != null && w.has(key) && !w.isNull(key)) return w.optBoolean(key, def) } catch (_: Throwable) { }
            if (rc == null) return def
            return try {
                val v = rc.getValue(key)
                if (v.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) v.asBoolean() else def
            } catch (_: Throwable) { def }
        }
        fun long(key: String, def: Long): Long {
            try { if (w != null && w.has(key) && !w.isNull(key)) return w.optLong(key, def) } catch (_: Throwable) { }
            if (rc == null) return def
            return try {
                val v = rc.getValue(key)
                if (v.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) v.asLong() else def
            } catch (_: Throwable) { def }
        }

        val d = Snapshot()
        val resolved = Snapshot(
            adsEnabled = bool("ads_enabled", d.adsEnabled),
            interstitialUnit = str("ad_interstitial_unit", d.interstitialUnit),
            rewardedUnit = str("ad_rewarded_unit", d.rewardedUnit),
            appOpenUnit = str("ad_app_open_unit", d.appOpenUnit),
            bannerUnit = str("ad_banner_unit", d.bannerUnit),
            nativeUnit = str("ad_native_unit", d.nativeUnit),
            interstitialEnabled = bool("interstitial_enabled", d.interstitialEnabled),
            // Floors protect against a typo in the console hammering users.
            interstitialOnLaunch = bool("interstitial_on_launch", d.interstitialOnLaunch),
            interstitialOnExit = bool("interstitial_on_exit", d.interstitialOnExit),
            interstitialIntervalMs = long("interstitial_interval_sec", d.interstitialIntervalMs / 1000).coerceAtLeast(20L) * 1000L,
            appOpenEnabled = bool("app_open_enabled", d.appOpenEnabled),
            appOpenOnLaunch = bool("app_open_on_launch", d.appOpenOnLaunch),
            appOpenMinGapMs = long("app_open_min_gap_min", d.appOpenMinGapMs / 60_000).coerceAtLeast(1L) * 60_000L,
            appOpenBackgroundMs = long("app_open_background_sec", d.appOpenBackgroundMs / 1000).coerceAtLeast(5L) * 1000L,
            rewardedBeforeDownload = bool("rewarded_before_download", d.rewardedBeforeDownload),
            rewardedHalfwayGate = bool("rewarded_halfway_gate", d.rewardedHalfwayGate),
            bannerAsNative = str("banner_type", "banner").equals("native", ignoreCase = true),
            bannerCollapsible = bool("banner_collapsible", d.bannerCollapsible),
            bannerLarge = bool("banner_large", d.bannerLarge),
            bannerRefreshSec = long("banner_refresh_sec", d.bannerRefreshSec).let { if (it in 1..29) 30L else it.coerceAtLeast(0L) },
            postSmallEnabled = bool("post_small_enabled", d.postSmallEnabled),
            postLargeEnabled = bool("post_large_enabled", d.postLargeEnabled),
            postAdPosition = str("post_ad_position", d.postAdPosition).takeIf { it == "before" || it == "after" } ?: "after",
            postAdType = str("post_ad_type", d.postAdType).takeIf { it == "banner" || it == "native" } ?: "banner",
            bannerDetail = bool("banner_detail_enabled", d.bannerDetail),
            bannerDownloads = bool("banner_downloads_enabled", d.bannerDownloads),
            bannerGames = bool("banner_games_enabled", d.bannerGames),
            bannerHome = bool("banner_home_enabled", d.bannerHome),
            nativeFeedEnabled = bool("native_feed_enabled", d.nativeFeedEnabled),
            feedBannerEnabled = bool("feed_banner_enabled", d.feedBannerEnabled),
            feedHomeEnabled = bool("feed_home_enabled", d.feedHomeEnabled),
            feedSearchEnabled = bool("feed_search_enabled", d.feedSearchEnabled),
            diagnosticsEnabled = bool("ad_diagnostics_enabled", d.diagnosticsEnabled),
            nativeFeedEveryN = long("native_feed_every_n_games", d.nativeFeedEveryN.toLong()).coerceIn(4L, 40L).toInt()
        )
        _snapshot.value = if (com.evolutionofgames.zgamespsx.BuildConfig.DEBUG) resolved.copy(
            interstitialUnit = TEST_INTERSTITIAL, rewardedUnit = TEST_REWARDED, appOpenUnit = TEST_APP_OPEN,
            bannerUnit = TEST_BANNER, nativeUnit = TEST_NATIVE
        ) else resolved
    }
}
