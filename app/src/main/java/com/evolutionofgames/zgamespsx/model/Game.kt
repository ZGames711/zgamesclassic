package com.evolutionofgames.zgamespsx.model

/**
 * A GAME in the library. Catalogue entries come ONLY from the ZGames PSX plugin on
 * evolutionofgames.com — nothing is hosted by the app and no website articles are read.
 *
 * [downloadLink] is unused for catalogue entries: download links are loaded with the game's
 * details (see [com.evolutionofgames.zgamespsx.feed.GameCatalog.getGameDetails]).
 */
enum class Platform {
    PS1
}

enum class SourceSite(
    val host: String,
    val apiBase: String
) {
    EVO(
        host = "evolutionofgames.com",
        apiBase = "https://evolutionofgames.com/wp-json/zgpsx/v1/"
    );

    companion object {
        fun fromHost(host: String): SourceSite? =
            entries.firstOrNull { it.host.equals(host, ignoreCase = true) }
    }
}

data class Game(
    /** Stable id: "evo:<postId>". */
    val id: String,
    val postId: Long,
    val sourceSite: SourceSite,
    /** Raw catalogue title (never shown raw — see display sanitizing). */
    val title: String,
    /** Cover image URL (https). */
    val coverUrl: String?,
    val platform: Platform,
    /** Not used for catalogue entries (links load with the details). */
    val downloadLink: String?,
    /** Catalogue publish date (ISO 8601), for newest-first ordering. */
    val publishedAt: String,
    /** Local installed file path once downloaded, else null. */
    val localPath: String? = null,
    val categorySlugs: List<String>? = null
) {
    val isDownloaded: Boolean get() = localPath != null
}
