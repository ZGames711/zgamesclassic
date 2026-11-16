package com.evolutionofgames.zgamespsx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolutionofgames.zgamespsx.remote.WpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val counts = ConcurrentHashMap<String, Pair<Long, Int>>()
private val slots = Semaphore(4)
private val countHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).addNetworkInterceptor { it.proceed(it.request().newBuilder().removeHeader("Accept-Encoding").build()) }.build()
@Composable
fun DownloadCountBadge(game: String, modifier: Modifier = Modifier) {
    val config by WpConfig.content.collectAsState()
    if (!config.flag("tile_download_counts", false) || game.startsWith("app:")) return
    var count by remember(game) { mutableStateOf(counts[game]?.second) }
    LaunchedEffect(game) {
        val cached = counts[game]
        if (cached != null && System.currentTimeMillis() - cached.first < 300000) { count = cached.second; return@LaunchedEffect }
        try {
            slots.acquire()
            try {
                val n = withContext(Dispatchers.IO) {
                    countHttp.newCall(Request.Builder().url("${WpConfig.SITE}/?rest_route=/zgpsx/v1/game-download&game=$game").build()).execute().use { r ->
                        if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optInt("count", -1).takeIf { it >= 0 }
                    }
                }
                if (n != null) { counts[game] = System.currentTimeMillis() to n; count = n }
            } finally { slots.release() }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }
    count?.takeIf { it > 0 }?.let { Text("↓ $it", modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color.White, fontSize = 9.sp, maxLines = 1) }
}
