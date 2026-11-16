package com.evolutionofgames.zgamespsx.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.evolutionofgames.zgamespsx.ads.AdConfig
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.delay

/**
 * One bottom ad slot for a screen. Everything is decided by Remote Config
 * ([AdConfig]): whether this placement shows an ad at all, whether the
 * slot is a (collapsible, large) anchored adaptive BANNER or a compact NATIVE ad, the
 * banner refresh interval and the unit ids. Ad-Free users see nothing.
 * A failed load collapses to nothing (never a fake bar).
 */
@Composable
fun BannerSlot(placement: AdConfig.Placement, viewModel: LibraryViewModel) {
    val cfg by AdConfig.snapshot.collectAsState()
    val adFree by viewModel.billing.isAdFree.collectAsState()
    val consented by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    if (adFree || !consented || !cfg.bannerFor(placement)) return
    if (cfg.bannerAsNative) {
        CompactNativeSlot(viewModel)
    } else {
        AdaptiveBannerSlot(
            unitId = cfg.bannerUnit,
            collapsible = cfg.bannerCollapsible,
            large = cfg.bannerLarge,
            refreshSec = cfg.bannerRefreshSec
        )
    }
}

/** Separate anchored post placement; never overlay the download button. */
@Composable
fun PostAnchoredBanner(viewModel: LibraryViewModel) {
    val cfg by AdConfig.snapshot.collectAsState()
    val adFree by viewModel.billing.isAdFree.collectAsState()
    val consent by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    if (!adFree && consent && cfg.adsEnabled && cfg.bannerDetail)
        AdaptiveBannerSlot(cfg.bannerUnit, cfg.bannerCollapsible, cfg.bannerLarge, cfg.bannerRefreshSec)
}

