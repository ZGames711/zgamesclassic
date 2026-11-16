package com.evolutionofgames.zgamespsx.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Runs the small set of actions the plugin can attach to a banner, card,
 * button, menu row or push. Only these exist; anything else is ignored.
 */
object RemoteActions {

    fun perform(ctx: Context, a: RAction?) {
        if (a == null) return
        try {
            when (a.type) {
                "url" -> openUrl(ctx, a.value)
                "screen" -> if (a.value.isNotBlank()) WpConfig.requestScreen(a.value)
                "page" -> if (a.value.isNotBlank()) WpConfig.openPage(a.value)
                "play_store" -> openPlayStore(ctx, a.value.ifBlank { ctx.packageName })
                "share" -> share(ctx, a.value)
                "email" -> if (a.value.isNotBlank()) {
                    start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${a.value}")))
                }
                else -> Log.i("RemoteActions", "ignoring unknown action '${a.type}'")
            }
        } catch (t: Throwable) {
            Log.w("RemoteActions", "action ${a.type} failed: ${t.message}")
        }
    }

    /** Only http(s) is ever opened: no intent:, file:, content: or javascript: from the server. */
    fun openUrl(ctx: Context, url: String) {
        val u = Uri.parse(url.trim())
        if (u.scheme != "https" && u.scheme != "http") return
        start(ctx, Intent(Intent.ACTION_VIEW, u))
    }

    private fun openPlayStore(ctx: Context, pkg: String) {
        try {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: Throwable) {
            openUrl(ctx, "https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    private fun share(ctx: Context, text: String) {
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        start(ctx, Intent.createChooser(send, null))
    }

    private fun start(ctx: Context, i: Intent) {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
