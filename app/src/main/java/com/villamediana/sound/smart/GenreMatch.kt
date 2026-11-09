package com.villamediana.sound.smart

/**
 * Which of the Home's genres ([com.villamediana.sound.discover.GenreCatalog] ids) a genre name
 * belongs to, however the catalog writes it: "Sertanejo Universitário" → sertanejo, "Hip-Hop/Rap"
 * → hiphop, "Pop Latino" → latin, "Música Cristã" → gospel. Null for the ones the Home has no shelf
 * for (country, folk…). Pure.
 */
object GenreMatch {
    /** Multi-word names first (longest wins), so "pop latino" is latin and "funk carioca" is funk. */
    private val phrases: List<Pair<String, String>> = listOf(
        "urbano latino" to "reggaeton",
        "latin urban" to "reggaeton",
        "trap latino" to "reggaeton",
        "latin trap" to "reggaeton",
        "pop latino" to "latin",
        "latin pop" to "latin",
        "regional mexicano" to "latin",
        "musica mexicana" to "latin",
        "salsa y tropical" to "salsa",
        "musica tropical" to "salsa",
        "funk carioca" to "funk",
        "funk brasileiro" to "funk",
        "brazilian funk" to "funk",
        "baile funk" to "funk",
        "funk ostentacao" to "funk",
        "hip hop" to "hiphop",
        "bossa nova" to "mpb",
        "musica popular brasileira" to "mpb",
        "k pop" to "kpop",
        "christian and gospel" to "gospel",
        "musica crista" to "gospel",
        "musica cristiana" to "gospel",
        "trilha sonora" to "classical",
        "banda sonora" to "classical",
        "drum and bass" to "electronic",
        "electronic dance music" to "electronic",
    ).sortedByDescending { it.first.length }

    /** Single words, checked in this order (pop last: it is usually a modifier, "pop rock"). */
    private val words: List<Pair<String, Set<String>>> = listOf(
        "reggaeton" to setOf("reggaeton", "regueton", "urbano", "dembow"),
        "salsa" to setOf("salsa", "tropical"),
        "sertanejo" to setOf("sertanejo", "sertaneja", "arrocha", "modao"),
        "pagode" to setOf("pagode", "samba"),
        "funk" to setOf("funk"),
        "mpb" to setOf("mpb", "bossa", "forro", "axe", "brasileira", "brazilian", "brasileiro", "piseiro", "pisadinha", "brega"),
        "kpop" to setOf("kpop"),
        "gospel" to setOf("gospel", "christian", "crista", "cristao", "cristiana", "cristiano", "worship", "louvor", "adoracao", "religious", "religiosa"),
        "hiphop" to setOf("rap", "trap", "hiphop", "drill"),
        "rnb" to setOf("rnb", "soul"),
        "electronic" to setOf("electronic", "electronica", "eletronica", "eletronico", "edm", "house", "techno", "trance", "dubstep", "dance", "electro"),
        "indie" to setOf("indie", "alternative", "alternativo", "alternativa"),
        "rock" to setOf("rock", "metal", "punk", "grunge"),
        "jazz" to setOf("jazz", "blues"),
        "classical" to setOf("classical", "classica", "classico", "clasica", "soundtrack", "instrumental", "opera", "orchestral"),
        "latin" to setOf("latin", "latino", "latina", "bachata", "merengue", "cumbia", "bolero", "mariachi", "ranchera", "corridos", "banda"),
        "pop" to setOf("pop"),
    )

    /** The Home genre id of [genre], or null. */
    fun idOf(genre: String?): String? {
        val key = normalizeText(genre)
        if (key.isEmpty()) return null
        val padded = " $key "
        phrases.firstOrNull { padded.contains(" ${it.first} ") }?.let { return it.second }
        val tokens = key.split(' ').toSet()
        return words.firstOrNull { (_, set) -> tokens.any { it in set } }?.first
    }
}
