package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import com.google.android.gms.ads.*

@Composable
fun PostAdSlot(viewModel: LibraryViewModel, large: Boolean, force: Boolean = false) {
    val cfg by AdConfig.snapshot.collectAsState()
    val adFree by viewModel.billing.isAdFree.collectAsState()
    val consent by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    // force = always show this slot (premium/consent/ads-off still apply), ignoring the
    // plugin's per-slot switches, which default to off.
    if (adFree || !consent || !cfg.adsEnabled || (!force && !(if (large) cfg.postLargeEnabled else cfg.postSmallEnabled))) return
    if (cfg.postAdType == "native") {
        key(cfg.nativeUnit, large) {
            if (large) NativeAdTile(loadNativeAd = { cb -> viewModel.ads.loadNativeAd(cb) }, modifier = Modifier.padding(vertical = 24.dp))
            else CompactNativeSlot(viewModel, Modifier.padding(vertical = 24.dp))
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      if (maxWidth < (if (large) 300.dp else 320.dp)) return@BoxWithConstraints
      key(cfg.bannerUnit, large) {
        var loaded by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val ref = remember { arrayOfNulls<AdView>(1) }
        DisposableEffect(Unit) { onDispose { ref[0]?.destroy(); ref[0] = null } }
        if (!failed) Column(Modifier.fillMaxWidth().padding(vertical = if (loaded) 24.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loaded) Text("Advertisement", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AndroidView(factory = { ctx -> AdView(ctx).apply {
                adUnitId = cfg.bannerUnit
                setAdSize(if (large) AdSize.MEDIUM_RECTANGLE else AdSize.BANNER)
                adListener = object : AdListener() {
                    override fun onAdLoaded() { loaded = true; com.evolutionofgames.zgamespsx.ads.AdDiagnostics.loaded("Post banner") }
                    override fun onAdImpression() { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.impression("Banner") }
                    override fun onAdFailedToLoad(error: LoadAdError) { failed = true; com.evolutionofgames.zgamespsx.ads.AdDiagnostics.failed("Post banner", error.code) }
                }
                ref[0] = this
                loadAd(AdRequest.Builder().build())
            } }, modifier = Modifier.width(if (large) 300.dp else 320.dp).height(if (loaded) (if (large) 250.dp else 50.dp) else 0.dp))
        }
      }
    }
}
