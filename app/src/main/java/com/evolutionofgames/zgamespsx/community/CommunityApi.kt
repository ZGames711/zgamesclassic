package com.evolutionofgames.zgamespsx.community

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.evolutionofgames.zgamespsx.push.PushManager
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object CommunityApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor { c -> c.proceed(c.request().newBuilder().removeHeader("Accept-Encoding").build()) }.build()
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("community_private", Context.MODE_PRIVATE)
    @Synchronized private fun token(ctx: Context): String {
        val prefs = prefs(ctx)
        prefs.getString("token", null)?.let { return it }
        val raw = ByteArray(32); SecureRandom().nextBytes(raw)
        val value = raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(prefs.edit().putString("token", value).commit())
        return value
    }
    @Synchronized fun recoveryBackup(ctx: Context): String = JSONObject()
        .put("version", 1).put("site", WpConfig.SITE).put("token", token(ctx)).toString()
    @Synchronized fun restoreRecovery(ctx: Context, text: String) {
        require(text.length <= 4096) { "Invalid recovery file." }
        val data = JSONObject(text)
        require(data.optInt("version") == 1 && data.optString("site") == WpConfig.SITE) { "This backup is for a different app or site." }
        val value = data.optString("token")
        require(Regex("^[0-9a-f]{64}$").matches(value)) { "Invalid recovery file." }
        check(prefs(ctx).edit().clear().putString("token", value).commit()) { "Could not restore inbox." }
    }
    private fun execute(ctx: Context, path: String, data: JSONObject? = null): JSONObject {
        val tokenValue = token(ctx)
        val endpoint = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val plain = WpConfig.SITE + "/?rest_route=/zgpsx/v1/" + endpoint + if (query.isNotEmpty()) "&$query" else ""
        val urls = listOf(WpConfig.SITE + "/wp-json/zgpsx/v1/" + path, plain)
        for ((index, url) in urls.withIndex()) {
            val builder = Request.Builder().url(url).header("X-Zgames-Token", tokenValue).header("Cache-Control", "no-store")
            if (data != null) builder.post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            client.newCall(builder.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 404 && index == 0) return@use
                if (!r.isSuccessful) throw IllegalStateException(when (r.code) {
                    403 -> "This feature is currently disabled."
                    429 -> "Please wait a moment before sending again."
                    404 -> "This request is no longer available."
                    else -> "Couldn't complete the request. Please try again."
                })
                return JSONObject(body)
            }
        }
        throw IllegalStateException("This request is no longer available.")
    }
    suspend fun publicPost(ctx: Context, game: String): JSONObject = withContext(Dispatchers.IO) {
        require(Regex("^(zgames|evo|app):[0-9]+$").matches(game))
        execute(ctx, "community?game=$game")
    }
    suspend fun publicRating(ctx: Context, game: String): JSONObject = withContext(Dispatchers.IO) {
        require(Regex("^(zgames|evo|app):[0-9]+$").matches(game))
        execute(ctx, "community?game=$game&ratings_only=1")
    }
    suspend fun inbox(ctx: Context): JSONObject = withContext(Dispatchers.IO) { execute(ctx, "community/inbox") }
    suspend fun send(ctx: Context, data: JSONObject): JSONObject = withContext(Dispatchers.IO) { execute(ctx, "community", data) }

    /** Called by the existing network-constrained WorkManager poll, not a real-time push service. */
    fun pollReplies(ctx: Context) {
        if (!PushManager.isEnabled(ctx) || !WpConfig.flag("reply_notifications", false)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val records = execute(ctx, "community/inbox").optJSONArray("items") ?: return
        val p = prefs(ctx)
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            val reply = item.optJSONObject("reply") ?: continue
            val rev = reply.optLong("revision", 0L)
            val id = item.optLong("id", 0L)
            if (id == 0L || rev == 0L || reply.optString("text").isBlank() || rev <= p.getLong("reply_$id", 0L)) continue
            // Do not put private message contents on the lock screen.
            PushManager.show(ctx, mapOf("title" to "New reply", "body" to "The ZGames team replied. Open the app to read it.", "action" to "open_screen", "screen" to "settings"))
            p.edit().putLong("reply_$id", rev).apply()
        }
    }
}
