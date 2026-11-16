package com.evolutionofgames.zgamespsx.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.evolutionofgames.zgamespsx.MainActivity
import com.evolutionofgames.zgamespsx.R
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in notifications (no Firebase). The WordPress plugin publishes them at
 * /wp-json/zgpsx/v1/notifications; this app checks that feed on open and every
 * ~15 minutes in the background (WorkManager) and shows new ones itself.
 */
object PushManager {

    const val CHANNEL_ID = "zgames_updates"
    private const val PREFS = "psx_push"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST = "last_id"
    private const val WORK_NAME = "zgames_notification_poll"
    private const val FEED_URL = "${com.evolutionofgames.zgamespsx.remote.WpConfig.SITE}/wp-json/zgpsx/v1/notifications"
    private const val FEED_URL_PLAIN = "${com.evolutionofgames.zgamespsx.remote.WpConfig.SITE}/?rest_route=/zgpsx/v1/notifications"
    private const val FEED_URL_DIRECT = "${com.evolutionofgames.zgamespsx.remote.WpConfig.SITE}/?zgac=notifications"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Accept-Encoding").build())
            }
            .build()
    }

    /** User switch in Settings. */
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        schedule(ctx)
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "News & updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "New games and app updates"
                    }
                )
            }
        } catch (_: Throwable) { }
    }

    /** Application.onCreate. */
    fun init(ctx: Context) {
        NotificationInbox.init(ctx)
        ensureChannel(ctx)
        schedule(ctx)
    }

    /** Background check about every 15 minutes (Android's minimum), only with a network. */
    private fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!isEnabled(ctx)) { wm.cancelUniqueWork(WORK_NAME); return }
            val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (t: Throwable) { Log.w("PushManager", "schedule failed: ${t.message}") }
    }

    /** Legacy hook from the config document; nothing to do now. */
    fun onRemoteConfig(ctx: Context, push: JSONObject?) { }

    /** Fetches new notifications and shows them. Blocking: call off the main thread. */
    fun poll(ctx: Context) {
        val app = ctx.applicationContext
        val first = !sp(app).contains(KEY_LAST)
        val last = sp(app).getInt(KEY_LAST, 0)
        var body: String? = null
        for (u in listOf(FEED_URL, FEED_URL_PLAIN, FEED_URL_DIRECT)) {
            try {
                val sep = if (u.contains("?")) "&" else "?"
                val req = Request.Builder().url("$u${sep}since=$last")
                    .header("User-Agent", "ZGamesPSX/1.0 Android").build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) body = r.body?.string()
                }
                if (body != null) break
            } catch (t: Throwable) { Log.w("PushManager", "feed fetch failed: ${t.message}") }
        }
        val json = try { JSONObject(body ?: return) } catch (_: Throwable) { return }
        val latest = json.optInt("latest", last)
        val items = json.optJSONArray("items")
        // First ever run: remember where the feed is, don't dump its history on the user.
        if (!first && items != null) {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val m = HashMap<String, String>()
                o.keys().forEach { k -> m[k] = o.optString(k) }
                try { show(app, m) } catch (_: Throwable) { }
            }
        }
        sp(app).edit().putInt(KEY_LAST, maxOf(latest, last)).apply()
    }

    class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            try {
                poll(applicationContext)
                try { com.evolutionofgames.zgamespsx.community.CommunityApi.pollReplies(applicationContext) } catch (_: Throwable) { }
                try { com.evolutionofgames.zgamespsx.remote.WpConfig.refresh(applicationContext, force = true) } catch (_: Throwable) { }
                Result.success()
            } catch (_: Throwable) { Result.retry() }
        }
    }

    /** Builds and shows the notification for one feed item. */
    fun show(ctx: Context, data: Map<String, String>) {
        if (!isEnabled(ctx)) return
        val title = data["title"].orEmpty().ifBlank { "ZGames PSX" }
        val body = data["body"].orEmpty()
        if (body.isBlank() && data["title"].isNullOrBlank()) return
        if (com.evolutionofgames.zgamespsx.remote.WpConfig.flag("notification_inbox", false)) NotificationInbox.record(ctx, data)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)

        val url = data["url"]?.trim().orEmpty()
        val action = data["action"].orEmpty()
        val intent = if (action == "open_url" && url.startsWith("http")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (action == "open_page") data["page"]?.takeIf { it.isNotBlank() }
                    ?.let { putExtra(com.evolutionofgames.zgamespsx.remote.WpConfig.EXTRA_PAGE, it) }
                if (action == "open_screen") data["screen"]?.takeIf { it.isNotBlank() }
                    ?.let { putExtra(com.evolutionofgames.zgamespsx.remote.WpConfig.EXTRA_SCREEN, it) }
            }
        }
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zgames)
            .setColor(0xFF3D7BFF.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        data["image"]?.takeIf { it.startsWith("http") }?.let { img ->
            fetchBitmap(img)?.let { bmp ->
                b.setLargeIcon(bmp)
                b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?))
            }
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (_: SecurityException) { }
    }

    /** Runs on a background thread, so a short blocking fetch is fine. */
    private fun fetchBitmap(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 7000
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) { null }
}
