package com.villamediana.sound.discover

import androidx.annotation.StringRes
import com.villamediana.sound.R

/** A genre offered on the first Home, with the YouTube Music search that represents it. */
data class GenreDef(val id: String, @StringRes val label: Int, val query: String, val tint: Long, val term: String = query)

object GenreCatalog {
    val all: List<GenreDef> = listOf(
        GenreDef("pop", R.string.genre_pop, "pop hits", 0xFF4C6FFF, "pop"),
        GenreDef("reggaeton", R.string.genre_reggaeton, "reggaeton hits", 0xFFE0567A, "reggaeton"),
        GenreDef("rock", R.string.genre_rock, "rock classics", 0xFF3D3F4A, "rock"),
        GenreDef("sertanejo", R.string.genre_sertanejo, "sertanejo sucessos", 0xFFC98A2B, "sertanejo"),
        GenreDef("funk", R.string.genre_funk, "funk brasileiro", 0xFF8E44D9, "funk brasileiro"),
        GenreDef("mpb", R.string.genre_mpb, "mpb clássicos", 0xFF1E9E6E, "mpb"),
        GenreDef("hiphop", R.string.genre_hiphop, "hip hop hits", 0xFF2B2D36, "hip hop"),
        GenreDef("electronic", R.string.genre_electronic, "electronic dance hits", 0xFF14A3C7, "electronic"),
        GenreDef("latin", R.string.genre_latin, "latin pop hits", 0xFFE36A3A, "latin pop"),
        GenreDef("salsa", R.string.genre_salsa, "salsa clásica", 0xFFD9453B, "salsa"),
        GenreDef("pagode", R.string.genre_pagode, "pagode", 0xFFB5651D, "pagode"),
        GenreDef("indie", R.string.genre_indie, "indie hits", 0xFF5E8C61, "indie"),
        GenreDef("rnb", R.string.genre_rnb, "r&b hits", 0xFF7A4BC2, "r&b"),
        GenreDef("kpop", R.string.genre_kpop, "k-pop hits", 0xFFE85FB0, "k-pop"),
        GenreDef("gospel", R.string.genre_gospel, "louvor gospel", 0xFF3A7BD5, "gospel"),
        GenreDef("jazz", R.string.genre_jazz, "jazz classics", 0xFF2F6F8F, "jazz"),
        GenreDef("classical", R.string.genre_classical, "classical music", 0xFF8A7556, "classical"),
        GenreDef("eighties", R.string.genre_eighties, "80s hits", 0xFFD94E8F, "80s"),
    )

    fun byId(id: String): GenreDef? = all.firstOrNull { it.id == id }

    /**
     * The search behind a genre's suggestions right now. The wording rotates with the day and the
     * time of day (4 slots), so the shelves change while staying in the genre.
     */
    fun liveQuery(genre: GenreDef, slot: Long): String {
        val flavours = listOf("", "new", "top", "best", "2026")
        val i = ((slot + all.indexOf(genre)) % flavours.size).toInt()
        return listOf(genre.query, flavours[i]).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 6-hour slot of [nowMs]: changes four times a day. */
    fun slotOf(nowMs: Long): Long = nowMs / (6L * 3_600_000L)
}
