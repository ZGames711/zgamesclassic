package com.evolutionofgames.zgamespsx.feed

/** Optional image slideshow for a game page, supplied by the plugin. */
data class WpSlideshow(
    val enabled: Boolean = false,
    val images: List<String> = emptyList(),
    val heightDp: Int = 220,
    val intervalSec: Int = 5,
    val imageFit: String = "fit"
)

/**
 * HTML → plain text for short fields (titles, excerpts). Titles are short, so tag-stripping +
 * common-entity decoding is enough, and it can never throw on malformed markup.
 */
fun htmlToPlainText(html: String): String {
    return try {
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) return html.trim()
        var text = html.replace(Regex("<[^>]*>"), " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
        text = text.replace(Regex("&#(\\d+);")) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        text.replace(Regex("\\s+"), " ").trim()
    } catch (_: Exception) {
        ""
    }
}
