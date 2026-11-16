package com.evolutionofgames.zgamespsx.display

/**
 * Display sanitizing for game titles. HARD USER RULE: emulator names must
 * never appear in any user-visible string. Catalogue titles often contain
 * the standalone word for the PSP emulator (e.g. "... Mod ISO for
 * Android" posts titled "... PPSSPP ISO ..."), so the display transform
 * strips those words. The underlying [com.evolutionofgames.zgamespsx.model.Game]
 * data (title, download link) is never altered — only what the UI shows.
 *
 * Only whole standalone words are stripped (word boundaries), so a word
 * that merely contains one of these as a substring is left alone.
 * Runs of leftover whitespace collapse to single spaces.
 */
object TitleSanitizer {
    private val emulatorWord = Regex(
        "(?i)\\b(PPSSPP|EmuCoreX|PCSX2)\\b"
    )

    fun displayTitle(rawTitle: String): String {
        return rawTitle
            .replace(emulatorWord, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            // Clean up doubled separators left behind by a stripped word.
            .replace(Regex("\\s+-\\s+-\\s+"), " - ")
            .trim()
            .trim('-', ' ')
            .trim()
    }

    /** True if the visible text contains any emulator name (for audits). */
    fun containsEmulatorName(visibleText: String): Boolean {
        return emulatorWord.containsMatchIn(visibleText)
    }
}
