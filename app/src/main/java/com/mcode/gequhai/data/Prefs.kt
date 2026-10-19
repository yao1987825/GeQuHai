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
 *  - listened: List<SongMeta>   收听历史 (按时间倒序, 去重, 上限 200 首)
 *
 * 序列化用 Gson, 单字段 JSONArray 字符串存到 Preferences key.
 */
private val Context.dataStore by preferencesDataStore(name = "gequhai_prefs")
private val LISTENED_KEY = stringPreferencesKey("listened_songs")

object Prefs {

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongMeta>>() {}.type
    private const val MAX_LISTENED = 200

    /** 监听当前收听列表 (按最近在前顺序). */
    fun listened(context: Context): Flow<List<SongMeta>> =
        context.dataStore.data.map { prefs -> decode(prefs) }

    /** 在 storage 层强制添加一首到收听. 若已存在 (mp3Id 相同) 则移到最前. */
    suspend fun addListened(context: Context, song: SongMeta) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs)
            val updated = (listOf(song) + current.filter { it.mp3Id != song.mp3Id })
                .take(MAX_LISTENED)
            prefs[LISTENED_KEY] = gson.toJson(updated)
        }
    }

    /** 从收听列表中删除一首. */
    suspend fun removeListened(context: Context, mp3Id: String) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs).filter { it.mp3Id != mp3Id }
            prefs[LISTENED_KEY] = gson.toJson(current)
        }
    }

    /** 清空收听列表. */
    suspend fun clearListened(context: Context) {
        context.dataStore.edit { prefs -> prefs.remove(LISTENED_KEY) }
    }

    /** 当前一次性快照 (首屏 / 调试用). */
    suspend fun listenedSnapshot(context: Context): List<SongMeta> =
        listened(context).first()

    private fun decode(prefs: Preferences): List<SongMeta> {
        val raw = prefs[LISTENED_KEY] ?: return emptyList()
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching { gson.fromJson<List<SongMeta>>(raw, songListType) }
            .getOrDefault(emptyList())
            .orEmpty()
    }
}
