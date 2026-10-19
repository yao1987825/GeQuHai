package com.mcode.gequhai.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 应用偏好持久化 (DataStore Preferences).
 *
 * 当前字段:
 *  - listened: List<SongMeta>     收听历史 (按时间倒序, 去重, 上限 200 首)
 *  - favorites: Set<String>       "❤️ 标记" 集 (每首一个 mp3Id), 与列表解耦
 *
 * 序列化用 Gson, 字符串存到 Preferences key.
 */
private val Context.dataStore by preferencesDataStore(name = "gequhai_prefs")
private val LISTENED_KEY = stringPreferencesKey("listened_songs")
private val FAVORITES_KEY = stringPreferencesKey("favorite_ids")

object Prefs {

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongMeta>>() {}.type
    private val stringSetType = object : TypeToken<Set<String>>() {}.type
    private const val MAX_LISTENED = 200

    // -------- listened (收听历史) --------

    fun listened(context: Context): Flow<List<SongMeta>> =
        context.dataStore.data.map { prefs -> decodeSongs(prefs) }

    suspend fun addListened(context: Context, song: SongMeta) {
        context.dataStore.edit { prefs ->
            val current = decodeSongs(prefs)
            val updated = (listOf(song) + current.filter { it.mp3Id != song.mp3Id })
                .take(MAX_LISTENED)
            prefs[LISTENED_KEY] = gson.toJson(updated)
        }
    }

    suspend fun removeListened(context: Context, mp3Id: String) {
        context.dataStore.edit { prefs ->
            val current = decodeSongs(prefs).filter { it.mp3Id != mp3Id }
            prefs[LISTENED_KEY] = gson.toJson(current)
        }
    }

    suspend fun clearListened(context: Context) {
        context.dataStore.edit { prefs -> prefs.remove(LISTENED_KEY) }
    }

    suspend fun listenedSnapshot(context: Context): List<SongMeta> =
        listened(context).first()

    // -------- favorites (❤️ 标记) --------

    fun favorites(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { prefs -> decodeFavorites(prefs) }

    /**
     * 切换一首歌的 ❤️ 标记. 若未标记则标记并返回 true; 若已标记则移除并返回 false.
     */
    suspend fun toggleFavorite(context: Context, mp3Id: String): Boolean {
        var nowFavorited = false
        context.dataStore.edit { prefs ->
            val current = decodeFavorites(prefs)
            val updated = if (mp3Id in current) current - mp3Id else {
                nowFavorited = true
                current + mp3Id
            }
            prefs[FAVORITES_KEY] = gson.toJson(updated)
        }
        return nowFavorited
    }

    suspend fun isFavorite(context: Context, mp3Id: String): Boolean =
        mp3Id in favorites(context).first()

    // -------- 内部 --------

    private fun decodeSongs(prefs: Preferences): List<SongMeta> {
        val raw = prefs[LISTENED_KEY] ?: return emptyList()
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching { gson.fromJson<List<SongMeta>>(raw, songListType) }
            .getOrDefault(emptyList())
            .orEmpty()
    }

    private fun decodeFavorites(prefs: Preferences): Set<String> {
        val raw = prefs[FAVORITES_KEY] ?: return emptySet()
        if (raw.isBlank() || raw == "[]") return emptySet()
        return runCatching { gson.fromJson<Set<String>>(raw, stringSetType) }
            .getOrDefault(emptySet())
            .orEmpty()
    }
}
