package com.evolutionofgames.zgamespsx.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.evolutionofgames.zgamespsx.BuildConfig
import com.evolutionofgames.zgamespsx.MainActivity
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Opt-in aggregate counters. Aggregate metrics are opt-in; per-game counts send a game ID, but no name, link, device ID or purchase data. */
object UsageAnalytics : Application.ActivityLifecycleCallbacks {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .addNetworkInterceptor { it.proceed(it.request().newBuilder().removeHeader("Accept-Encoding").build()) }.build()
    private var start = 0L
    private fun prefs() = app.getSharedPreferences("usage_analytics", Context.MODE_PRIVATE)
    fun init(application: Application) { app = application; application.registerActivityLifecycleCallbacks(this) }
    fun optedIn(): Boolean = ::app.isInitialized && prefs().getBoolean("opt_in", false)
    fun setOptIn(value: Boolean) { if (::app.isInitialized) { prefs().edit().putBoolean("opt_in", value).apply(); if (value) beginForeground() else start = 0L } }
    fun allowed(): Boolean = !BuildConfig.DEBUG && optedIn() && WpConfig.flag("usage_analytics", false)
    fun event(name: String, value: Int = 1) {
        if (!allowed() || value < 1) return
        val payload = JSONObject().put("event", name).put("value", value.coerceAtMost(21600)).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        scope.launch {
            try {
                for ((i, url) in listOf("${WpConfig.SITE}/wp-json/zgpsx/v1/usage-event", "${WpConfig.SITE}/?rest_route=/zgpsx/v1/usage-event").withIndex()) {
                    if (!allowed()) return@launch
                    http.newCall(Request.Builder().url(url).post(payload).build()).execute().use { r ->
                        if (r.code == 404 && i == 0) return@use
                        return@launch
                    }
                }
            } catch (_: Exception) { /* best effort; never block the app */ }
        }
    }
    fun gameDownload(game: String) {
        if (!allowed() || !WpConfig.flag("tile_download_counts", false) || !Regex("^(zgames|evo):[0-9]+$").matches(game)) return
        val payload = JSONObject().put("game", game).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        scope.launch { try {
            if (!allowed() || !WpConfig.flag("tile_download_counts", false)) return@launch
            http.newCall(Request.Builder().url("${WpConfig.SITE}/?rest_route=/zgpsx/v1/game-download").post(payload).build()).execute().close()
        } catch (_: Exception) { } }
    }
    private fun beginForeground() {
        if (!allowed()) return
        start = SystemClock.elapsedRealtime()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (prefs().getString("last_day", "") != day) { prefs().edit().putString("last_day", day).apply(); event("active_device") }
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) beginForeground()
    }
    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity && start != 0L) { val sec = ((SystemClock.elapsedRealtime() - start) / 1000).toInt(); start = 0L; event("app_seconds", sec) }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
