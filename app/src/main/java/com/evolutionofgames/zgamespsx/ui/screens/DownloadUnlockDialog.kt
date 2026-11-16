package com.evolutionofgames.zgamespsx.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evolutionofgames.zgamespsx.analytics.UsageAnalytics
import com.evolutionofgames.zgamespsx.ads.AdsEngine
import kotlinx.coroutines.delay

/**
 * Download click -> rewarded ad LOADS -> "Unlock download" button -> ad plays -> download starts.
 *
 *  - Loading: the ad is requested; a spinner is shown (up to [timeoutSec]).
 *  - Ready:   the Unlock button appears. Only a tap shows the ad (never an ad out of nowhere).
 *  - Ad finished (reward earned): [onUnlocked] starts the download.
 *  - Ad closed early: nothing is unlocked; the next ad is loaded and the button returns.
 *  - No ad in time: starts the download when [grantWhenNoAd] (default), otherwise offers Retry.
 */
@Composable
fun DownloadUnlockDialog(
    ads: AdsEngine,
    activity: Activity,
    timeoutSec: Int,
    grantWhenNoAd: Boolean,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var attempt by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { UsageAnalytics.event("unlock_prompt") }

    LaunchedEffect(attempt) {
        phase = Phase.LOADING
        ads.preloadRewarded(activity.applicationContext)
        var waited = 0L
        val limit = timeoutSec * 1000L
        while (waited < limit && !ads.isRewardedReady()) {
            delay(250); waited += 250
            if (waited % 2000L == 0L) ads.preloadRewarded(activity.applicationContext)
        }
        if (ads.isRewardedReady()) {
            phase = Phase.READY
            UsageAnalytics.event("unlock_ready")
        } else if (grantWhenNoAd) {
            UsageAnalytics.event("unlock_no_ad")
            onUnlocked()
        } else {
            UsageAnalytics.event("unlock_no_ad")
            phase = Phase.FAILED
        }
    }

    AlertDialog(
        onDismissRequest = { if (!showing) { UsageAnalytics.event("unlock_cancelled"); onCancel() } },
        title = { Text("Unlock your download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    Phase.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Row(Modifier.width(12.dp)) { }
                        Text("Loading a short ad…")
                    }
                    Phase.READY -> Text("Your ad is ready. Tap Unlock, watch it to the end, and the download starts automatically.")
                    Phase.FAILED -> Text("No ad is available right now. Check your connection and try again.")
                }
                hint?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = phase != Phase.LOADING && !showing,
                onClick = {
                    if (phase == Phase.FAILED) { hint = null; attempt++; return@Button }
                    showing = true
                    hint = null
                    ads.showRewarded(
                        activity = activity,
                        onRewarded = { showing = false; UsageAnalytics.event("unlock_completed"); onUnlocked() },
                        onDismissed = {
                            showing = false
                            hint = "Watch the whole ad to unlock the download."
                            attempt++ // load the next ad, then the button comes back
                        },
                        onNotAvailable = {
                            showing = false
                            UsageAnalytics.event("unlock_no_ad")
                            if (grantWhenNoAd) onUnlocked() else { phase = Phase.FAILED }
                        }
                    )
                }
            ) { Text(if (phase == Phase.FAILED) "Retry" else "Unlock download") }
        },
        dismissButton = {
            TextButton(enabled = !showing, onClick = { UsageAnalytics.event("unlock_cancelled"); onCancel() }) { Text("Cancel") }
        }
    )
}

private enum class Phase { LOADING, READY, FAILED }
