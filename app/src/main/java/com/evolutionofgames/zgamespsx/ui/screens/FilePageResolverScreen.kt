package com.evolutionofgames.zgamespsx.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.evolutionofgames.zgamespsx.download.CapturedDownload

/**
 * The ZGamez mechanism, ported: a VISIBLE WebView screen (JS on, DOM
 * storage on, the browser's own default User-Agent — no headers are
 * spoofed and no custom UA is set) that loads a file-host page URL.
 * The user taps through the host's own page exactly like a browser
 * (this is REQUIRED for MediaFire: its download button's href is set
 * by JavaScript and its repair URL returns an HTML error page, so no
 * scraper can ever produce the real file URL).
 *
 * When the host finally serves the file, the WebView's
 * DownloadListener captures (url, UA, contentDisposition, mimeType)
 * plus the WebView's cookies and the page URL as referer, closes this
 * screen, and hands all of it to
 * [com.evolutionofgames.zgamespsx.download.DownloadEngine.enqueueWebView]
 * via [onFileCaptured]. The "Download started" dialog is shown by the
 * caller ONLY when that enqueue returns a real id (>= 0) — never when
 * this screen merely opens.
 *
 * Caveat, stated plainly: WebView capture itself can only be proven
 * on a real device (there is none in this build environment). What is
 * verified here is the wiring: the listener is set, the capture fields
 * are exactly ZGamez's, and the enqueue variant is the WebView one.
 * Gate URLs (e.g. the zgames server file-gate) shown here just behave
 * like a browser page — the gate bypass is keyed to the ZGamez
 * package, and nothing here spoofs it.
 */
@Composable
fun FilePageResolverScreen(
    pageUrl: String,
    gameTitle: String,
    onFileCaptured: (CapturedDownload) -> Unit,
    onClose: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf(pageUrl) }
    var progress by remember { mutableStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // A host can fire the listener more than once for one tap; only
    // the first capture starts a download.
    val captured = remember { mutableStateOf(false) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onClose()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = pageTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Opening the download page for “$gameTitle”. " +
                    "Tap the download button on the page; keep this open until the download starts.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        pageError?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚠ $err — tap back to go back",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp
                )
            }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // NOTE: no setUserAgentString — the WebView's own
                    // browser UA is used, exactly like ZGamez's browser.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = handleUrl(view, request.url.toString())

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            targetUrl: String
                        ): Boolean = handleUrl(view, targetUrl)

                        /** http(s) stays in-app; every other scheme opens externally. */
                        private fun handleUrl(view: WebView, url: String): Boolean {
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                view.loadUrl(url)
                                return true
                            }
                            return try {
                                view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                                true
                            } catch (e: Exception) {
                                Log.w("FilePageResolver", "no handler for $url", e)
                                true
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageError = error?.description?.toString() ?: "Failed to load page"
                            }
                        }

                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            pageError = null
                            val t = view.title
                            if (!t.isNullOrBlank()) pageTitle = t
                            super.onPageFinished(view, finishedUrl)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView?, receivedTitle: String?) {
                            if (!receivedTitle.isNullOrBlank()) pageTitle = receivedTitle
                        }
                    }
                    // File downloads → the app's own download engine,
                    // with the browser's own cookies / UA / referer so
                    // Drive and MediaFire serve the real file (not a
                    // page) to our download. Mirrors ZGamez's
                    // InAppBrowser DownloadListener line for line.
                    setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
                        if (captured.value) return@setDownloadListener
                        captured.value = true
                        try {
                            val name = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                            Log.d("FilePageResolver", "captured download: $name from $dlUrl")
                            val cookies = try {
                                CookieManager.getInstance().getCookie(dlUrl)
                            } catch (_: Exception) {
                                null
                            }
                            onFileCaptured(
                                CapturedDownload(
                                    url = dlUrl,
                                    userAgent = userAgent,
                                    contentDisposition = contentDisposition,
                                    mimeType = mimeType,
                                    cookie = cookies,
                                    referer = this.url
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("FilePageResolver", "download listener capture failed", e)
                            captured.value = false
                        }
                    }
                    loadUrl(pageUrl)
                }.also { webView = it }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.destroy()
            } catch (_: Exception) {
            }
            webView = null
        }
    }
}
