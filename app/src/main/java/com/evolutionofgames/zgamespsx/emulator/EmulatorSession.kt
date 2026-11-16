package com.evolutionofgames.zgamespsx.emulator

/**
 * Remembers that a game core (PS1 emulator) was started, so that when the
 * player exits the game and lands back in this app, MainActivity can show
 * the exit interstitial exactly once.
 */
object EmulatorSession {
    @Volatile private var pendingExitAd = false

    /** Call right after the core activity was started. */
    fun markGameStarted() { pendingExitAd = true }

    /** True once per game session (clears the flag). */
    fun consumeExit(): Boolean {
        val v = pendingExitAd
        pendingExitAd = false
        return v
    }
}
