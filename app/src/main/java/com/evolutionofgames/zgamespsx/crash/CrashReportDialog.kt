package com.evolutionofgames.zgamespsx.crash

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.evolutionofgames.zgamespsx.prefs.AppLinks

/**
 * Friendly "the app closed unexpectedly" prompt. The technical trace is
 * NOT shown to the player: "Send report" opens an email draft with the
 * report attached as text (the player chooses whether to send it —
 * nothing is sent automatically). Dismiss hides the popup and keeps the
 * report in Settings until cleared; Clear deletes it.
 */
@Composable
fun CrashReportDialog(
    report: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sorry, the app closed unexpectedly", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Something went wrong last time. If you'd like to help get it fixed, " +
                        "send the report by email — it only contains technical details about the problem. " +
                        "Nothing is sent unless you press send in your email app.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val mail = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${AppLinks.CONTACT_EMAIL}")
                        putExtra(Intent.EXTRA_SUBJECT, "ZGames PSX crash report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(mail)
                } catch (_: Throwable) {
                }
                onClear()
            }) { Text("Send report") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = onClear) { Text("Delete report") }
            }
        }
    )
}
