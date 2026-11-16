package com.evolutionofgames.zgamespsx.push

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class InboxNotice(val title: String, val body: String, val time: Long, val action: String, val page: String, val screen: String, val url: String)
object NotificationInbox {
    private val _items = MutableStateFlow<List<InboxNotice>>(emptyList())
    val items = _items.asStateFlow()
    @Synchronized fun init(ctx: Context) {
        val raw = ctx.applicationContext.getSharedPreferences("notice_inbox", 0).getString("items", "[]")
        val a = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        _items.value = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> InboxNotice(o.optString("title"), o.optString("body"), o.optLong("time"), o.optString("action"), o.optString("page"), o.optString("screen"), o.optString("url")) } }
    }
    @Synchronized fun record(ctx: Context, data: Map<String, String>) {
        val n = InboxNotice(data["title"].orEmpty().ifBlank { "ZGames PSX" }, data["body"].orEmpty(), System.currentTimeMillis(), data["action"].orEmpty(), data["page"].orEmpty(), data["screen"].orEmpty(), data["url"].orEmpty())
        _items.value = (listOf(n) + _items.value).take(100)
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().put("title", it.title).put("body", it.body).put("time", it.time).put("action", it.action).put("page", it.page).put("screen", it.screen).put("url", it.url)) }
        ctx.applicationContext.getSharedPreferences("notice_inbox", 0).edit().putString("items", a.toString()).apply()
    }
    @Synchronized fun clear(ctx: Context) { _items.value = emptyList(); ctx.applicationContext.getSharedPreferences("notice_inbox", 0).edit().remove("items").apply() }
}
