package com.evolutionofgames.zgamespsx.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolutionofgames.zgamespsx.crash.CrashReportDialog
import com.evolutionofgames.zgamespsx.crash.CrashReporter
import com.evolutionofgames.zgamespsx.consent.ConsentManager
import com.evolutionofgames.zgamespsx.prefs.AppLinks
import com.evolutionofgames.zgamespsx.prefs.AppPrefs
import com.evolutionofgames.zgamespsx.ui.LibraryViewModel
import com.evolutionofgames.zgamespsx.ui.theme.PsxColors

/**
 * Settings, grouped into sections with icon rows:
 *  - Ad-Free (the ONLY purchase entry)
 *  - GAMES: PS1 emulator status, ad status
 *  - SUPPORT: share + rate
 *  - HELP: last crash report (only when one exists)
 *  - ABOUT: name + real version
 */
@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel,
    onNav: (NavDest) -> Unit,
    onChooseFolderManually: () -> Unit = {},
    onPrivacyOptions: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isAdFree by viewModel.billing.isAdFree.collectAsState()
    val restoreStatus by viewModel.billing.restoreStatus.collectAsState()
    val products by viewModel.billing.products.collectAsState()
    val product = products.firstOrNull()
    val offer = product?.subscriptionOfferDetails?.firstOrNull()

    var wifiOnly by remember { mutableStateOf(AppPrefs.wifiOnly(context)) }
    var autoInstall by remember { mutableStateOf(AppPrefs.autoInstall(context)) }
    var pushOn by remember { mutableStateOf(com.evolutionofgames.zgamespsx.push.PushManager.isEnabled(context)) }

    val installedGames by viewModel.library.games.collectAsState()
    val usedBytes = remember(installedGames) { installedGames.sumOf { it.sizeBytes.coerceAtLeast(0L) } }
    val privacyOptionsNeeded by ConsentManager.privacyRequired.collectAsState()

    var crashReport by remember { mutableStateOf(CrashReporter.readReport(context)) }
    var showCrashDialog by remember { mutableStateOf(false) }

    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }
        catch (_: Exception) { "1.0" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold
        )

        // ── Ad-Free hero ─────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, PsxColors.Gold.copy(alpha = 0.7f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(PsxColors.Gold.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = PsxColors.Gold)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = if (isAdFree) "Ad-Free is active" else "Go Ad-Free",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PsxColors.Gold
                        )
                        Text(
                            text = if (isAdFree) "Thank you for supporting the app" else "No ads anywhere in the app",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when {
                        isAdFree -> "Banners, native ads and interstitials are all switched off while your subscription is active."
                        offer != null -> "Review the subscription price, billing period and terms in Google Play before subscribing."
                        else -> "Plans are unavailable right now. Try again later or tap Restore if you already subscribed."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { activity?.let { viewModel.billing.launchPurchase(it) } },
                        enabled = !isAdFree && offer != null,
                        colors = ButtonDefaults.buttonColors(containerColor = PsxColors.Gold),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isAdFree) "Active" else "Subscribe",
                            color = Color(0xFF1A1200),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.billing.restorePurchases() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restore") }
                }
                Text(restoreStatus, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp)
            }
        }

        // ── GAMES ────────────────────────────────────────────────────
        SectionLabel("GAMES")
        SettingsCard {
            run {
                val context = androidx.compose.ui.platform.LocalContext.current
                val builtIn = remember { com.evolutionofgames.zgamespsx.emulator.Ps1Launcher.hasBuiltInCore(context) }
                val externalReady = remember { viewModel.isEmulatorInstalled() }
                val emuReady = builtIn || externalReady
                SettingsRow(
                    icon = Icons.Filled.Memory,
                    title = "PS1 emulator",
                    subtitle = if (builtIn) "Built in. Games play right inside the app (system/BIOS folder: Android/data/${context.packageName}/files/system)."
                    else if (emuReady) "RetroArch found. Games open in it automatically. Add the SwanStation core in RetroArch once."
                    else "Emulator core missing from this build. See CORE_README.md.",
                    statusColor = if (emuReady) PsxColors.Success else PsxColors.Gold,
                    onClick = { },
                    trailing = if (emuReady) "Ready" else ""
                )
                RowDivider()
            }
            run {
                val adStatus by com.evolutionofgames.zgamespsx.ads.AdDiagnostics.last.collectAsState()
                if (com.evolutionofgames.zgamespsx.ads.AdConfig.current.diagnosticsEnabled) {
                    SettingsRow(icon = Icons.Filled.Info, title = "Ad status", subtitle = adStatus + " (code 3 = no fill / app not set up in AdMob)", onClick = { }, trailing = "")
                    RowDivider()
                }
            }
        }

        val appearance by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
        val lightMode by com.evolutionofgames.zgamespsx.remote.WpConfig.light.collectAsState()
        if (appearance.flag("light_mode", false)) {
            SectionLabel("APPEARANCE")
            SettingsCard {
                SettingsSwitchRow(icon = Icons.Filled.Star, title = "Light mode", subtitle = "Use a brighter appearance.", checked = lightMode, onChecked = { com.evolutionofgames.zgamespsx.remote.WpConfig.setLight(context, it) })
            }
        }

        // ── DOWNLOADS ────────────────────────────────────────────────
        SectionLabel("DOWNLOADS")
        SettingsCard {
            SettingsSwitchRow(
                icon = Icons.Filled.Wifi,
                title = "Download on Wi-Fi only",
                subtitle = "Pause big downloads until you're on Wi-Fi so they don't use mobile data.",
                checked = wifiOnly,
                onChecked = { wifiOnly = it; AppPrefs.setWifiOnly(context, it) }
            )
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Filled.Download,
                title = "Install automatically",
                subtitle = "Install a game as soon as its download finishes.",
                checked = autoInstall,
                onChecked = { autoInstall = it; AppPrefs.setAutoInstall(context, it) }
            )
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Filled.Notifications,
                title = "News & update notifications",
                subtitle = "Get told about new games and app updates.",
                checked = pushOn,
                onChecked = { pushOn = it; com.evolutionofgames.zgamespsx.push.PushManager.setEnabled(context, it) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.SportsEsports,
                title = "Storage used by games",
                subtitle = "${installedGames.size} installed · ${formatGameSize(usedBytes)}. Delete games from My Games to free space.",
                onClick = { onNav(NavDest.GAMES) },
                trailing = "Open"
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.CleaningServices,
                title = "Clear cache",
                subtitle = "Removes the saved game list and temporary files. Your games and saves stay.",
                onClick = { viewModel.clearCaches() }
            )
        }

        // ── SUPPORT ──────────────────────────────────────────────────
        SectionLabel("SUPPORT")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Share,
                title = "Share the app",
                subtitle = "Send ZGames PSX to a friend.",
                onClick = {
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Check out ZGames PSX: https://play.google.com/store/apps/details?id=${context.packageName}"
                            )
                        }
                        context.startActivity(Intent.createChooser(send, "Share app"))
                    } catch (_: Exception) { }
                }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Star,
                title = "Rate the app",
                subtitle = "A rating helps a lot.",
                onClick = {
                    val pkg = context.packageName
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                    } catch (_: Exception) {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                            )
                        } catch (_: Exception) { }
                    }
                }
            )
        }


        val metricsFlags by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
        if (metricsFlags.flag("usage_analytics", false)) {
            var metricsOptIn by remember { mutableStateOf(com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.optedIn()) }
            SectionLabel("OPTIONAL ANALYTICS")
            SettingsCard {
                SettingsSwitchRow(icon = Icons.Filled.PrivacyTip, title = "Share optional usage statistics", subtitle = "Share ad events, download counts, game IDs for per-game counts, and approximate usage time. No device ID is sent. Network logs may contain your IP. Off by default.", checked = metricsOptIn, onChecked = { metricsOptIn = it; com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.setOptIn(it) })
            }
        }
        // ── PRIVACY & LEGAL ──────────────────────────────────────────
        SectionLabel("PRIVACY & LEGAL")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.PrivacyTip,
                title = "Privacy policy",
                subtitle = "How the app and its ads use your data.",
                onClick = { openUrl(context, AppLinks.PRIVACY_POLICY) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Description,
                title = "Terms of use",
                subtitle = "The rules for using the app.",
                onClick = { openUrl(context, AppLinks.TERMS) }
            )
            if (privacyOptionsNeeded) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Ad privacy choices",
                    subtitle = "Review or change your ad consent.",
                    onClick = onPrivacyOptions
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Email,
                title = "Contact & report a problem",
                subtitle = AppLinks.CONTACT_EMAIL,
                onClick = {
                    try {
                        val mail = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:${AppLinks.CONTACT_EMAIL}")
                            putExtra(Intent.EXTRA_SUBJECT, "ZGames PSX $versionName feedback")
                        }
                        context.startActivity(mail)
                    } catch (_: Exception) { }
                }
            )
        }

        // ── HELP (only when a crash report exists) ───────────────────
        if (crashReport != null) {
            SectionLabel("HELP")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Filled.BugReport,
                    title = "Last crash report",
                    subtitle = "The app crashed once. View the saved report and send it so the cause can be fixed.",
                    onClick = { showCrashDialog = true },
                    trailing = "View"
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.BugReport,
                    title = "Clear crash report",
                    subtitle = "Delete the saved report from this device.",
                    onClick = {
                        CrashReporter.clearReport(context)
                        crashReport = null
                    }
                )
            }
            if (showCrashDialog) {
                crashReport?.let { report ->
                    CrashReportDialog(
                        report = report,
                        onDismiss = { showCrashDialog = false },
                        onClear = {
                            CrashReporter.clearReport(context)
                            crashReport = null
                            showCrashDialog = false
                        }
                    )
                }
            }
        }

        // ── APPEARANCE (designs published by the plugin) ─────────────
        val themeRc by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
        val chosenTheme by com.evolutionofgames.zgamespsx.remote.WpConfig.selectedTheme.collectAsState()
        if (themeRc.allowUserTheme && themeRc.themes.isNotEmpty()) {
            SectionLabel("APPEARANCE")
            SettingsCard {
                val activeId = chosenTheme.ifEmpty { themeRc.defaultTheme.ifEmpty { "zgames" } }
                themeRc.themes.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    SettingsRow(
                        icon = Icons.Filled.Info,
                        title = t.name,
                        subtitle = if (t.id == activeId) "In use" else "Tap to use",
                        onClick = { com.evolutionofgames.zgamespsx.remote.WpConfig.selectTheme(context, t.id) },
                        trailing = if (t.id == activeId) "✓" else "",
                        statusColor = androidx.compose.ui.graphics.Color(t.accent)
                    )
                }
            }
        }

        // ── MORE (rows added from the WordPress plugin) ──────────────
        val remote by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
        if (remote.menu.isNotEmpty()) {
            SectionLabel(remote.menuTitle.ifBlank { "More" }.uppercase())
            SettingsCard {
                remote.menu.forEachIndexed { i, m ->
                    if (i > 0) RowDivider()
                    SettingsRow(
                        icon = com.evolutionofgames.zgamespsx.ui.screens.remoteIcon(m.icon),
                        title = m.title,
                        subtitle = m.subtitle,
                        onClick = if (m.action != null) ({
                            com.evolutionofgames.zgamespsx.remote.RemoteActions.perform(context, m.action)
                        }) else null
                    )
                }
            }
        }

        // ── ABOUT ────────────────────────────────────────────────────
        SectionLabel("ABOUT")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = "ZGames PSX",
                subtitle = "Version $versionName\nBrowse games, download, play. Downloads go to Download/ZGames PSX.",
                onClick = null
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = PsxColors.Yellow
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    )
}

/** One settings line: round icon, title + subtitle, optional trailing label and chevron. */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: String? = null,
    statusColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = statusColor ?: PsxColors.Yellow, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = trailing, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PsxColors.Orange)
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) { }
}

/** A settings line with a switch on the right; the whole row toggles it. */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = PsxColors.Yellow, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = PsxColors.Orange)
        )
    }
}
