package com.evolutionofgames.zgamespsx.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the WordPress plugin can ask the app to show, in a vocabulary small
 * enough that the plugin can grow forever without an app update:
 *
 *  - unknown block types are skipped, unknown fields are ignored;
 *  - every parse step is wrapped, so one bad block never hides the others;
 *  - nothing here executes server-supplied code.
 */
data class RAction(val type: String, val value: String)

/** Session limit / rewarded extension / offline grace / autosave / download ad pause, from the plugin's "session_limit" block. */
data class RSession(
    val timerEnabled: Boolean = false,
    val limitMinutes: Int = 60,
    val windowHours: Int = 24,
    val rewardedExtend: Boolean = true,
    val extendMinutes: Int = 60,
    val maxExtensions: Int = 0,
    val grantWhenNoAd: Boolean = true,
    val premiumExempt: Boolean = true,
    val warnMinutes: Int = 5,
    val offlineGrace: Boolean = true,
    val offlineMinutes: Int = 30,
    val autosave: Boolean = true,
    val autosaveMinutes: Int = 10,
    val halfwayPause: Boolean = true,
    val halfwayPercent: Int = 50,
    val halfwayFreeOk: Boolean = true,
    val unlockOnDownload: Boolean = false,
    val unlockTimeoutSec: Int = 8
) {
    companion object {
        fun parse(o: JSONObject?): RSession {
            if (o == null) return RSession()
            val d = RSession()
            return RSession(
                timerEnabled = o.optBoolean("timer_enabled", d.timerEnabled),
                limitMinutes = o.optInt("limit_minutes", d.limitMinutes).coerceIn(5, 600),
                windowHours = o.optInt("window_hours", d.windowHours).coerceIn(1, 168),
                rewardedExtend = o.optBoolean("rewarded_extend", d.rewardedExtend),
                extendMinutes = o.optInt("extend_minutes", d.extendMinutes).coerceIn(5, 600),
                maxExtensions = o.optInt("max_extensions", 0).coerceIn(0, 50),
                grantWhenNoAd = o.optString("ad_unavailable", "grant") != "block",
                premiumExempt = o.optBoolean("premium_exempt", d.premiumExempt),
                warnMinutes = o.optInt("warn_minutes", d.warnMinutes).coerceIn(0, 30),
                offlineGrace = o.optBoolean("offline_grace", d.offlineGrace),
                offlineMinutes = o.optInt("offline_minutes", d.offlineMinutes).coerceIn(1, 240),
                autosave = o.optBoolean("autosave", d.autosave),
                autosaveMinutes = o.optInt("autosave_minutes", d.autosaveMinutes).coerceIn(0, 60),
                halfwayPause = o.optBoolean("halfway_pause", d.halfwayPause),
                halfwayPercent = o.optInt("halfway_percent", 50).coerceIn(10, 90),
                halfwayFreeOk = o.optBoolean("halfway_free_ok", d.halfwayFreeOk),
                unlockOnDownload = o.optBoolean("unlock_on_download", d.unlockOnDownload),
                unlockTimeoutSec = o.optInt("unlock_timeout", d.unlockTimeoutSec).coerceIn(3, 30)
            )
        }
    }
}

data class RItem(
    val title: String,
    val subtitle: String,
    val image: String?,
    val action: RAction?
)

data class RBlock(
    val id: String,
    val type: String,
    val slot: String,
    val title: String,
    val text: String,
    val image: String?,
    val accent: Long?,
    val dismissible: Boolean,
    val action: RAction?,
    val items: List<RItem>,
    val heightDp: Int = 220,
    val intervalSec: Int = 5,
    val imageFit: String = "fit",
    val collapsible: Boolean = false,
    val postTags: Boolean = false
)

data class RAppPost(val page: String, val title: String, val image: String?, val date: Long, val pinned: Boolean, val categories: List<String> = emptyList())

data class RPage(
    val id: String,
    val title: String,
    val kind: String,
    val url: String?,
    val blocks: List<RBlock>
)

data class RMenuItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val action: RAction?
)

data class RTheme(
    val id: String,
    val name: String,
    val accent: Long,
    val background: Long,
    val surface: Long,
    val text: Long
)

