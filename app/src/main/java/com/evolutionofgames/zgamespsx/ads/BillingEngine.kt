package com.evolutionofgames.zgamespsx.ads

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monthly Ad-Free subscription: removes ALL ads while active (every ad
 * path in [AdsEngine] checks [isAdFree]).
 *
 * Pattern: query on connect, acknowledge on purchase, restore on fresh
 * install (and on every connect — a successful Play query is
 * authoritative), persisted entitlement in SharedPreferences.
 *
 * The Play Console subscription and an eligible base plan must be active
 * for this package. Availability, price and billing terms come from Play.
 */
class BillingEngine(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences("psx_billing", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isAdFree = MutableStateFlow(prefs.getBoolean(KEY_AD_FREE, false))
    /** The single entitlement flag every ad path checks. */
    val isAdFree: StateFlow<Boolean> = _isAdFree.asStateFlow()

    private val _restoreStatus = MutableStateFlow("Not checked yet")
    val restoreStatus: StateFlow<String> = _restoreStatus.asStateFlow()

    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products: StateFlow<List<ProductDetails>> = _products.asStateFlow()

    private var reconnectAttempts = 0

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(appCtx)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        connect()
    }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempts = 0
                    queryProducts()
                    restorePurchases()
                } else {
                    reconnectWithBackoff()
                }
            }

            override fun onBillingServiceDisconnected() {
                reconnectWithBackoff()
            }
        })
    }

    private fun reconnectWithBackoff() {
        scope.launch {
            val delayMs = minOf(1000L * (1L shl reconnectAttempts.coerceAtMost(5)), 30000L)
            reconnectAttempts++
            delay(delayMs)
            connect()
        }
    }

    fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(AD_FREE_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _products.value = details.productDetailsList
            }
        }
    }

    /** Starts the purchase flow for the monthly Ad-Free product. No-op when plans are unavailable. */
    fun launchPurchase(activity: Activity) {
        val productDetails = _products.value.firstOrNull() ?: return
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    /** Re-validates the entitlement from Play. Only a successful query updates it; failures keep the cached value. */
    fun restorePurchases() {
        if (!client.isReady) { _restoreStatus.value = "Google Play is not connected. Try again later."; return }
        _restoreStatus.value = "Checking Google Play…"
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
                val active = purchases.any {
                    it.products.any { id -> id in SUPPORTED_PRODUCT_IDS } &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                setAdFree(active)
                _restoreStatus.value = if (active) "Ad-Free subscription restored." else "No active Ad-Free subscription found on this Play account."
            } else {
                _restoreStatus.value = "Restore failed (Play code ${result.responseCode}); cached status unchanged."
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.products.none { it in SUPPORTED_PRODUCT_IDS }) continue
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                client.acknowledgePurchase(params) { _ -> }
            }
            setAdFree(true)
        }
    }

    private fun setAdFree(value: Boolean) {
        if (_isAdFree.value != value) {
            _isAdFree.value = value
            prefs.edit().putBoolean(KEY_AD_FREE, value).apply()
        }
    }

    companion object {
        /** New purchases use this Play Console subscription ID. */
        const val AD_FREE_PRODUCT_ID = "zgames_psx_pro"
        private val SUPPORTED_PRODUCT_IDS = setOf(AD_FREE_PRODUCT_ID, "zgames_super_premium", "psx_adfree_monthly") // Preserve existing subscribers, if any.
        private const val KEY_AD_FREE = "is_adfree"
    }
}
