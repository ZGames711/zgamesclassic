package com.evolutionofgames.zgamespsx.emulator

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Approximate play time: from handing the game to the emulator until the player is back in this app. */
object PlaytimeStats : Application.ActivityLifecycleCallbacks {
    const val EXTRA_GAME_KEY = "com.evolutionofgames.zgamespsx.PLAYTIME_GAME_KEY"
    private lateinit var app: Application
    private val _totals = MutableStateFlow<Map<String, Long>>(emptyMap())
    val totals: StateFlow<Map<String, Long>> = _totals.asStateFlow()
    fun init(application: Application) {
        app = application
        _totals.value = app.getSharedPreferences("playtime_totals", 0).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        application.registerActivityLifecycleCallbacks(this)
    }
    @Volatile private var pendingKey: String? = null
    @Volatile private var pendingStart = 0L

    /** Called when a game is handed to the emulator; the time until our app is back is the session. */
    fun begin(gameKey: String) {
        pendingKey = gameKey
        pendingStart = SystemClock.elapsedRealtime()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is com.evolutionofgames.zgamespsx.MainActivity) return
        val key = pendingKey ?: return
        pendingKey = null
        val elapsed = (SystemClock.elapsedRealtime() - pendingStart).coerceAtLeast(0L)
        if (elapsed < 5_000L) return
        com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("game_sessions")
        com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("game_seconds", (elapsed / 1000L).toInt())
        if (!WpConfig.flag("playtime_stats", false)) return
        val total = (_totals.value[key] ?: 0L) + elapsed
        _totals.value = _totals.value + (key to total)
        app.getSharedPreferences("playtime_totals", 0).edit().putLong(key, total).apply()
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
