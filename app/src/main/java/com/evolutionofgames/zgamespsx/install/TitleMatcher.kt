package com.evolutionofgames.zgamespsx.install

/**
 * Pure-Kotlin (no Android) matching of a textures-only mod pack to an
 * already-installed game.
 *
 * Why this exists: TTT mods kept failing while Shin Budokai mods worked.
 * TTT packs are very often texture-only (bare textures.ini, or textures
 * under a mod-name folder) whose download title never contains the game's
 * full title — e.g. "DBZ TTT Super Dragon Ball Heroes Mod" vs the
 * installed "Dragon Ball Z: Tenkaichi Tag Team". Plain normalized
 * equality/containment never matches that. Abbreviation expansion
 * (DBZ -> Dragon Ball Z, TTT -> Tenkaichi Tag Team) plus the known TTT
 * game IDs close the gap — without ever guessing a wrong game: every
 * rule below still requires exactly one candidate.
 *
 * NOTE: the DROP_WORDS below are matching tokens only (code, never
 * shown in UI). They include the PSP core's common name because catalogue
 * titles contain it ("... ISO for Android" posts); dropping it from the
 * match key is what lets mod titles match installed game titles.
 */

/** One installed game, as offered to the matcher. */
data class InstalledGameRef(val title: String, val gameId: String)

object TitleMatcher {

    /**
     * Game IDs for Dragon Ball Z: Tenkaichi Tag Team, read from the PSP
     * core's own assets/compat.ini ("DBZ Tenkaichi Tag Team" section) —
     * not invented. Used only to recognise an installed TTT game, never
     * to invent a game the user doesn't have.
     */
    val TTT_GAME_IDS = setOf("ULJS00311", "ULAS42247", "ULUS10537", "ULUS10737")

    private val DROP_WORDS = setOf("mod", "psp", "iso", "cso", "android", "download", "ppsspp")
    private val VERSION_TOKEN = Regex("^v[0-9]+(\\.[0-9]+)*$")

    /**
     * Normalizes a title for comparison: lowercase, punctuation stripped,
     * common abbreviations expanded to the full game words, generic words
     * ("mod", "psp", "iso", version tags like "v2") dropped.
     */
    fun normalizeTitle(s: String): String {
        val words = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            .split(" ").filter { it.isNotEmpty() }
        val expanded = ArrayList<String>()
        for (w in words) {
            when (w) {
                "dbz" -> {
                    expanded.add("dragon"); expanded.add("ball"); expanded.add("z")
                }
                "ttt" -> {
                    expanded.add("tenkaichi"); expanded.add("tag"); expanded.add("team")
                }
                else -> expanded.add(w)
            }
        }
        return expanded.filter { it !in DROP_WORDS && !VERSION_TOKEN.matches(it) }.joinToString(" ")
    }

    /**
     * Matches a textures-only pack to an installed game, or null when no
     * safe match exists (caller then asks the user or skips the textures
     * — never installs them to a guessed game).
     *
     * Rules, in order:
     *  1. TTT marker: the pack's title or top folder mentions Tenkaichi
     *     Tag Team (or "TTT") AND exactly one installed game actually IS
     *     TTT (known ID or title) -> that game.
     *  2. Normalized title match: exactly one installed game whose
     *     normalized title equals, contains, or is contained by a
     *     candidate (download title or archive top folder), names >= 6
     *     chars for containment. Zero or ambiguous matches -> null.
     */
    fun matchGame(title: String, paths: List<String>, installed: List<InstalledGameRef>): String? {
        if (installed.isEmpty()) return null
        val candidates = mutableListOf(normalizeTitle(title))
        for (p in paths) {
            val top = ArchivePlanner.normalize(p)?.firstOrNull() ?: continue
            if (!top.contains('.')) candidates.add(normalizeTitle(top))
        }
        // Rule 1: TTT marker.
        if (candidates.any { it.contains("tenkaichi tag team") }) {
            val tttGames = installed.filter { g ->
                g.gameId.uppercase() in TTT_GAME_IDS ||
                    normalizeTitle(g.title).contains("tenkaichi tag team")
            }.map { it.gameId }.toSet()
            if (tttGames.size == 1) return tttGames.single()
            // Multiple TTT games installed: fall through to generic matching.
        }
        // Rule 2: normalized title match, exactly-one rule.
        val hits = LinkedHashSet<String>()
        for (g in installed) {
            val gt = normalizeTitle(g.title)
            if (gt.length < 4) continue
            for (c in candidates) {
                if (c.length < 4) continue
                if (c == gt || (c.length >= 6 && gt.length >= 6 && (c.contains(gt) || gt.contains(c)))) {
                    hits.add(g.gameId)
                    break
                }
            }
        }
        return if (hits.size == 1) hits.single() else null
    }
}
