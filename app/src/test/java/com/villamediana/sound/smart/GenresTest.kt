package com.villamediana.sound.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenresTest {
    @Test
    fun `normalizes accents, case and punctuation`() {
        assertEquals("hip hop rap", Genres.key("Hip-Hop/Rap"))
        assertEquals("rnb soul", Genres.key("R&B/Soul"))
        assertEquals("rnb", Genres.key("R & B"))
        assertEquals("reggaeton", Genres.key("  Reggaetón "))
        assertEquals("musica popular brasileira", Genres.key("Música Popular Brasileira"))
        assertEquals("drum and bass", Genres.key("Drum & Bass"))
        assertEquals("", Genres.key(null))
        assertEquals("hip-hop-rap", Genres.slug("Hip-Hop/Rap"))
    }

    @Test
    fun `families understand english, portuguese and spanish names`() {
        val expected = mapOf(
            "Reggaeton" to GenreFamily.LATIN,
            "Reggaetón" to GenreFamily.LATIN,
            "Urbano latino" to GenreFamily.LATIN,
            "Pop Latino" to GenreFamily.LATIN,
            "Latin Pop" to GenreFamily.LATIN,
            "Música Mexicana" to GenreFamily.LATIN,
            "Bachata" to GenreFamily.LATIN,
            "Rock" to GenreFamily.ROCK,
            "Alternativo" to GenreFamily.ROCK,
            "Indie Rock" to GenreFamily.ROCK,
            "Heavy Metal" to GenreFamily.ROCK,
            "Rock en Español" to GenreFamily.ROCK,
            "Pop Rock" to GenreFamily.ROCK,
            "Pop" to GenreFamily.POP,
            "Dance" to GenreFamily.POP,
            "K-Pop" to GenreFamily.POP,
            "Electrónica" to GenreFamily.ELECTRONIC,
            "Eletrônica" to GenreFamily.ELECTRONIC,
            "Dance/Electronic" to GenreFamily.ELECTRONIC,
            "House" to GenreFamily.ELECTRONIC,
            "Techno" to GenreFamily.ELECTRONIC,
            "Hip-Hop/Rap" to GenreFamily.HIPHOP,
            "Trap" to GenreFamily.HIPHOP,
            "Funk Carioca" to GenreFamily.HIPHOP,
            "R&B/Soul" to GenreFamily.RNB,
            "Funk" to GenreFamily.RNB,
            "MPB" to GenreFamily.BRAZILIAN,
            "Samba" to GenreFamily.BRAZILIAN,
            "Bossa Nova" to GenreFamily.BRAZILIAN,
            "Pagode" to GenreFamily.BRAZILIAN,
            "Sertanejo Universitário" to GenreFamily.BRAZILIAN,
            "Forró" to GenreFamily.BRAZILIAN,
            "Jazz" to GenreFamily.JAZZ,
            "Blues" to GenreFamily.JAZZ,
            "Clássica" to GenreFamily.CLASSICAL,
            "Música Clásica" to GenreFamily.CLASSICAL,
            "Soundtrack" to GenreFamily.CLASSICAL,
            "Trilha Sonora" to GenreFamily.CLASSICAL,
            "Banda Sonora" to GenreFamily.CLASSICAL,
            "Reggae" to GenreFamily.REGGAE,
            "Gospel" to GenreFamily.GOSPEL,
            "Christian & Gospel" to GenreFamily.GOSPEL,
            "Singer/Songwriter" to GenreFamily.FOLK,
            "Country" to GenreFamily.FOLK,
            "Música Infantil" to GenreFamily.KIDS,
            "Afrobeats" to GenreFamily.WORLD,
        )
        expected.forEach { (genre, family) -> assertEquals(genre, family, Genres.family(genre)) }
        assertNull(Genres.family("Spoken Word"))
        assertNull(Genres.family(""))
    }

    @Test
    fun `relation ranks same genre over family over neighbours`() {
        assertEquals(Genres.SAME, Genres.relation("Pop", "pop"), 0.0)
        assertEquals(Genres.FAMILY, Genres.relation("Rock", "Indie"), 0.0)
        assertEquals(Genres.FAMILY, Genres.relation("Reggaeton", "Urbano latino"), 0.0)
        assertEquals(Genres.NEIGHBOUR, Genres.relation("Pop", "House"), 0.0)
        assertEquals(Genres.NEIGHBOUR, Genres.relation("Reggaeton", "Pop"), 0.0)
        assertEquals(0.0, Genres.relation("Classical", "Reggaeton"), 0.0)
        assertEquals(0.0, Genres.relation(null, "Pop"), 0.0)
        assertEquals(0.0, Genres.relation("Spoken Word", "Audiobook"), 0.0)
    }

    @Test
    fun `neighbour map is symmetric`() {
        GenreFamily.entries.forEach { a ->
            Genres.neighboursOf(a).forEach { b -> assertTrue("$a ↔ $b", a in Genres.neighboursOf(b)) }
        }
    }

    @Test
    fun `featured artists are cut from the artist key`() {
        assertEquals("anitta", artistKey("Anitta feat. Maluma"))
        assertEquals("bad bunny", artistKey("Bad Bunny ft. Drake"))
        assertEquals("jorge and mateus", artistKey("Jorge & Mateus"))
        assertEquals("ivete sangalo", artistKey("Ivete Sangalo part. Ludmilla"))
    }
}
