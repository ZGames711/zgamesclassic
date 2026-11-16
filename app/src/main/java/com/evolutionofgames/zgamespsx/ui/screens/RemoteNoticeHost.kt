package com.evolutionofgames.zgamespsx.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.evolutionofgames.zgamespsx.remote.WpConfig
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Shows what the WordPress plugin asked for: maintenance (blocks the app),
 * mandatory / optional update, or an announcement. Blocking notices cannot
 * be dismissed.
 */
@Composable
fun RemoteNoticeHost() {
    val ctx = LocalContext.current
    val notice by WpConfig.notice.collectAsState()
    val n = notice ?: return
    val icon = when (n.kind) {
        WpConfig.Kind.MAINTENANCE -> Icons.Filled.Construction
        WpConfig.Kind.UPDATE -> Icons.Filled.SystemUpdate
        WpConfig.Kind.ANNOUNCEMENT -> Icons.Filled.Campaign
    }
    AlertDialog(
        onDismissRequest = { if (!n.force) WpConfig.dismiss(ctx, n) },
        properties = DialogProperties(dismissOnBackPress = !n.force, dismissOnClickOutside = !n.force),
        icon = { Icon(icon, contentDescription = null, tint = PsxColors.Yellow) },
        title = { Text(n.title) },
        text = { Text(n.message) },
        confirmButton = {
            val label = when {
                n.kind == WpConfig.Kind.UPDATE -> "Update"
                n.url != null -> "Open"
                n.force -> "Close app"
                else -> "OK"
            }
            Button(onClick = {
                val url = n.url
                if (url != null) {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                    if (!n.force) WpConfig.dismiss(ctx, n)
                } else if (n.force) {
                    ctx.activity()?.finishAffinity()
                } else {
                    WpConfig.dismiss(ctx, n)
                }
            }) { Text(label) }
        },
        dismissButton = {
            if (!n.force) TextButton(onClick = { WpConfig.dismiss(ctx, n) }) { Text("Later") }
        }
    )
}
