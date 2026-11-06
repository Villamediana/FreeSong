package com.villamediana.sound.ui.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Pure list rules of the recent searches: newest first, no repeats (ignoring case), at most [MAX]. */
object RecentSearches {
    const val MAX = 12

    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    fun remove(list: List<String>, query: String): List<String> = list.filterNot { it.equals(query.trim(), ignoreCase = true) }

    private val serializer = ListSerializer(String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(list: List<String>): String = json.encodeToString(serializer, list)

    fun decode(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

private val Context.searchRecentsStore: DataStore<Preferences> by preferencesDataStore(name = "search_recents")

/** Recent searches of the Search screen, kept in their own small DataStore. */
class RecentSearchStore(context: Context) {
    private val store = context.applicationContext.searchRecentsStore
    private val key = stringPreferencesKey("queries")

    val recents: Flow<List<String>> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { RecentSearches.decode(it[key]) }

    suspend fun add(query: String) {
        store.edit { it[key] = RecentSearches.encode(RecentSearches.add(RecentSearches.decode(it[key]), query)) }
    }

    suspend fun remove(query: String) {
        store.edit { it[key] = RecentSearches.encode(RecentSearches.remove(RecentSearches.decode(it[key]), query)) }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
