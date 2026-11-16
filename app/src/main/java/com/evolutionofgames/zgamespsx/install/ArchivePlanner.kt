package com.evolutionofgames.zgamespsx.install

/**
 * Pure-Kotlin (no Android) logic that decides where each file of a
 * downloaded mod archive belongs inside the PSP memory-stick folder:
 *
 *   <memstick>/GAME/…        games (.iso/.cso/.pbp/.chd, or EBOOT.PBP folders)
 *   <memstick>/SAVEDATA/…    save-data folders
 *   <memstick>/TEXTURES/<ID>/…  texture packs
 *   <memstick>/CHEATS, PLUGINS, STATE, SYSTEM  (only when the archive says so)
 *
 * Texture rule that matters most: stock PSP cores only ever read
 * TEXTURES/<gameId>/… — so a first segment that is NOT a game ID is a
 * mod-name folder, not a real destination. Installing it verbatim puts
 * textures where the core never looks and the mod silently "does
 * nothing" in-game (the classic TTT failure: packs ship
 * TEXTURES/<modname>/… instead of TEXTURES/<gameId>/…). The fix drops
 * exactly that one non-ID first segment and puts the files under the
 * resolved game ID — or NeedsId when the ID isn't known yet, so the
 * installer can resolve it (title match / user pick) instead of writing
 * a dead path.
 */
object ArchivePlanner {

    val GAME_ID_REGEX = Regex("^[A-Z]{4}[0-9]{5}$")
    private val ANCHORS = setOf("GAME", "SAVEDATA", "TEXTURES", "CHEATS", "PLUGINS")
    private val PSP_SUBROOTS = ANCHORS + setOf("STATE", "SYSTEM")
    // "bin" = PS2 BIN/CUE images (the PSP core never uses BIN/CUE). A PS2
    // zip with only a .bin (+ .cue) must map to something. "cue" is
    // handled separately in map() (sidecar, never a standalone game) and
    // never counts in analyze().
    private val GAME_FILE_EXT = setOf("iso", "cso", "pbp", "chd", "zso", "bin", "gz", "img", "mdf")
    private val JUNK_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    sealed class Mapped {
        data class Dest(val path: String) : Mapped()
        /** File belongs in TEXTURES/<gameId>/<rest>; caller must supply the id. */
        data class NeedsId(val rest: String) : Mapped()
        object Skip : Mapped()
    }

    class Analysis(
        val needsGameId: Boolean,
        val hasGameImage: Boolean,
        val installable: Int,
    )

    fun isGameId(s: String) = GAME_ID_REGEX.matches(s.uppercase())