@Composable
private fun AdaptiveBannerSlot(unitId: String, collapsible: Boolean, large: Boolean, refreshSec: Long) {
    // Re-created if Remote Config changes the unit, the collapsible mode or the size type.
    val failed = remember(unitId, collapsible, large) { mutableStateOf(false) }
    val adViewRef = remember(unitId, collapsible, large) { arrayOfNulls<AdView>(1) }
    if (failed.value) return

    fun request(): AdRequest {
        val b = AdRequest.Builder()
        if (collapsible) {
            // Google's collapsible banner: extra "collapsible" = placement edge.
            b.addNetworkExtrasBundle(
                AdMobAdapter::class.java,
                Bundle().apply { putString("collapsible", "bottom") }
            )
        }
        return b.build()
    }

    if (refreshSec > 0) {
        LaunchedEffect(unitId, collapsible, large, refreshSec) {
            while (true) {
                delay(refreshSec * 1000L)
                try { adViewRef[0]?.loadAd(request()) } catch (_: Throwable) { }
            }
        }
    }
    DisposableEffect(unitId, collapsible, large) {
        onDispose {
            try { adViewRef[0]?.destroy() } catch (_: Throwable) { }
            adViewRef[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            val dm = ctx.resources.displayMetrics
            val adWidth = (dm.widthPixels / dm.density).toInt()
            AdView(ctx).apply {
                // LARGE anchored adaptive banner: sized for modern, tall screens
                // (height up to 100-150dp or 20% of the screen) and eligible for
                // higher-value / video demand. The older helper is deprecated.
                setAdSize(
                    if (large) AdSize.getLargeAnchoredAdaptiveBannerAdSize(ctx, adWidth)
                    else AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidth)
                )
                adUnitId = unitId
                adListener = object : AdListener() {
                    override fun onAdImpression() { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.impression("Banner") }
                    override fun onAdLoaded() { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.loaded("Banner") }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        com.evolutionofgames.zgamespsx.ads.AdDiagnostics.failed("Banner", error.code)
                        failed.value = true
                    }
                }
                adViewRef[0] = this
                loadAd(request())
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

/** Inline feed banner: never collapsible, distinct from anchored screen banners. */
@Composable
fun FeedBannerSlot() {
    val cfg by AdConfig.snapshot.collectAsState()
    val consented by com.evolutionofgames.zgamespsx.consent.ConsentManager.allowedFlow.collectAsState()
    if (!consented || !cfg.adsEnabled || !cfg.feedBannerEnabled) return
    val failed = remember(cfg.bannerUnit) { mutableStateOf(false) }
    if (failed.value) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
        val width = maxWidth.value.toInt().coerceAtLeast(1)
        val ref = remember(cfg.bannerUnit, width) { arrayOfNulls<AdView>(1) }
        DisposableEffect(cfg.bannerUnit, width) { onDispose { ref[0]?.destroy(); ref[0] = null } }
        AndroidView(factory = { ctx ->
            AdView(ctx).apply {
                val bannerView = this
                adUnitId = cfg.bannerUnit
                visibility = android.view.View.GONE
                adListener = object : AdListener() {
                    override fun onAdImpression() { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.impression("Banner") }
                    override fun onAdLoaded() { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.loaded("Feed banner"); bannerView.visibility = android.view.View.VISIBLE }
                    override fun onAdFailedToLoad(error: LoadAdError) { com.evolutionofgames.zgamespsx.ads.AdDiagnostics.failed("Feed banner", error.code); failed.value = true }
                }
                setAdSize(AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(ctx, width))
                ref[0] = this
                loadAd(AdRequest.Builder().build())
            }
        }, modifier = Modifier.fillMaxWidth())
    }
}

/** "Banner to native": a compact native ad (square media + headline + CTA) in the banner slot. */
@Composable
fun CompactNativeSlot(viewModel: LibraryViewModel, modifier: Modifier = Modifier) {
    val cfg by AdConfig.snapshot.collectAsState()
    var ad by remember(cfg.nativeUnit) { mutableStateOf<NativeAd?>(null) }
    DisposableEffect(cfg.nativeUnit) {
        var active = true
        viewModel.ads.loadNativeAd { loaded -> if (active) ad = loaded else loaded?.destroy() }
        onDispose { active = false; ad?.destroy(); ad = null }
    }
    val current = ad
    if (current != null) {
        Box(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            AndroidView(
                factory = { ctx -> buildCompactNativeView(ctx, current) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun buildCompactNativeView(ctx: Context, ad: NativeAd): NativeAdView {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Float) = (v * density + 0.5f).toInt()
    val vg = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
    val wc = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT

    val adView = NativeAdView(ctx).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(android.graphics.Color.parseColor("#1A160F"))
            setStroke(dp(1f), android.graphics.Color.parseColor("#80FFC107"))
        }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }
    val row = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
    }
    // Media: 120dp square = AdMob's minimum MediaView size.
    var media: MediaView? = null
    if (ad.mediaContent != null) {
        media = MediaView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(120f), dp(120f)).apply {
                setMargins(0, 0, dp(12f), 0)
            }
            setImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(android.graphics.Color.parseColor("#0E0C08"))
            }
        }
        row.addView(media)
    }
    val col = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, wc).apply { weight = 1f }
        // AdChoices sits top-right: keep the text clear of it.
        setPadding(0, 0, dp(22f), 0)
    }
    val badgeRow = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val badge = android.widget.TextView(ctx).apply {
        text = "Ad"
        textSize = 10f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#1A1200"))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(5f).toFloat()
            setColor(android.graphics.Color.parseColor("#FFC107"))
        }
        setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
        layoutParams = android.widget.LinearLayout.LayoutParams(wc, wc).apply { setMargins(0, 0, dp(8f), 0) }
    }
    val advertiser = android.widget.TextView(ctx).apply {
        text = ad.advertiser ?: ""
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#9AA6C4"))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    badgeRow.addView(badge)
    badgeRow.addView(advertiser)
    val headline = android.widget.TextView(ctx).apply {
        text = ad.headline
        textSize = 14f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#EAF0FF"))
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = android.widget.LinearLayout.LayoutParams(vg, wc).apply { setMargins(0, dp(4f), 0, 0) }
    }
    val cta = android.widget.TextView(ctx).apply {
        text = ad.callToAction ?: "Learn more"
        textSize = 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.WHITE)
        gravity = android.view.Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(android.graphics.Color.parseColor("#3D7BFF"), android.graphics.Color.parseColor("#6CC4FF"))
        ).apply { cornerRadius = dp(10f).toFloat() }
        layoutParams = android.widget.LinearLayout.LayoutParams(vg, dp(40f)).apply { setMargins(0, dp(8f), 0, 0) }
    }
    col.addView(badgeRow)
    col.addView(headline)
    col.addView(cta)
    row.addView(col)
    adView.addView(row)
    adView.headlineView = headline
    adView.advertiserView = advertiser
    adView.mediaView = media
    adView.callToActionView = cta
    adView.setNativeAd(ad)
    return adView
}
