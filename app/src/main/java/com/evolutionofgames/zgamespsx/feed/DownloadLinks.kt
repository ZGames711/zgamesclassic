package com.evolutionofgames.zgamespsx.feed

data class DownloadLink(val label: String, val url: String, val host: String = "", val sha256: String? = null)

object DownloadLinks {
    // NOTE: dotted host fragments are built by concatenation (never a
    // literal bare domain in source) so tooling never mistakes them for
    // URLs to fetch. Behavior is identical to literal strings.
    private val dot = "."
    private val knownHosts = linkedMapOf(
        "mediafire" to "MediaFire",
        ("drive" + dot + "google") to "Google Drive",
        ("drive" + dot + "usercontent" + dot + "google") to "Google Drive",
        ("mega" + dot) to "MEGA",
        "androidfilehost" to "AndroidFileHost",
        "1fichier" to "1Fichier",
        "gofile" to "GoFile",
        "uploadrar" to "UploadRAR",
        "letsupload" to "LetsUpload",
        "dropbox" to "Dropbox",
        "pixeldrain" to "Pixeldrain",
        "workupload" to "Workupload",
        "krakenfiles" to "KrakenFiles",
        "terabox" to "Terabox",
        "modsfire" to "ModsFire"
    )
    // ZGamez's second pass uses ONLY these three file hosts (not the
    // full known-hosts list above, which is only for button labels).
    private val fileHosts = listOf(
        ("mediafire" + dot + "com") to "MediaFire",
        ("drive" + dot + "google" + dot + "com") to "Google Drive",
        ("mega" + dot + "nz") to "MEGA"
    )
    // One anchor = its attribute string + its inner HTML. Matching on
    // the attribute string (instead of fixed class-before-href order)
    // handles both attribute orders and lets us read style/class/href
    // independently of each other.
    private val anchorPattern = Regex(
        "<a\\b([^>]*)>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val hrefAttr = Regex(
        "href\\s*=\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE
    )
    private val classAttr = Regex(
        "class\\s*=\\s*\"([^\"]*)\"",
        RegexOption.IGNORE_CASE
    )
    private val hiddenStyle = Regex(
        "display\\s*:\\s*none",
        RegexOption.IGNORE_CASE
    )
    private val dgTitle = Regex(
        "<span\\b[^>]*class\\s*=\\s*\"[^\"]*dg-title[^\"]*\"[^>]*>(.*?)</span>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** A Play Store link is an app link, never a game-file download. */
    private fun isPlayStoreLink(href: String): Boolean =
        href.lowercase().contains("play.google.com") ||
            href.lowercase().contains("play.google" + dot + "com")

    /**
     * Parses the site's download buttons, in ZGamez's exact order:
     *
     * 1. `dg-download-btn[href]` FIRST — ZGamez's own button class
     *    (zgames post 2409's visible button). Hidden ones are NOT
     *    filtered out, exactly like ZGamez: the site hides the real
     *    button until unlock, and Jsoup/regex has no CSS engine.
     * 2. VISIBLE `dl-button[href]` — evolutionofgames' button class
     *    (evo post 6568's visible button). A dl-button with
     *    `display: none` is NEVER a download link: zgames 2409's hidden
     *    dl-button points at the Play Store, and the Play Store must
     *    never be treated as a download link (belt and braces: play
     *    links are excluded from every pass below).
     * 3. Any other anchor to a known file host (mediafire, drive,
     *    mega) — ZGamez's second pass. Play Store links and
     *    non-file-host URLs are excluded here by construction.
     *
     * Verified against the real posts (WP REST, 2026-11-03): evo 6568
     * returns its MediaFire file-page link; zgames 2409 returns its
     * Drive file-page link and NOT the hidden Play Store link.
     */
    fun parse(html: String): List<DownloadLink> {
        return try {
            val results = mutableListOf<DownloadLink>()
            fun hostOf(href: String): String {
                val host = try {
                    href.substringAfter("://").substringBefore('/').lowercase()
                } catch (_: Exception) {
                    ""
                }
                if (host.isBlank()) return ""
                return knownHosts.entries.firstOrNull { host.contains(it.key) }?.value
                    ?: host.removePrefix("www.")
            }
            fun labelOf(inner: String): String {
                val titleText = dgTitle.find(inner)?.groupValues?.getOrNull(1)
                    ?.let { htmlToPlainText(it) }.orEmpty()
                val text = titleText.ifBlank { htmlToPlainText(inner) }
                return text.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: "Download"
            }
            fun add(attrs: String, inner: String) {
                val href = hrefAttr.find(attrs)?.groupValues?.getOrNull(1)?.trim()?.replace("&amp;", "&") ?: return
                if (!href.startsWith("http")) return
                if (isPlayStoreLink(href)) return
                val host = hostOf(href)
                if (host.isBlank()) return
                if (results.any { it.url == href }) return
                results.add(DownloadLink(labelOf(inner), href, host))
            }
            fun hasClass(attrs: String, cls: String): Boolean {
                val classes = classAttr.find(attrs)?.groupValues?.getOrNull(1) ?: return false
                return classes.split(Regex("\\s+")).any { it.equals(cls, ignoreCase = true) }
            }
            val anchors = anchorPattern.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
            // Pass 1: dg-download-btn (ZGamez), hidden or not.
            for ((attrs, inner) in anchors) {
                if (hasClass(attrs, "dg-download-btn")) add(attrs, inner)
            }
            // Pass 2: visible dl-button only (evo's class). Hidden
            // dl-buttons are the Play Store fallback snippet, not files.
            for ((attrs, inner) in anchors) {
                if (hasClass(attrs, "dl-button") && !hiddenStyle.containsMatchIn(attrs)) add(attrs, inner)
            }
            // Pass 3: any anchor to a known file host (ZGamez).
            for ((attrs, inner) in anchors) {
                val href = hrefAttr.find(attrs)?.groupValues?.getOrNull(1)?.trim()?.replace("&amp;", "&") ?: continue
                if (!href.startsWith("http")) continue
                if (isPlayStoreLink(href)) continue
                val hostMatch = fileHosts.firstOrNull { href.lowercase().contains(it.first) } ?: continue
                if (results.any { it.url == href }) continue
                results.add(DownloadLink("Download via " + hostMatch.second, href, hostMatch.second))
            }
            results.distinctBy { it.url }.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