    /** Splits on / and \, drops "", "." and rejects ".." / drive letters (returns null). */
    fun normalize(raw: String): List<String>? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        for (p in parts) {
            if (p == ".." || p.contains(':') || p.contains('\u0000')) return null
        }
        return parts
    }

    private fun isJunk(parts: List<String>): Boolean {
        if (parts.any { it.equals("__MACOSX", true) }) return true
        return parts.last().lowercase() in JUNK_NAMES || parts.last().startsWith("._")
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Per-archive context computed once from the full file list. */
    class Context internal constructor(
        internal val gameFolders: Map<List<String>, String>,  // prefix -> "GAME"/"SAVEDATA"
        internal val barePackBase: List<String>?,             // folder holding textures.ini
        internal val hasTexturesAnchor: Boolean,              // any path has a TEXTURES folder
    )

    fun buildContext(filePaths: List<String>): Context {
        val folders = HashMap<List<String>, String>()
        var bare: List<String>? = null
        val norm = filePaths.mapNotNull { normalize(it) }.filter { !isJunk(it) }
        for (p in norm) {
            val name = p.last()
            if (p.size >= 2) {
                val parentIdx = p.size - 2
                val prefix = p.subList(0, parentIdx + 1)
                if (name.equals("EBOOT.PBP", true) && !p.any { it.equals("PSP_GAME", true) }) {
                    folders[prefix] = "GAME"
                }
            }
        }
        for (p in norm) {
            if (p.size >= 2 && p.last().equals("PARAM.SFO", true)) {
                val prefix = p.subList(0, p.size - 1)
                val parent = prefix.last()
                if (parent.equals("PSP_GAME", true) || parent.equals("SYSTEM", true)) continue
                if (folders[prefix] == null) folders[prefix] = "SAVEDATA"
            }
        }
        val hasAnchorOrId = norm.any { p -> p.dropLast(1).any { seg -> seg.uppercase() in PSP_SUBROOTS || isGameId(seg) } }
        if (!hasAnchorOrId) {
            val ini = norm.firstOrNull { it.last().equals("textures.ini", true) }
            if (ini != null) bare = ini.dropLast(1)
        }
        val hasTexturesAnchor = norm.any { p -> p.dropLast(1).any { seg -> seg.equals("TEXTURES", true) } }
        return Context(folders, bare, hasTexturesAnchor)
    }

    private fun mapTexturesRest(rest: List<String>, gameId: String?): Mapped {
        if (rest.isEmpty()) return Mapped.Skip
        if (rest.size == 1) {
            // file straight under TEXTURES (e.g. textures.ini) -> needs an id
            return if (gameId != null) Mapped.Dest("TEXTURES/$gameId/${rest[0]}")
            else Mapped.NeedsId(rest[0])
        }
        if (isGameId(rest[0])) {
            return Mapped.Dest((listOf("TEXTURES", rest[0].uppercase()) + rest.drop(1)).joinToString("/"))
        }
        val below = rest.drop(1).joinToString("/")
        return if (gameId != null) Mapped.Dest("TEXTURES/$gameId/$below")
        else Mapped.NeedsId(below)
    }

    fun map(rawPath: String, ctx: Context, gameId: String?): Mapped {
        val p = normalize(rawPath) ?: return Mapped.Skip
        if (isJunk(p)) return Mapped.Skip
        val last = p.last()

        // 1) explicit PSP/<root>/… mirror
        for (i in 0 until p.size - 1) {
            if (p[i].equals("PSP", true) && p[i + 1].uppercase() in PSP_SUBROOTS && i + 2 < p.size) {
                val root = p[i + 1].uppercase()
                val rest = p.drop(i + 2)
                if (root == "TEXTURES") return mapTexturesRest(rest, gameId)
                return Mapped.Dest((listOf(root) + rest).joinToString("/"))
            }
        }
        // 2) anchor folder GAME / SAVEDATA / TEXTURES / CHEATS / PLUGINS
        for (i in 0 until p.size - 1) {
            if (p[i].uppercase() in ANCHORS) {
                val root = p[i].uppercase()
                val rest = p.drop(i + 1)
                if (root == "TEXTURES") return mapTexturesRest(rest, gameId)
                return Mapped.Dest((listOf(root) + rest).joinToString("/"))
            }
        }
        // 3) EBOOT / PARAM.SFO folders (GAME or SAVEDATA)
        var bestPrefix: List<String>? = null
        for ((prefix, _) in ctx.gameFolders) {
            if (p.size > prefix.size && p.subList(0, prefix.size) == prefix) {
                if (bestPrefix == null || prefix.size > bestPrefix.size) bestPrefix = prefix
            }
        }
        if (bestPrefix != null) {
            val root = ctx.gameFolders[bestPrefix]!!
            val rest = p.drop(bestPrefix.size - 1) // keep the folder name itself
            return Mapped.Dest((listOf(root) + rest).joinToString("/"))
        }
        // 3b) game images beat bare game-ID folders: an ISO inside a
        // game-ID-named folder is a game, not a texture (the old order
        // routed ULUS10041/game.iso to TEXTURES/ULUS10041/game.iso,
        // where it could never be played). Texture files are unaffected
        // (they are not game images); anchor/EBOOT rules above still win.
        if (ext(last) in GAME_FILE_EXT) return Mapped.Dest("GAME/$last")
        // 4) bare game-ID folder = texture pack for that game
        for (i in 0 until p.size - 1) {
            if (isGameId(p[i])) {
                return Mapped.Dest((listOf("TEXTURES", p[i].uppercase()) + p.drop(i + 1)).joinToString("/"))
            }
        }
        // 5) game images
        if (ext(last) in GAME_FILE_EXT) return Mapped.Dest("GAME/$last")
        // 5b) .cue sidecar: rides along with its .bin into GAME/ so the
        // core can use it for track info, but it is never a game by
        // itself. A bare .cue with no .bin is skipped by analyze()'s
        // count, so a cue-only archive still errors. .mds is the same
        // idea for .mdf (Alcohol's descriptor sidecar).
        if (ext(last) == "cue" || ext(last) == "mds") return Mapped.Dest("GAME/$last")
        // 6) bare texture pack (textures.ini with no id anywhere)
        val base = ctx.barePackBase
        if (base != null && p.size > base.size && p.subList(0, base.size) == base) {
            val rest = p.drop(base.size).joinToString("/")
            return if (gameId != null) Mapped.Dest("TEXTURES/$gameId/$rest") else Mapped.NeedsId(rest)
        }
        // 7) lone textures.ini next to a TEXTURES folder (e.g. a pack with
        // "ModName/textures.ini" + "ModName/TEXTURES/…"): the ini belongs
        // with the pack's textures, not skipped. Only fires when no earlier
        // rule matched, so real TEXTURES/<id>/textures.ini paths are
        // untouched (rules 1-2 already returned for those).
        if (last.equals("textures.ini", true) && ctx.hasTexturesAnchor) {
            return if (gameId != null) Mapped.Dest("TEXTURES/$gameId/textures.ini")
            else Mapped.NeedsId("textures.ini")
        }
        return Mapped.Skip
    }

    fun analyze(filePaths: List<String>): Analysis {
        val ctx = buildContext(filePaths)
        var needs = false
        var image = false
        var count = 0
        for (f in filePaths) {
            when (val m = map(f, ctx, null)) {
                is Mapped.Dest -> {
                    // A .cue/.mds sidecar never makes an archive installable
                    // on its own (a cue without its .bin is useless): it is
                    // still written when its image is present, it just
                    // doesn't count here.
                    if (ext(m.path) == "cue" || ext(m.path) == "mds") continue
                    count++
                    if (m.path.startsWith("GAME/") && ext(m.path) in GAME_FILE_EXT) image = true
                }
                is Mapped.NeedsId -> { count++; needs = true }
                Mapped.Skip -> {}
            }
        }
        return Analysis(needs, image, count)
    }
}