data class RemoteContent(
    val blocks: List<RBlock> = emptyList(),
    val appPostFeedSupported: Boolean = false,
    val appPosts: List<RAppPost> = emptyList(),
    val categoryPosts: List<RAppPost> = emptyList(),
    val pages: List<RPage> = emptyList(),
    val menuTitle: String = "",
    val menu: List<RMenuItem> = emptyList(),
    val features: Map<String, Boolean> = emptyMap(),
    val accent: Long? = null,
    val refreshSec: Int = 600,
    /** libretro core RetroArch loads: "swanstation" (default) or "pcsx_rearmed". Set in the plugin. */
    val emulatorCore: String = "swanstation",
    /** True once a real config (fetched or cached) has been parsed. */
    val loaded: Boolean = false,
    val themes: List<RTheme> = emptyList(),
    val defaultTheme: String = "",
    val allowUserTheme: Boolean = true,
    /** "page" = Home is replaced by [homePage]; otherwise the native game feed. */
    val homeMode: String = "native",
    val homePage: String = "",
    /** Game card layout chosen in the plugin: vertical (default), classic, overlay, poster, compact, list, large. */
    val tileStyle: String = "vertical",
    val tileRadius: Int = 16,
    val navStyle: String = "floating",
    val navColor: Long? = null,
    val navAccent: Long? = null,
    val navIconSize: Int = 24,
    val navOpacity: Int = 100,
    val navRadius: Int = 18,
    val navEffect: String = "glow",
    val siteSlidesHeight: Int = 0,
    val visualEffect: String = "midnight",
    val tileEffect: String = "slide",
    val session: RSession = RSession()
) {
    fun flag(key: String, default: Boolean): Boolean = features[key] ?: default

    companion object {
        val EMPTY = RemoteContent()

        fun parse(json: JSONObject): RemoteContent {
            val blocks = safeList(json.optJSONArray("blocks")) { parseBlock(it) }
            val pages = safeList(json.optJSONArray("pages")) { parsePage(it) }
            val menuObj = json.optJSONObject("menu")
            val menu = safeList(menuObj?.optJSONArray("items")) { parseMenu(it) }
            val features = HashMap<String, Boolean>()
            json.optJSONObject("features")?.let { f ->
                f.keys().forEach { k -> if (!f.isNull(k)) features[k] = f.optBoolean(k, false) }
            }
            val accent = parseColor(json.optJSONObject("theme")?.optString("accent"))
            val refresh = json.optJSONObject("app")?.optInt("refresh_sec", 600) ?: 600
            return RemoteContent(
                blocks = blocks,
                appPostFeedSupported = json.optJSONObject("app_posts")?.has("home_items") == true,
                appPosts = parseAppPosts(json.optJSONObject("app_posts")?.optJSONArray("home_items")),
                categoryPosts = parseAppPosts(json.optJSONObject("app_posts")?.optJSONArray("items")),
                pages = pages,
                menuTitle = menuObj?.optString("title").orEmpty(),
                menu = menu,
                features = features,
                session = RSession.parse(json.optJSONObject("session_limit")),
                accent = accent,
                refreshSec = refresh.coerceIn(60, 3600),
                emulatorCore = json.optJSONObject("app")?.optString("emulator_core").orEmpty()
                    .takeIf { it == "swanstation" || it == "pcsx_rearmed" } ?: "swanstation",
                loaded = true,
                themes = safeList(json.optJSONArray("themes")) { parseTheme(it) },
                defaultTheme = json.optJSONObject("design")?.optString("default_theme").orEmpty(),
                allowUserTheme = json.optJSONObject("design")?.optBoolean("allow_user_choice", true) ?: true,
                homeMode = json.optJSONObject("home")?.optString("mode").orEmpty().ifEmpty { "native" },
                homePage = json.optJSONObject("home")?.optString("page").orEmpty(),
                tileStyle = json.optJSONObject("design")?.optString("tile_style").orEmpty()
                    .takeIf { it in setOf("vertical", "classic", "overlay", "poster", "compact", "list", "large", "arcade_grid") } ?: "vertical",
                tileRadius = (json.optJSONObject("design")?.optInt("tile_radius", 16) ?: 16).coerceIn(0, 28),
                navStyle = json.optJSONObject("design")?.optString("nav_style").orEmpty().takeIf { it in setOf("classic", "glossy", "3d", "floating", "capsule", "arcade") } ?: "floating",
                navColor = parseColor(json.optJSONObject("design")?.optString("nav_color")),
                navAccent = parseColor(json.optJSONObject("design")?.optString("nav_accent")),
                navIconSize = (json.optJSONObject("design")?.optInt("nav_icon_size", 24) ?: 24).coerceIn(18, 32),
                navOpacity = (json.optJSONObject("design")?.optInt("nav_opacity", 100) ?: 100).coerceIn(50, 100),
                navRadius = (json.optJSONObject("design")?.optInt("nav_radius", 18) ?: 18).coerceIn(0, 40),
                navEffect = json.optJSONObject("design")?.optString("nav_effect").orEmpty().takeIf { it in setOf("none", "pop", "glow") } ?: "glow",
                visualEffect = json.optJSONObject("design")?.optString("visual_effect").orEmpty().takeIf { it in setOf("none", "glass", "shadow", "aurora", "midnight", "sunset", "sapphire", "emerald", "rose", "neon") } ?: "midnight",
                siteSlidesHeight = (json.optJSONObject("design")?.optInt("site_slides_height", 0) ?: 0).let { if (it <= 0) 0 else it.coerceIn(120, 480) },
                tileEffect = json.optJSONObject("design")?.optString("tile_effect").orEmpty().takeIf { it in setOf("none", "fade", "slide") } ?: "slide"
            )
        }

        private fun <T> safeList(arr: JSONArray?, f: (JSONObject) -> T?): List<T> {
            if (arr == null) return emptyList()
            val out = ArrayList<T>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.optJSONObject(i) ?: continue
                    f(o)?.let { out.add(it) }
                } catch (_: Throwable) { /* skip the bad entry, keep the rest */ }
            }
            return out
        }

        private fun str(o: JSONObject, k: String): String =
            if (o.isNull(k)) "" else o.optString(k, "").trim()

        fun parseColor(s: String?): Long? {
            val v = s?.trim().orEmpty()
            if (!Regex("^#[0-9a-fA-F]{6}$").matches(v)) return null
            return 0xFF000000L or v.substring(1).toLong(16)
        }

        private fun parseTheme(o: JSONObject): RTheme? {
            val id = str(o, "id").ifEmpty { return null }
            return RTheme(
                id = id,
                name = str(o, "name").ifEmpty { id },
                accent = parseColor(str(o, "accent")) ?: return null,
                background = parseColor(str(o, "background")) ?: return null,
                surface = parseColor(str(o, "surface")) ?: return null,
                text = parseColor(str(o, "text")) ?: return null
            )
        }

        private fun parseAppPosts(items: JSONArray?): List<RAppPost> = safeList(items) { o ->
            val page = str(o, "page"); val title = str(o, "title")
            if (!Regex("^ap-[0-9]+$").matches(page) || title.isBlank()) null else {
                val arr = o.optJSONArray("categories")
                val cats = if (arr == null) emptyList() else (0 until minOf(arr.length(), 30)).map { arr.optString(it) }.filter { it.isNotBlank() }
                RAppPost(page, title, httpsOrNull(str(o, "image")), o.optLong("date", 0), o.optBoolean("pinned", false), cats)
            }
        }

        private fun parseAction(o: JSONObject?): RAction? {
            if (o == null) return null
            val type = str(o, "type")
            if (type.isEmpty() || type == "none") return null
            return RAction(type, str(o, "value"))
        }

        private fun httpsOrNull(s: String): String? = s.takeIf { it.startsWith("https://") }

        private fun parseItem(o: JSONObject) = RItem(
            title = str(o, "title"),
            subtitle = str(o, "subtitle"),
            image = httpsOrNull(str(o, "image")),
            action = parseAction(o.optJSONObject("action"))
        )

        private fun parseBlock(o: JSONObject): RBlock? {
            val type = str(o, "type")
            if (type !in KNOWN_BLOCKS) return null // a block type from a newer plugin: skip it
            val id = str(o, "id").ifEmpty { return null }
            return RBlock(
                id = id,
                type = type,
                slot = str(o, "slot"),
                title = str(o, "title"),
                text = str(o, "text"),
                image = httpsOrNull(str(o, "image")),
                accent = parseColor(str(o, "accent")),
                dismissible = o.optBoolean("dismissible", false),
                action = parseAction(o.optJSONObject("action")),
                items = safeList(o.optJSONArray("items")) { parseItem(it) },
                heightDp = o.optInt("height_dp", 220).coerceIn(120, 480),
                intervalSec = o.optInt("interval_sec", 5).let { if (it == 0) 0 else it.coerceIn(3, 30) },
                imageFit = if (str(o, "image_fit") == "crop") "crop" else "fit",
                collapsible = o.optBoolean("collapsible", false),
                postTags = o.optBoolean("post_tags", false)
            )
        }

        private fun parsePage(o: JSONObject): RPage? {
            val id = str(o, "id").ifEmpty { return null }
            val kind = str(o, "kind").ifEmpty { "blocks" }
            if (kind != "blocks" && kind != "web") return null
            var url: String? = null
            if (kind == "web") {
                url = httpsOrNull(str(o, "url")) ?: return null
                // In-app web pages may only come from the plugin's own site.
                val host = android.net.Uri.parse(url).host?.lowercase() ?: return null
                val site = android.net.Uri.parse(WpConfig.SITE).host?.lowercase() ?: return null
                if (host != site && !host.endsWith(".$site")) return null
            }
            return RPage(
                id = id,
                title = str(o, "title").ifEmpty { "Page" },
                kind = kind,
                url = url,
                blocks = safeList(o.optJSONArray("blocks")) { parseBlock(it) }
            )
        }

        private fun parseMenu(o: JSONObject): RMenuItem? {
            val title = str(o, "title").ifEmpty { return null }
            return RMenuItem(
                id = str(o, "id").ifEmpty { title },
                title = title,
                subtitle = str(o, "subtitle"),
                icon = str(o, "icon").ifEmpty { "link" },
                action = parseAction(o.optJSONObject("action"))
            )
        }

        private val KNOWN_BLOCKS = setOf("banner", "text", "buttons", "carousel", "list", "divider", "image", "notice", "slideshow")
    }
}
