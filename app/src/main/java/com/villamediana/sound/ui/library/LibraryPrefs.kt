package com.villamediana.sound.ui.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryUiStore: DataStore<Preferences> by preferencesDataStore(name = "sound_library_ui")

/** How the library is shown (list or grid, sort) — remembered between sessions. */
class LibraryPrefs(context: Context) {
    private val store = context.applicationContext.libraryUiStore

    private object K {
        val mode = stringPreferencesKey("view_mode")
        val sort = stringPreferencesKey("sort")
    }

    val viewMode: Flow<LibViewMode> = store.data.map { p ->
        p[K.mode]?.let { runCatching { LibViewMode.valueOf(it) }.getOrNull() } ?: LibViewMode.LIST
    }

    val sort: Flow<LibSort> = store.data.map { p ->
        p[K.sort]?.let { runCatching { LibSort.valueOf(it) }.getOrNull() } ?: LibSort.RECENT
    }

    suspend fun setViewMode(mode: LibViewMode) {
        store.edit { it[K.mode] = mode.name }
    }

    suspend fun setSort(sort: LibSort) {
        store.edit { it[K.sort] = sort.name }
    }
}
