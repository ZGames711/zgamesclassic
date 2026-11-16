package com.evolutionofgames.zgamespsx.download

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Track app visibility, not a single screen; allow brief transitions between activities. */
object ForegroundDownloadPolicy : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: DownloadEngine
    private var started = 0
    private var visible = false
    private var enabled = true
    private val background = Runnable { if (started == 0) { visible = false; applyPolicy() } }

    fun init(app: Application) {
        engine = DownloadEngine.get(app)
        enabled = WpConfig.flag("download_foreground_only", true)
        app.registerActivityLifecycleCallbacks(this)
        scope.launch { WpConfig.content.collect { enabled = it.flag("download_foreground_only", true); applyPolicy() } }
        applyPolicy()
    }
    private fun applyPolicy() = engine.setForegroundPolicy(visible, enabled)
    override fun onActivityStarted(activity: Activity) {
        started++
        handler.removeCallbacks(background)
        if (!visible) { visible = true; applyPolicy() }
    }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) { handler.removeCallbacks(background); handler.postDelayed(background, 700L) }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
