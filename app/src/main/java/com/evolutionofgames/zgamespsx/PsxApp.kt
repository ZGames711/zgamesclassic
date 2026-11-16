package com.evolutionofgames.zgamespsx

import android.app.Application
import android.util.Log

/** ZGames PSX application: crash guard, ads/consent plumbing, remote control and downloads. */
class PsxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The emulator runs in its own ":emulator" process: it needs none of the app plumbing
        // (ads, downloads, remote config), and must not touch the shared launch-guard state.
        if (isEmulatorProcess()) return
        // Crash capture FIRST, before anything else can crash: the
        // uncaught-exception handler saves a report to app-private
        // storage so the next launch can show the real trace. Without
        // this, interstitial crashes were guessed at twice with no
        // stack trace. The handler never sends anything anywhere.
        try {
            com.evolutionofgames.zgamespsx.crash.CrashReporter.install(this)
        } catch (t: Throwable) {
            Log.e("PsxApp", "Crash handler install failed", t)
        }
        // Crash-loop breaker: if the previous launches died within seconds of starting,
        // drop the saved download queue (the usual poison) so the app can open again.
        try { guardCrashLoop() } catch (t: Throwable) { Log.e("PsxApp", "launch guard failed", t) }
        // The emulator core calls FirebaseAuth/Firestore.getInstance() when its
        // screen opens; with no initialised FirebaseApp that throws and the
        // app crashes on PS2 Play. If google-services.json is present the
        // real default app already exists; otherwise create an inert local
        // placeholder (never contacts a real project).
        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(
                    this,
                    com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId("1:000000000000:android:0000000000000000")
                        .setApiKey("placeholder-not-a-real-key")
                        .setProjectId(com.evolutionofgames.zgamespsx.ads.AdConfig.PLACEHOLDER_PROJECT)
                        .build()
                )
            }
        } catch (t: Throwable) {
            Log.e("PsxApp", "Placeholder Firebase init failed", t)
        }
        // Ad settings (Firebase Remote Config, falls back to built-in
        // defaults when Firebase isn't set up). Never throws.
        com.evolutionofgames.zgamespsx.ads.AdConfig.init(this)
        // WordPress plugin remote control (ad switches, update / maintenance /
        // announcement notices) and push notifications. Both never throw.
        try { com.evolutionofgames.zgamespsx.remote.WpConfig.init(this) } catch (t: Throwable) { Log.e("PsxApp", "WpConfig init failed", t) }
        try { com.evolutionofgames.zgamespsx.download.ForegroundDownloadPolicy.init(this) } catch (t: Throwable) { Log.e("PsxApp", "Download visibility policy init failed", t) }
        try { com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.init(this) } catch (t: Throwable) { Log.e("PsxApp", "Analytics init failed", t) }
        try { com.evolutionofgames.zgamespsx.emulator.PlaytimeStats.init(this) } catch (t: Throwable) { Log.e("PsxApp", "Playtime tracking init failed", t) }
        try { com.evolutionofgames.zgamespsx.push.PushManager.init(this) } catch (t: Throwable) { Log.e("PsxApp", "Push init failed", t) }
        // Ads SDK init now happens in ConsentManager.gather() (called by
        // MainActivity) so no ad request is made before consent is known.
    }

    private fun guardCrashLoop() {
        val sp = getSharedPreferences("launch_guard", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = sp.getLong("last_start", 0L)
        val stable = sp.getBoolean("stable", true)
        var strikes = sp.getInt("strikes", 0)
        if (!stable && now - last in 0..45_000L) strikes++ else if (stable) strikes = 0
        if (strikes >= 2) {
            com.evolutionofgames.zgamespsx.download.DownloadEngine.safeReset(this)
            strikes = 0
        }
        sp.edit().putLong("last_start", now).putBoolean("stable", false).putInt("strikes", strikes).commit()
        // Surviving 12 seconds counts as a healthy start.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { sp.edit().putBoolean("stable", true).apply() } catch (_: Throwable) { }
        }, 12_000L)
    }

    private fun isEmulatorProcess(): Boolean = try {
        java.io.File("/proc/self/cmdline").readText().trim('\u0000').endsWith(":emulator")
    } catch (_: Throwable) { false }
}
