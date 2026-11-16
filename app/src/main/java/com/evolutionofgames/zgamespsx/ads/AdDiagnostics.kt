package com.evolutionofgames.zgamespsx.ads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local-only last SDK result, no device IDs or purchase data. */
object AdDiagnostics {
    private val _last = MutableStateFlow("No ad load reported yet")
    val last: StateFlow<String> = _last.asStateFlow()
    fun loaded(format: String) { _last.value = "$format loaded" }
    private fun formatName(format: String): String = when {
        format.contains("interstitial", true) -> "interstitial"
        format.contains("rewarded", true) -> "rewarded"
        format.contains("native", true) -> "native"
        format.contains("app-open", true) -> "app_open"
        else -> "banner"
    }
    fun impression(format: String) { com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("ad_${formatName(format)}_impression") }
    fun showFailed(format: String) { com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("ad_${formatName(format)}_show_failed") }
    fun failed(format: String, code: Int) { _last.value = "$format failed to load (code $code)"; com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("ad_${formatName(format)}_load_failed") }
}
