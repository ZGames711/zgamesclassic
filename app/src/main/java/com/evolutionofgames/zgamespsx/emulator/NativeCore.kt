package com.evolutionofgames.zgamespsx.emulator

import android.view.Surface

/** JNI bridge to the native libretro frontend (src/main/cpp/frontend.cpp). */
object NativeCore {
    init { System.loadLibrary("psxfrontend") }

    external fun nativeLoadCore(corePath: String, systemDir: String, saveDir: String): Boolean
    external fun nativeLoadGame(path: String): Boolean
    /** Sets one SwanStation core option (e.g. "duckstation_GPU.Renderer") before the core loads. */
    external fun nativeSetVariable(key: String, value: String)
    /** Writes a save state file. Must be called on the emulation thread. */
    external fun nativeSaveState(path: String): Boolean
    /** Loads a save state file. Must be called on the emulation thread. */
    external fun nativeLoadState(path: String): Boolean
    /** Analog stick: stick 0 = left, 1 = right; x/y in -32767..32767 (up = negative y). */
    external fun nativeSetAnalog(stick: Int, x: Int, y: Int)
    /** Analog sticks on/off (false = digital pad only). Applies at once when a game is running. */
    external fun nativeSetAnalogEnabled(enabled: Boolean)
    external fun nativeSampleRate(): Int
    external fun nativeFps(): Double
    external fun nativeSetSurface(surface: Surface?)
    /** Runs one frame; returns audio samples written (L/R interleaved), -1 not loaded, -2 core quit. */
    external fun nativeRunFrame(audio: ShortArray): Int
    external fun nativeSetInput(mask: Int)
    external fun nativeUnload()
}
