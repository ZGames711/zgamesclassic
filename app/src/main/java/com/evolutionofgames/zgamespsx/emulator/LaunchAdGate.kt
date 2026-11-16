package com.evolutionofgames.zgamespsx.emulator

import android.app.Activity
import android.content.Context

/**
 * The one ad call the launchers need. The concrete ads engine
 * implements this; launchers never touch ad SDK types directly, so a
 * launch never depends on an ad being loaded — when the entitlement
 * says ad-free (or no ad is ready), [showLaunchInterstitial] just runs
 * [onDone] immediately.
 */
interface LaunchAdGate {
    /** Call when the games list appears so an ad is already loaded when the user taps. */
    fun prewarm(context: Context)

    /** Shows the launch interstitial (or skips it), then runs [onDone]. */
    fun showLaunchInterstitial(activity: Activity, onDone: () -> Unit)
}
