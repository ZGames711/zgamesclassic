package com.evolutionofgames.zgamespsx.feed

import com.evolutionofgames.zgamespsx.model.Game
import com.evolutionofgames.zgamespsx.model.Platform
import com.evolutionofgames.zgamespsx.model.SourceSite
import com.evolutionofgames.zgamespsx.remote.WpConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The game catalogue. Everything comes from the "ZGames PSX" WordPress plugin on
 * evolutionofgames.com — the games the site owner posts in the plugin, with their covers,
 * categories, descriptions and download buttons. The app hosts nothing and reads no
 * website articles.
 *
 * Endpoints (namespace zgpsx/v1):
 *   GET games?page=&per_page=&search=&category=   -> { games:[{id,title,cover,date,categories[]}], total_pages }
 *   GET games/{id}                                 -> one game with description, tags, slideshow, links[]
 *
 * Each call tries the pretty /wp-json/ URL first, then ?rest_route= (works when permalinks
 * or a security plugin block /wp-json/).
 */
class GameCatalog {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "ZGamesPSX/1.0 Android")
                    .header("Accept", "application/json")
                    .build()
            )
        }
        .build()

    /** GET [route] (e.g. "games?page=1") and parse the JSON object, trying both URL styles. */
    private fun fetch(route: String): JSONObject {
        val path = route.substringBefore('?')
        val query = route.substringAfter('?', "")
        val urls = listOf(
            "${WpConfig.SITE}/wp-json/zgpsx/v1/$route",
            "${WpConfig.SITE}/?rest_route=/zgpsx/v1/$path" + if (query.isNotEmpty()) "&$query" else ""
        )
        var last: Exception? = null
        for (u in urls) {
            try {
                val req = Request.Builder().url(u.toHttpUrl()).build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    return JSONObject(body)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw IOException("Catalogue unavailable: ${last?.message}")
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun https(s: String?): String? = s?.trim()?.takeIf { it.startsWith("https://") }

    private fun toGame(o: JSONObject): Game? {
        val id = o.optLong("id", 0L)
        val title = htmlToPlainText(o.optString("title"))
        if (id <= 0L || title.isBlank()) return null
        val cats = o.optJSONArray("categories")
        val slugs = if (cats == null) emptyList() else (0 until cats.length()).map { cats.optString(it) }.filter { it.isNotBlank() }.distinct()
        return Game(
            id = "evo:$id",
            postId = id,
            sourceSite = SourceSite.EVO,
            title = title,
            coverUrl = https(o.optString("cover")),
            platform = Platform.PS1,
            downloadLink = null,
            publishedAt = o.optString("date"),
            categorySlugs = slugs
        )
    }

    private fun parseList(o: JSONObject): List<Game> {
        val arr: JSONArray = o.optJSONArray("games") ?: return emptyList()
        val out = ArrayList<Game>(arr.length())
        for (i in 0 until arr.length()) {
            try { arr.optJSONObject(i)?.let { toGame(it) }?.let { out.add(it) } } catch (_: Exception) { }
        }
        return out
    }

    /** One page of the newest-first catalogue. Empty list = no more pages. Throws when the site is unreachable. */
    suspend fun getGames(page: Int, perPage: Int = 20): List<Game> = withContext(Dispatchers.IO) {
        parseList(fetch("games?page=$page&per_page=$perPage"))
    }

    suspend fun searchGames(query: String, page: Int, perPage: Int = 20): List<Game> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            parseList(fetch("games?page=$page&per_page=$perPage&search=${enc(query.trim())}"))
        }

    data class GameDetails(
        val links: List<DownloadLink>,
        val slideshow: WpSlideshow?,
        val description: String,
        val tags: List<String>
    )

    private fun parseDetails(o: JSONObject): GameDetails {
        val links = ArrayList<DownloadLink>()
        val la = o.optJSONArray("links")
        if (la != null) {
            for (i in 0 until la.length()) {
                val l = la.optJSONObject(i) ?: continue
                val url = l.optString("url").trim()
                if (!(url.startsWith("https://") || url.startsWith("http://"))) continue
                val host = try { android.net.Uri.parse(url).host.orEmpty().removePrefix("www.") } catch (_: Exception) { "" }
                val hash = l.optString("sha256").trim().takeIf { Regex("^[0-9a-fA-F]{64}$").matches(it) }?.lowercase()
                links.add(DownloadLink(l.optString("label").ifBlank { "Download" }, url, host, hash))
            }
        }
        val ss = o.optJSONObject("slideshow")
        val slides = if (ss != null && ss.optBoolean("enabled", false)) {
            val ia = ss.optJSONArray("images")
            val imgs = if (ia == null) emptyList() else (0 until ia.length()).mapNotNull { https(ia.optString(it)) }.distinct().take(12)
            if (imgs.isEmpty()) null else WpSlideshow(
                true, imgs,
                ss.optInt("height_dp", 220).coerceIn(120, 480),
                ss.optInt("interval_sec", 5).let { if (it == 0) 0 else it.coerceIn(3, 30) },
                if (ss.optString("image_fit") == "crop") "crop" else "fit"
            )
        } else null
        val ta = o.optJSONArray("tags")
        val tags = if (ta == null) emptyList() else (0 until ta.length()).map { htmlToPlainText(ta.optString(it)) }.filter { it.isNotBlank() }.distinct().take(16)
        return GameDetails(links, slides, o.optString("description").trim().take(12000), tags)
    }

    suspend fun getGameDetails(game: Game): GameDetails = withContext(Dispatchers.IO) {
        parseDetails(fetch("games/${game.postId}"))
    }

    suspend fun getDownloadLinks(game: Game): List<DownloadLink> = getGameDetails(game).links

    /** One game by id (used when a saved/opened game is no longer in memory). */
    suspend fun getGameById(site: SourceSite, postId: Long): Game? = withContext(Dispatchers.IO) {
        toGame(fetch("games/$postId"))
    }
}
