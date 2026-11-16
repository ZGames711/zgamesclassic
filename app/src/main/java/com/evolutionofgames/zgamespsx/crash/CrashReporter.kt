package com.evolutionofgames.zgamespsx.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Best-effort app state snapshot for crash reports. Plain assignments
 * only — setting these can never throw, so reporting never makes a
 * crash worse. Values are deliberately plain words, not internals.
 */
object CrashState {
    @Volatile var screen: String = "unknown"
    @Volatile var interstitialState: String = "idle"
    @Volatile var rewardedUp: Boolean = false
    @Volatile var downloadRunning: Boolean = false
    @Volatile var installRunning: Boolean = false

    fun snapshot(): String {
        return try {
            "Screen: $screen\n" +
                "Interstitial: $interstitialState\n" +
                "Rewarded ad showing: $rewardedUp\n" +
                "Download running: $downloadRunning\n" +
                "Install running: $installRunning"
        } catch (_: Throwable) {
            "App state unavailable"
        }
    }
}

/**
 * Crash capture (method change after two interstitial-crash fix rounds
 * guessed without a trace): an uncaught-exception handler that writes
 * a report to app-private storage BEFORE the process dies, then chains
 * to Android's own handler so the normal crash dialog still appears.
 *
 * The report is NEVER sent anywhere automatically — it stays on the
 * device until the user copies it from the next launch's dialog or
 * from Settings. No new permissions, no network use.
 *
 * The handler itself must never throw: every step is wrapped, and a
 * failure to write simply means no report (the crash still proceeds
 * exactly as it would without this handler).
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash_report.txt"
    private const val TAG = "CrashReporter"

    fun install(context: Context) {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val appCtx = context.applicationContext
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeReport(appCtx, thread, throwable)
                } catch (_: Throwable) {
                    // Reporter failure must never mask the real crash.
                }
                try {
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
                } catch (_: Throwable) {
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not install crash handler", t)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val time = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            } catch (_: Throwable) {
                "unknown time"
            }
            // printStackTrace includes the full cause chain.
            val trace = try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                sw.toString()
            } catch (_: Throwable) {
                "Stack trace unavailable"
            }
            val problem = try {
                "${throwable.javaClass.name}: ${throwable.message}"
            } catch (_: Throwable) {
                "Unknown problem"
            }
            val threadName = try {
                thread.name
            } catch (_: Throwable) {
                "unknown thread"
            }
            val text = "ZGames PSX crash report\n" +
                "Time: $time\n" +
                "Thread: $threadName\n" +
                "Problem: $problem\n\n" +
                "What the app was doing:\n${CrashState.snapshot()}\n\n" +
                "Details:\n$trace"
            // Synchronous write + fsync: the report must survive the
            // process dying right after this handler returns. Nothing
            // is buffered past this point.
            val file = File(context.filesDir, FILE_NAME)
            FileOutputStream(file, false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        } catch (_: Throwable) {
            // Best effort only — a failed write must not change the crash.
        }
    }

    /** The saved report, or null when there is none. */
    fun readReport(context: Context): String? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) null else file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun hasReport(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).exists()
        } catch (_: Throwable) {
            false
        }
    }

    fun clearReport(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
