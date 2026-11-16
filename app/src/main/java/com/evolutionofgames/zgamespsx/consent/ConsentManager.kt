package com.evolutionofgames.zgamespsx.consent

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UMP is refreshed on every app launch. Ads are gated by UMP, not by a custom consent flag. */
object ConsentManager {
    private const val TAG = "ConsentManager"
    private val initStarted = AtomicBoolean(false)
    private val _allowed = MutableStateFlow(false)
    val allowedFlow: StateFlow<Boolean> = _allowed.asStateFlow()
    val adsAllowed: Boolean get() = _allowed.value
    private val _privacyRequired = MutableStateFlow(false)
    val privacyRequired: StateFlow<Boolean> = _privacyRequired.asStateFlow()
    private fun info(ctx: Context) = UserMessagingPlatform.getConsentInformation(ctx)

    private fun sync(ctx: Context): Boolean {
        val ci = info(ctx)
        _privacyRequired.value = ci.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (!ci.canRequestAds()) { _allowed.value = false; return false }
        if (initStarted.compareAndSet(false, true)) {
            try { MobileAds.initialize(ctx.applicationContext) }
            catch (t: Throwable) { initStarted.set(false); _allowed.value = false; Log.e(TAG, "MobileAds init failed", t); return false }
        }
        _allowed.value = true
        return true
    }
    fun gather(activity: Activity, onReset: () -> Unit = {}, onFinished: () -> Unit = {}, onReady: () -> Unit = {}) {
        val ci = info(activity)
        var notified = false
        fun update() { if (sync(activity) && !notified) { notified = true; onReady() } }
        try {
            ci.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                // Recheck before displaying the form: a previously valid choice may have expired.
                if (!ci.canRequestAds()) _allowed.value = false
                _privacyRequired.value = ci.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "consent form: ${error.message}")
                    onReset()
                    notified = false
                    update()
                    onFinished()
                }
            }, { error -> Log.w(TAG, "consent info update failed: ${error.message}"); update(); onFinished() })
            // UMP can reuse the previous session only AFTER requestConsentInfoUpdate is called.
            update()
        } catch (t: Throwable) {
            Log.w(TAG, "consent flow failed", t)
            _allowed.value = false
            onFinished()
        }
    }
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        // Tear down displayed ads while the user changes choices; callers discard cached ads too.
        _allowed.value = false
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                if (error != null) Log.w(TAG, "privacy options: ${error.message}")
                sync(activity); onDone()
            }
        } catch (t: Throwable) { Log.w(TAG, "privacy options failed", t); sync(activity); onDone() }
    }
}
