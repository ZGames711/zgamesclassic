package com.evolutionofgames.zgamespsx.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Small user settings that several layers need (download engine,
 * settings screen, view model). Plain SharedPreferences so the
 * download engine can read them from any thread.
 */
object AppPrefs {
    private const val FILE = "zg_settings"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_AUTO_INSTALL = "auto_install"
    private const val KEY_NOTIF_ASKED = "notif_permission_asked"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun wifiOnly(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_WIFI_ONLY, false)
    fun setWifiOnly(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_WIFI_ONLY, v).apply() }

    /** Install a finished download automatically (default on, the previous behaviour). */
    fun autoInstall(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_INSTALL, true)
    fun setAutoInstall(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_AUTO_INSTALL, v).apply() }

    fun notificationPermissionAsked(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_NOTIF_ASKED, false)
    fun setNotificationPermissionAsked(ctx: Context) { sp(ctx).edit().putBoolean(KEY_NOTIF_ASKED, true).apply() }
}
