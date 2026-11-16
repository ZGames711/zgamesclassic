package com.evolutionofgames.zgamespsx.prefs

import android.content.Context
import com.evolutionofgames.zgamespsx.model.Game
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saved (favourite) games. A full snapshot of each game is stored, so the
 * Favorites tab works offline and Detail can open after the app restarts.
 */
class FavoritesStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("zg_favorites", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<Game>>() {}.type

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Game>> = _items.asStateFlow()

    private fun load(): List<Game> = try {
        val raw = sp.getString(KEY, null)
        if (raw.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Game>>(raw, type).orEmpty().filterNotNull()
    } catch (_: Exception) {
        emptyList()
    }

    fun isFavorite(id: String): Boolean = _items.value.any { it.id == id }

    fun get(id: String): Game? = _items.value.firstOrNull { it.id == id }

    /** Adds or removes the game. Returns true when it is now a favourite. */
    @Synchronized
    fun toggle(game: Game): Boolean {
        val cur = _items.value
        val now = if (cur.any { it.id == game.id }) {
            cur.filterNot { it.id == game.id }
        } else {
            listOf(game) + cur
        }
        _items.value = now
        try { sp.edit().putString(KEY, gson.toJson(now)).apply() } catch (_: Exception) { }
        return now.any { it.id == game.id }
    }

    private companion object {
        const val KEY = "items"
    }
}
