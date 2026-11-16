package com.evolutionofgames.zgamespsx.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.evolutionofgames.zgamespsx.emulator.LaunchAdGate
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * All ads in the app, behind ONE entitlement gate: when [isAdFree]
 * returns true, every ad path is skipped (no load, no show, no tile).
 * No ad path is left ungated — native tiles, launch interstitials, and
 * app-open ads all check the flag first.
 *
 * Interstitial rules (the proven fixes):
 * - 95-second interval between shows; the timer resets ONLY on a real
 *   show (not on a load, not on a tap).
 * - An ad older than 50 minutes is discarded and reloaded (stale ads
 *   fail to show and waste the slot).
 * - A failed load retries after 15 seconds (bounded, background only).
 * - A launch never waits on an ad: if no fresh ad is ready, the game
 *   launches immediately and a reload is triggered for next time.
 *
 * TEST BUILD units: Google's official test units. Release needs a real
 * AdMob app + units for this package — flagged, never faked.
 */
class AdsEngine(
    context: Context,
    private val isAdFree: () -> Boolean
) : LaunchAdGate {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Entitlement (Ad-Free purchase) OR the remote master switch turns every ad off. */
    private fun adsOff(): Boolean =
        isAdFree() || !AdConfig.current.adsEnabled ||
            !com.evolutionofgames.zgamespsx.consent.ConsentManager.adsAllowed
    private fun interstitialOff(): Boolean = adsOff() || !AdConfig.current.interstitialEnabled
    private fun appOpenOff(): Boolean = adsOff() || !AdConfig.current.appOpenEnabled

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The Google Mobile Ads SDK requires every load() on the MAIN thread
     * (IllegalStateException "#008 Must be called on the main UI thread"
     * otherwise). The interstitial timer runs on Dispatchers.IO, so all
     * four load paths go through this: run now if already on main, else post.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { block() } catch (_: Throwable) { }
        } else {
            mainHandler.post { try { block() } catch (_: Throwable) { } }
        }
    }


    private var interstitial: InterstitialAd? = null
    // Written on AdMob callback threads, read on the IO timer thread:
    // volatile so the timer can never act on a stale timestamp.
    @Volatile private var interstitialLoadedAtMs: Long = 0L
    @Volatile private var lastInterstitialShowMs: Long = 0L
    private var interstitialLoading = AtomicBoolean(false)
    private var interstitialRetryJobStarted = AtomicBoolean(false)

    /**
     * Crash hardening (interstitial-load crash reports): every show path
     * funnels through ONE atomic take + ONE show gate, so two triggers
     * (timer + launch, launch + app-open) can never take the same ad
     * object or stack two fullscreen ads — both are failure/crash-prone
     * in the SDK. All cross-thread flags are volatile/atomic: the timer
     * reads them on IO while callbacks write them on main.
     */
    private val adLock = Any()
    private val consentGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    /** Discard ads requested under an earlier privacy choice. Call on the main thread. */
    fun clearCachedAds() {
        consentGeneration.incrementAndGet()
        synchronized(adLock) { interstitial = null }
        rewardedAd = null
        appOpenAd = null
    }

    /** Atomically takes the loaded interstitial only if fresh; null otherwise. */
    private fun takeInterstitial(): InterstitialAd? {
        synchronized(adLock) {
            val ad = interstitial
            interstitial = null
            if (ad == null) return null
            if (System.currentTimeMillis() - interstitialLoadedAtMs > MAX_AD_AGE_MS) return null
            return ad
        }
    }

    /** Puts a taken-but-unshown ad back for the next attempt. */
    private fun restoreInterstitial(ad: InterstitialAd) {
        synchronized(adLock) {
            if (interstitial == null) interstitial = ad
        }
    }

    private var rewardedAd: RewardedAd? = null
    private var rewardedLoadedAtMs: Long = 0L
    private var rewardedLoading = AtomicBoolean(false)

    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoadedAtMs: Long = 0L
    private var lastAppOpenShowMs: Long = 0L
    private var lastBackgroundMs: Long = 0L

    /** True while any fullscreen ad is on screen — the timer never stacks on top of one. */
    private var isFullscreenAdShowing = AtomicBoolean(false)

    /** Foreground activity for the timer (set on every resume, cleared on pause). */
    private var currentActivityRef: WeakReference<Activity>? = null
    @Volatile private var isForeground = false
    /** True only between onResume and onPause: a show into a merely-created
     * or paused activity is never attempted, whatever the other flags say. */
    @Volatile private var isResumed = false
    private var timerStarted = false

    companion object {
        // All unit ids / intervals now come from AdConfig (Firebase Remote
        // Config with built-in Google TEST defaults). Getters, not consts,
        // so a Remote Config change applies on the next load.
        val INTERSTITIAL_UNIT: String get() = AdConfig.current.interstitialUnit
        val NATIVE_UNIT: String get() = AdConfig.current.nativeUnit
        val APP_OPEN_UNIT: String get() = AdConfig.current.appOpenUnit
        val BANNER_UNIT: String get() = AdConfig.current.bannerUnit
        val REWARDED_UNIT: String get() = AdConfig.current.rewardedUnit

        private val INTERSTITIAL_INTERVAL_MS: Long get() = AdConfig.current.interstitialIntervalMs
        private const val MAX_AD_AGE_MS = 50 * 60 * 1000L
        private const val LOAD_RETRY_DELAY_MS = 15_000L
        private const val REWARDED_WAIT_MS = 9_000L
        private val APP_OPEN_MIN_GAP_MS: Long get() = AdConfig.current.appOpenMinGapMs
        private val APP_OPEN_BACKGROUND_MS: Long get() = AdConfig.current.appOpenBackgroundMs
    }

    // ── LaunchAdGate ──────────────────────────────────────────────

    override fun prewarm(context: Context) {
        preloadInterstitial(context.applicationContext)
        preloadAppOpen(context.applicationContext)
        preloadRewarded(context.applicationContext)
        // Full-screen ads are reserved for user-initiated transitions, not timer interrupts.
    }

    override fun showLaunchInterstitial(activity: Activity, onDone: () -> Unit) =
        showGateInterstitial(activity, launchFlag = true, onDone = onDone)

    /** Interstitial when the player leaves a game (PSP or PS2) and returns to the app. */
    fun showExitInterstitial(activity: Activity) {
        if (!AdConfig.current.interstitialOnExit) return
        showGateInterstitial(activity, launchFlag = false, onDone = {})
    }

    private fun showGateInterstitial(activity: Activity, launchFlag: Boolean, onDone: () -> Unit) {
        // AdMob show() must run on the main thread; a background caller
        // re-posts instead of showing into a half-torn-down state.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { showGateInterstitial(activity, launchFlag, onDone) }
            return
        }
        // Every callback-invoked lambda is guarded: a throwing launcher
        // callback inside an AdMob callback used to propagate on the
        // main thread (a crash attributed to "the interstitial").
        val doneSafe = {
            try {
                onDone()
            } catch (_: Throwable) {
            }
        }
        if (interstitialOff() || (launchFlag && !AdConfig.current.interstitialOnLaunch)) {
            doneSafe()
            return
        }
        // Never show into a finishing/destroyed/paused activity, and
        // never stack on top of another fullscreen ad (timer/app-open
        // may already own the screen) — both are crash/failure-prone.
        if (activity.isFinishing || activity.isDestroyed || !isResumed) {
            doneSafe()
            return
        }
        if (isFullscreenAdShowing.get()) {
            doneSafe()
            return
        }
        // Atomic take: the timer path can no longer take the same ad
        // object in the same instant and double-show it.
        val ad = takeInterstitial()
        if (ad == null) {
            // Fail open: never block a launch on an ad.
            preloadInterstitial(appCtx)
            doneSafe()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShowMs < INTERSTITIAL_INTERVAL_MS) {
            restoreInterstitial(ad)
            doneSafe()
            return
        }
        if (!isFullscreenAdShowing.compareAndSet(false, true)) {
            restoreInterstitial(ad)
            doneSafe()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdImpression() { AdDiagnostics.impression("Interstitial") }
            override fun onAdDismissedFullScreenContent() {
                try {
                    isFullscreenAdShowing.set(false)
                    lastInterstitialShowMs = System.currentTimeMillis()
                    preloadInterstitial(appCtx)
                } catch (_: Throwable) {
                }
                try {
                    com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "idle"
                } catch (_: Throwable) {
                }
                doneSafe()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdDiagnostics.showFailed("Interstitial")
                try {
                    isFullscreenAdShowing.set(false)
                    preloadInterstitial(appCtx)
                } catch (_: Throwable) {
                }
                try {
                    com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "idle"
                } catch (_: Throwable) {
                }
                doneSafe()
            }
        }
        try {
            // Re-validate immediately before show: the activity may
            // have died between the checks above and this line.
            if (activity.isFinishing || activity.isDestroyed || !isResumed) {
                isFullscreenAdShowing.set(false)
                restoreInterstitial(ad)
                doneSafe()
                return
            }
            try {
                com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "showing"
            } catch (_: Throwable) {
            }
            ad.show(activity)
            // Timer resets on a real show (set here; the dismiss callback
            // refreshes it to the actual dismiss time).
            lastInterstitialShowMs = now
        } catch (_: Throwable) {
            isFullscreenAdShowing.set(false)
            try {
                com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "idle"
            } catch (_: Throwable) {
            }
            preloadInterstitial(appCtx)
            doneSafe()
        }
    }

    // ── Timer interstitial (ZGamez's scheduleInterstitialTimer) ───
    // This was MISSING entirely — AdsEngine only had launch
    // interstitials, which is why no timer interstitial ever showed.
    // ZGamez's rules, replicated: a foreground-only timer fires every
    // 95s and shows directly (navigation does NOT trigger shows); the
    // full interval applies ONLY when an ad actually showed, otherwise
    // it retries in 15s; never shows while backgrounded, while another
    // fullscreen ad is showing, or when no fresh ad is ready
    // (fail-open: the tick is skipped, never a crash or a block).
    fun startTimerInterstitial() {
        if (timerStarted) return
        timerStarted = true
        scope.launch {
            while (true) {
                delay(INTERSTITIAL_INTERVAL_MS)
                timerAttempt()
            }
        }
    }

    private suspend fun timerAttempt() {
        // Bounded retries (~10 min): a tick that never shows falls back
        // to the next full 95s interval instead of spinning forever.
        var retries = 0
        while (retries < 40) {
            if (interstitialOff()) return
            if (!isForeground) {
                delay(LOAD_RETRY_DELAY_MS)
                retries++
                continue
            }
            val activity = currentActivityRef?.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                delay(LOAD_RETRY_DELAY_MS)
                retries++
                continue
            }
            if (isFullscreenAdShowing.get()) {
                delay(LOAD_RETRY_DELAY_MS)
                retries++
                continue
            }
            if (!isFreshInterstitial()) {
                preloadInterstitial(appCtx)
                delay(LOAD_RETRY_DELAY_MS)
                retries++
                continue
            }
            val shown = showInterstitialNow(activity)
            if (shown) {
                preloadInterstitial(appCtx)
                return // full 95s interval before the next tick
            }
            preloadInterstitial(appCtx)
            delay(LOAD_RETRY_DELAY_MS)
            retries++
        }
    }

    /** Shows the loaded interstitial and suspends until it dismisses or fails. True = actually shown. */
    private suspend fun showInterstitialNow(activity: Activity): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val finish = { value: Boolean ->
                    if (cont.isActive) cont.resume(value)
                }
                // All gates re-checked on MAIN (the IO-thread check in
                // timerAttempt can go stale): resumed activity only,
                // no stacking on another fullscreen ad, interval gate.
                if (!isResumed || !isForeground ||
                    activity.isFinishing || activity.isDestroyed
                ) {
                    finish(false)
                    return@suspendCancellableCoroutine
                }
                // The 95s interval gate also applies to timer shows: a
                // launch interstitial shown moments ago blocks this tick
                // (it retries in 15s via timerAttempt).
                if (System.currentTimeMillis() - lastInterstitialShowMs < INTERSTITIAL_INTERVAL_MS) {
                    finish(false)
                    return@suspendCancellableCoroutine
                }
                // Atomic take + atomic show gate: the launch path can
                // no longer take or show the same ad in the same instant.
                val ad = takeInterstitial()
                if (ad == null) {
                    finish(false)
                    return@suspendCancellableCoroutine
                }
                if (!isFullscreenAdShowing.compareAndSet(false, true)) {
                    restoreInterstitial(ad)
                    finish(false)
                    return@suspendCancellableCoroutine
                }
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdImpression() { AdDiagnostics.impression("Interstitial") }
                    override fun onAdDismissedFullScreenContent() {
                        try {
                            isFullscreenAdShowing.set(false)
                            lastInterstitialShowMs = System.currentTimeMillis()
                        } catch (_: Throwable) {
                        }
                        try {
                            com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "idle"
                        } catch (_: Throwable) {
                        }
                        finish(true)
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdDiagnostics.showFailed("Interstitial")
                        try {
                            isFullscreenAdShowing.set(false)
                        } catch (_: Throwable) {
                        }
                        try {
                            com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "idle"
                        } catch (_: Throwable) {
                        }
                        finish(false)
                    }
                }
                try {
                    // Re-validate immediately before show: the activity
                    // may have died between the checks above and this line.
                    if (activity.isFinishing || activity.isDestroyed ||
                        !isResumed || !isForeground
                    ) {
                        isFullscreenAdShowing.set(false)
                        restoreInterstitial(ad)
                        finish(false)
                        return@suspendCancellableCoroutine
                    }
                    try {
                        com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "showing"
                    } catch (_: Throwable) {
                    }
                    ad.show(activity)
                    lastInterstitialShowMs = System.currentTimeMillis()
                } catch (_: Throwable) {
                    isFullscreenAdShowing.set(false)
                    try {
                        com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "idle"
                    } catch (_: Throwable) {
                    }
                    finish(false)
                }
            }
        }
    }

    // ── Rewarded (50% download gate) ──────────────────────────────
    // Mirrors ZGamez's showRewarded: a preloaded rewarded ad, a short
    // wait (up to 5s) when none is ready, then fail-open via
    // onNotAvailable — the caller (50% dialog) resumes the download on
    // EVERY path (rewarded, dismissed, not available), exactly like
    // ZGamez's fail-open gate, so a download is never bricked by ads.

    private fun isFreshRewarded(): Boolean {
        val ad = rewardedAd ?: return false
        if (System.currentTimeMillis() - rewardedLoadedAtMs > MAX_AD_AGE_MS) {
            rewardedAd = null
            return false
        }
        return true
    }

    /** True when a fresh rewarded ad is loaded and can be shown right now. */
    fun isRewardedReady(): Boolean = rewardedAd != null && isFreshRewarded()

    /** False for ad-free players, no consent, or ads switched off: callers should skip any ad gate. */
    fun rewardedEnabled(): Boolean = !adsOff()

    fun preloadRewarded(context: Context) = onMain { preloadRewardedImpl(context) }

    private fun preloadRewardedImpl(context: Context) {
        if (adsOff()) return
        if (rewardedLoading.getAndSet(true)) return
        if (isFreshRewarded()) {
            rewardedLoading.set(false)
            return
        }
        val loadGeneration = consentGeneration.get()
        RewardedAd.load(
            context.applicationContext,
            REWARDED_UNIT,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    AdDiagnostics.loaded("Rewarded")
                    try {
                        if (loadGeneration == consentGeneration.get() && !adsOff()) rewardedAd = ad
                        rewardedLoadedAtMs = System.currentTimeMillis()
                    } catch (_: Throwable) {
                    } finally {
                        rewardedLoading.set(false)
                        if (loadGeneration != consentGeneration.get() && !adsOff()) preloadRewarded(appCtx)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdDiagnostics.failed("Rewarded", error.code)
                    try {
                        rewardedAd = null
                    } catch (_: Throwable) {
                    } finally {
                        rewardedLoading.set(false)
                        if (loadGeneration != consentGeneration.get() && !adsOff()) preloadRewarded(appCtx)
                    }
                }
            }
        )
    }

    fun showRewarded(
        activity: Activity,
        onRewarded: () -> Unit,
        onDismissed: () -> Unit = {},
        onNotAvailable: () -> Unit = { onRewarded() }
    ) {
        if (adsOff()) {
            onNotAvailable()
            return
        }
        val ready = rewardedAd?.takeIf { isFreshRewarded() }
        if (ready != null) {
            showRewardedNow(activity, ready, onRewarded, onDismissed, onNotAvailable)
            return
        }
        // Not ready: wait up to 5s for a fresh ad (ZGamez's rewarded
        // wait), then fail-open — never leave the caller hanging.
        preloadRewarded(appCtx)
        scope.launch {
            var waited = 0L
            while (waited < REWARDED_WAIT_MS) {
                delay(250)
                waited += 250
                // A failed load leaves nothing loading: ask again every 2s
                // while the user waits (preload ignores a load in flight).
                if (waited % 2000L == 0L) preloadRewarded(appCtx)
                val ad = rewardedAd?.takeIf { isFreshRewarded() }
                if (ad != null) {
                    withContext(Dispatchers.Main) {
                        showRewardedNow(activity, ad, onRewarded, onDismissed, onNotAvailable)
                    }
                    return@launch
                }
            }
            withContext(Dispatchers.Main) { onNotAvailable() }
        }
    }

    private fun showRewardedNow(
        activity: Activity,
        ad: RewardedAd,
        onRewarded: () -> Unit,
        onDismissed: () -> Unit,
        onNotAvailable: () -> Unit
    ) {
        // Same safe-context rules as interstitials (shared flaw class):
        // main thread only, resumed activity only, never stack.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                showRewardedNow(activity, ad, onRewarded, onDismissed, onNotAvailable)
            }
            return
        }
        // Callback-invoked lambdas are guarded: a throwing download
        // callback inside an AdMob callback used to propagate as a crash.
        val rewardedSafe = {
            try {
                onRewarded()
            } catch (_: Throwable) {
            }
        }
        val dismissedSafe = {
            try {
                onDismissed()
            } catch (_: Throwable) {
            }
        }
        val notAvailableSafe = {
            try {
                onNotAvailable()
            } catch (_: Throwable) {
            }
        }
        if (isFullscreenAdShowing.get()) {
            // Another fullscreen ad is up — fail-open, don't stack.
            notAvailableSafe()
            return
        }
        if (activity.isFinishing || activity.isDestroyed || !isResumed) {
            notAvailableSafe()
            return
        }
        rewardedAd = null
        if (!isFullscreenAdShowing.compareAndSet(false, true)) {
            notAvailableSafe()
            return
        }
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdImpression() { AdDiagnostics.impression("Rewarded") }
            override fun onAdDismissedFullScreenContent() {
                try {
                    isFullscreenAdShowing.set(false)
                    preloadRewarded(appCtx)
                } catch (_: Throwable) {
                }
                try {
                    com.evolutionofgames.zgamespsx.crash.CrashState.rewardedUp = false
                } catch (_: Throwable) {
                }
                // Exactly one callback: rewarded if earned, else
                // dismissed. The 50% dialog resumes on both (fail-open).
                if (earned) rewardedSafe() else dismissedSafe()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdDiagnostics.showFailed("Rewarded")
                try {
                    isFullscreenAdShowing.set(false)
                    preloadRewarded(appCtx)
                } catch (_: Throwable) {
                }
                try {
                    com.evolutionofgames.zgamespsx.crash.CrashState.rewardedUp = false
                } catch (_: Throwable) {
                }
                notAvailableSafe()
            }
        }
        try {
            if (activity.isFinishing || activity.isDestroyed || !isResumed) {
                isFullscreenAdShowing.set(false)
                notAvailableSafe()
                return
            }
            try {
                com.evolutionofgames.zgamespsx.crash.CrashState.rewardedUp = true
            } catch (_: Throwable) {
            }
            ad.show(activity) { earned = true }
        } catch (_: Throwable) {
            isFullscreenAdShowing.set(false)
            try {
                com.evolutionofgames.zgamespsx.crash.CrashState.rewardedUp = false
            } catch (_: Throwable) {
            }
            preloadRewarded(appCtx)
            notAvailableSafe()
        }
    }

    // ── Interstitial loading ──────────────────────────────────────

    private fun isFreshInterstitial(): Boolean {
        // The stored ad is read on the IO timer thread but written on
        // AdMob callback threads — take it under the same lock the
        // take/restore paths use, and only null it when it is still
        // the same instance, so a just-loaded ad can never be
        // clobbered by a stale age check.
        val ad = synchronized(adLock) { interstitial } ?: return false
        if (System.currentTimeMillis() - interstitialLoadedAtMs > MAX_AD_AGE_MS) {
            synchronized(adLock) { if (interstitial === ad) interstitial = null }
            return false
        }
        return true
    }

    fun preloadInterstitial(context: Context) = onMain { preloadInterstitialImpl(context) }

    private fun preloadInterstitialImpl(context: Context) {
        if (interstitialOff()) return
        if (interstitialLoading.getAndSet(true)) return
        if (isFreshInterstitial()) {
            interstitialLoading.set(false)
            return
        }
        // Load-path audit (third crash report, "when inter loaded"):
        // load correctly uses the application context (show is what
        // needs a resumed Activity), single-flight is already guarded
        // by the AtomicBoolean above, and no second load can overlap
        // it. What was NOT safe: the callbacks below ran unguarded, so
        // a throw inside one could wedge interstitialLoading at true
        // forever (no future interstitial ever loads again), and the
        // stored ad was written without the lock the timer reads under.
        // Both are fixed here: every callback body is guarded and the
        // loading flag resets in finally, no matter what.
        try {
            com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "loading"
        } catch (_: Throwable) {
        }
        val loadGeneration = consentGeneration.get()
        InterstitialAd.load(
            context.applicationContext,
            INTERSTITIAL_UNIT,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    AdDiagnostics.loaded("Interstitial")
                    try {
                        if (loadGeneration == consentGeneration.get() && !interstitialOff()) synchronized(adLock) { interstitial = ad }
                        interstitialLoadedAtMs = System.currentTimeMillis()
                        try {
                            com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "loaded"
                        } catch (_: Throwable) {
                        }
                    } catch (_: Throwable) {
                    } finally {
                        interstitialLoading.set(false)
                        if (loadGeneration != consentGeneration.get() && !interstitialOff()) preloadInterstitial(appCtx)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdDiagnostics.failed("Interstitial", error.code)
                    try {
                        synchronized(adLock) { interstitial = null }
                        try {
                            com.evolutionofgames.zgamespsx.crash.CrashState.interstitialState = "failed"
                        } catch (_: Throwable) {
                        }
                    } catch (_: Throwable) {
                    } finally {
                        interstitialLoading.set(false)
                    }
                    // Bounded background retry (15s), not a launch block.
                    try {
                        if (interstitialRetryJobStarted.compareAndSet(false, true)) {
                            scope.launch {
                                try {
                                    delay(LOAD_RETRY_DELAY_MS)
                                } catch (_: Throwable) {
                                } finally {
                                    interstitialRetryJobStarted.set(false)
                                }
                                preloadInterstitial(appCtx)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        )
    }

    // ── Native (in-feed tiles) ────────────────────────────────────

    /**
     * Loads one native ad for an in-feed tile. The callback always fires
     * (null = no ad / ad-free): the tile hides itself on null, so an
     * ad-free user never sees a tile and a failed load never leaves a
     * blank hole.
     */
    fun loadNativeAd(onLoaded: (NativeAd?) -> Unit) = onMain { loadNativeAdImpl(onLoaded) }

    private fun loadNativeAdImpl(onLoaded: (NativeAd?) -> Unit) {
        if (adsOff()) {
            onLoaded(null)
            return
        }
        val loadGeneration = consentGeneration.get()
        try {
            com.google.android.gms.ads.AdLoader.Builder(appCtx, NATIVE_UNIT)
                .forNativeAd { ad -> if (loadGeneration != consentGeneration.get() || adsOff()) { ad.destroy(); onLoaded(null) } else { AdDiagnostics.loaded("Native"); onLoaded(ad) } }
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                        // Native VIDEO creatives are eligible; they start muted so a
                        // feed never blasts sound, and the user can unmute in place.
                        .setVideoOptions(
                            VideoOptions.Builder()
                                .setStartMuted(true)
                                .build()
                        )
                        .build()
                )
                .withAdListener(object : com.google.android.gms.ads.AdListener() {
                    override fun onAdImpression() { AdDiagnostics.impression("Native") }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                    AdDiagnostics.failed("Native", error.code)
                        onLoaded(null)
                    }
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        } catch (_: Exception) {
            onLoaded(null)
        }
    }

    // ── App open ──────────────────────────────────────────────────

    fun preloadAppOpen(context: Context) = onMain { preloadAppOpenImpl(context) }

    private fun preloadAppOpenImpl(context: Context) {
        if (appOpenOff()) return
        if (appOpenAd != null) return
        val loadGeneration = consentGeneration.get()
        AppOpenAd.load(
            context.applicationContext,
            APP_OPEN_UNIT,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    AdDiagnostics.loaded("App-open")
                    try {
                        if (loadGeneration == consentGeneration.get() && !appOpenOff()) appOpenAd = ad
                        appOpenLoadedAtMs = System.currentTimeMillis()
                    } catch (_: Throwable) {
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdDiagnostics.failed("App-open", error.code)
                    try {
                        appOpenAd = null
                    } catch (_: Throwable) {
                    }
                }
            }
        )
    }

    @Volatile private var launchAdHandled = false

    /**
     * Cold start: shows ONE app-open ad as soon as the splash screen has finished (plugin switch
     * "app_open_on_launch"). Waits a few seconds for consent + the ad to load; if it isn't ready
     * in time the player is simply never blocked. Ignores the 4 h minimum gap, which only
     * applies when coming back from the background.
     */
    fun showAppOpenAfterSplash(activity: Activity) {
        if (launchAdHandled) return
        launchAdHandled = true
        scope.launch {
            try {
                var waited = 0L
                var preloadedAt = -1L
                while (waited <= 9_000L) {
                    if (adsOff() && waited >= 6_000L) return@launch
                    if (!appOpenOff() && !AdConfig.current.appOpenOnLaunch) return@launch
                    if (!appOpenOff() && appOpenAd != null) break
                    if (!appOpenOff() && (preloadedAt < 0L || waited - preloadedAt >= 3_000L)) {
                        preloadedAt = waited
                        preloadAppOpen(appCtx)
                    }
                    delay(250)
                    waited += 250
                }
                if (appOpenOff() || !AdConfig.current.appOpenOnLaunch) return@launch
                withContext(Dispatchers.Main) {
                    if (!isFullscreenAdShowing.get()) showAppOpenNow(activity)
                }
            } catch (_: Throwable) { }
        }
    }

    private fun showAppOpenNow(activity: Activity): Boolean {
        val now = System.currentTimeMillis()
        val ad = appOpenAd?.takeIf { now - appOpenLoadedAtMs <= MAX_AD_AGE_MS } ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (!isFullscreenAdShowing.compareAndSet(false, true)) return false
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdImpression() { AdDiagnostics.impression("App-open") }
            override fun onAdDismissedFullScreenContent() {
                try {
                    isFullscreenAdShowing.set(false)
                    lastAppOpenShowMs = System.currentTimeMillis()
                    preloadAppOpen(appCtx)
                } catch (_: Throwable) { }
            }
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdDiagnostics.showFailed("App-open")
                try {
                    isFullscreenAdShowing.set(false)
                    preloadAppOpen(appCtx)
                } catch (_: Throwable) { }
            }
        }
        return try {
            ad.show(activity)
            true
        } catch (_: Throwable) {
            isFullscreenAdShowing.set(false)
            preloadAppOpen(appCtx)
            false
        }
    }

    /** Call from the main activity's onResume. Shows at most once per 4h, only after a real background gap. */
    fun onForeground(activity: Activity, suppressAppOpen: Boolean = false) {
        // Timer bookkeeping runs on EVERY resume (before the app-open
        // early-returns below): the timer needs a live activity ref and
        // foreground/resumed flags to show safely.
        currentActivityRef = WeakReference(activity)
        isForeground = true
        isResumed = true
        val now = System.currentTimeMillis()
        val wasBackgrounded = lastBackgroundMs > 0L && now - lastBackgroundMs >= APP_OPEN_BACKGROUND_MS
        lastBackgroundMs = 0L
        // Returning from a game: the exit interstitial owns this moment.
        if (!wasBackgrounded || suppressAppOpen) return
        if (appOpenOff()) return
        if (now - lastAppOpenShowMs < APP_OPEN_MIN_GAP_MS) return
        val ad = appOpenAd?.takeIf { now - appOpenLoadedAtMs <= MAX_AD_AGE_MS } ?: run {
            preloadAppOpen(appCtx)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        // Never stack an app-open ad on top of another fullscreen ad.
        if (!isFullscreenAdShowing.compareAndSet(false, true)) return
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdImpression() { AdDiagnostics.impression("App-open") }
            override fun onAdDismissedFullScreenContent() {
                try {
                    isFullscreenAdShowing.set(false)
                    lastAppOpenShowMs = System.currentTimeMillis()
                    preloadAppOpen(appCtx)
                } catch (_: Throwable) {
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdDiagnostics.showFailed("App-open")
                try {
                    isFullscreenAdShowing.set(false)
                    preloadAppOpen(appCtx)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            ad.show(activity)
        } catch (_: Throwable) {
            isFullscreenAdShowing.set(false)
            preloadAppOpen(appCtx)
        }
    }

    /** Call from the main activity's onPause. */
    fun onBackground() {
        isForeground = false
        isResumed = false
        currentActivityRef = null
        lastBackgroundMs = System.currentTimeMillis()
    }
}
