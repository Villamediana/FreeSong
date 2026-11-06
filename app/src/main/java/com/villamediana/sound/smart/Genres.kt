package com.villamediana.sound.smart

import java.text.Normalizer
import java.util.Locale

private val MARKS = Regex("\\p{Mn}+")
private val RNB = Regex("\\br\\s*(?:&|'n'|n|and)\\s*b\\b")
private val NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * Lowercase, accents stripped, punctuation collapsed to single spaces:
 * "Hip-Hop/Rap" → "hip hop rap", "R&B/Soul" → "rnb soul", "Reggaetón" → "reggaeton".
 */
internal fun normalizeText(s: String?): String {
    if (s.isNullOrBlank()) return ""
    var t = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
    t = RNB.replace(t, "rnb").replace("&", " and ")
    return NON_ALNUM.replace(t, " ").trim()
}

/** Broad families that relate genre names across spellings and languages (en / pt / es). */
enum class GenreFamily { LATIN, BRAZILIAN, GOSPEL, KIDS, HIPHOP, RNB, REGGAE, ELECTRONIC, ROCK, JAZZ, CLASSICAL, FOLK, WORLD, POP }

/**
 * Genre knowledge used by the smart features: normalization ("Pop Latino" == "pop latino"),
 * families (reggaeton and urbano latino are both [GenreFamily.LATIN]) and which families sound
 * close to each other (latin ↔ pop ↔ electronic…). Pure and deterministic.
 */
object Genres {
    /** Same genre after normalization. */
    const val SAME = 1.0
    /** Different genre, same family (rock ↔ indie). */
    const val FAMILY = 0.6
    /** Neighbouring families (pop ↔ dance-electronic). */
    const val NEIGHBOUR = 0.25

    /** Multi-word names checked first (longest wins), so "banda sonora" is not Mexican banda. */
    private val phrases: List<Pair<String, GenreFamily>> = listOf(
        "funk carioca" to GenreFamily.HIPHOP,
        "funk brasileiro" to GenreFamily.HIPHOP,
        "funk ostentacao" to GenreFamily.HIPHOP,
        "brazilian funk" to GenreFamily.HIPHOP,
        "baile funk" to GenreFamily.HIPHOP,
        "hip hop" to GenreFamily.HIPHOP,
        "drum and bass" to GenreFamily.ELECTRONIC,
        "drum n bass" to GenreFamily.ELECTRONIC,
        "lo fi" to GenreFamily.ELECTRONIC,
        "electronic dance music" to GenreFamily.ELECTRONIC,
        "bossa nova" to GenreFamily.BRAZILIAN,
        "musica popular brasileira" to GenreFamily.BRAZILIAN,
        "musica brasileira" to GenreFamily.BRAZILIAN,
        "trilha sonora" to GenreFamily.CLASSICAL,
        "banda sonora" to GenreFamily.CLASSICAL,
        "new age" to GenreFamily.CLASSICAL,
        "regional mexicano" to GenreFamily.LATIN,
        "musica mexicana" to GenreFamily.LATIN,
        "musica tropical" to GenreFamily.LATIN,
        "pop latino" to GenreFamily.LATIN,
        "latin pop" to GenreFamily.LATIN,
        "trap latino" to GenreFamily.LATIN,
        "latin trap" to GenreFamily.LATIN,
        "latin urban" to GenreFamily.LATIN,
        "urbano latino" to GenreFamily.LATIN,
        "rock latino" to GenreFamily.ROCK,
        "latin rock" to GenreFamily.ROCK,
        "rock en espanol" to GenreFamily.ROCK,
        "rock nacional" to GenreFamily.ROCK,
        "pop rock" to GenreFamily.ROCK,
        "singer songwriter" to GenreFamily.FOLK,
        "musica infantil" to GenreFamily.KIDS,
        "children s music" to GenreFamily.KIDS,
        "christian and gospel" to GenreFamily.GOSPEL,
        "musica cristiana" to GenreFamily.GOSPEL,
        "musica crista" to GenreFamily.GOSPEL,
        "adult contemporary" to GenreFamily.POP,
        "world music" to GenreFamily.WORLD,
        "k pop" to GenreFamily.POP,
        "j pop" to GenreFamily.POP,
        "c pop" to GenreFamily.POP,
        "j rock" to GenreFamily.ROCK,
    ).sortedByDescending { it.first.length }

    /** Single words, checked family by family in this order (pop last: it is usually a modifier). */
    private val words: List<Pair<GenreFamily, Set<String>>> = listOf(
        GenreFamily.LATIN to setOf(
            "latin", "latino", "latina", "latinoamericano", "latinoamericana", "reggaeton", "regueton", "urbano",
            "salsa", "bachata", "merengue", "cumbia", "dembow", "vallenato", "tango", "bolero", "mariachi",
            "ranchera", "ranchero", "corrido", "corridos", "banda", "norteno", "tejano", "tropical", "mexicana",
            "mexicano", "mexican", "flamenco", "rumba",
        ),
        GenreFamily.BRAZILIAN to setOf(
            "mpb", "samba", "bossa", "pagode", "sertanejo", "forro", "axe", "brazilian", "brasileira", "brasileiro",
            "piseiro", "pisadinha", "arrocha", "brega", "tecnobrega", "frevo", "choro", "baiao", "xote", "seresta",
        ),
        GenreFamily.GOSPEL to setOf(
            "gospel", "christian", "cristiana", "cristiano", "crista", "cristao", "worship", "louvor", "adoracao",
            "religious", "religiosa", "religioso",
        ),
        GenreFamily.KIDS to setOf("infantil", "kids", "children", "ninos", "criancas"),
        GenreFamily.HIPHOP to setOf("rap", "trap", "hiphop", "drill", "grime"),
        GenreFamily.RNB to setOf("rnb", "soul", "funk", "motown"),
        GenreFamily.REGGAE to setOf("reggae", "ska", "dub", "dancehall", "rocksteady"),
        GenreFamily.ELECTRONIC to setOf(
            "electronic", "electronica", "electronico", "eletronica", "eletronico", "edm", "house", "techno", "tecno",
            "trance", "dubstep", "electro", "ambient", "idm", "breakbeat", "dnb", "synthwave", "chillout", "chill",
            "chillhop", "lofi", "downtempo", "hardstyle",
        ),
        GenreFamily.ROCK to setOf(
            "rock", "alternative", "alternativo", "alternativa", "indie", "metal", "punk", "grunge", "emo", "hardcore",
            "prog", "progressive", "shoegaze", "psychedelic", "psicodelico", "rockabilly",
        ),
        GenreFamily.JAZZ to setOf("jazz", "blues", "swing", "bebop"),
        GenreFamily.CLASSICAL to setOf(
            "classical", "classica", "classico", "clasica", "clasico", "soundtrack", "soundtracks", "score", "opera",
            "orchestral", "orquestral", "instrumental", "piano", "baroque", "barroco",
        ),
        GenreFamily.FOLK to setOf("folk", "country", "americana", "bluegrass", "acoustic", "acustico", "cantautor", "songwriter"),
        GenreFamily.WORLD to setOf("world", "afrobeat", "afrobeats", "amapiano", "african", "afro", "kizomba", "kuduro", "fado", "celtic"),
        GenreFamily.POP to setOf("pop", "dance", "disco", "electropop", "synthpop", "kpop", "jpop", "teen"),
    )

    private val neighbours: Map<GenreFamily, Set<GenreFamily>> = run {
        val pairs = listOf(
            GenreFamily.LATIN to GenreFamily.POP,
            GenreFamily.LATIN to GenreFamily.HIPHOP,
            GenreFamily.LATIN to GenreFamily.REGGAE,
            GenreFamily.LATIN to GenreFamily.BRAZILIAN,
            GenreFamily.BRAZILIAN to GenreFamily.POP,
            GenreFamily.BRAZILIAN to GenreFamily.FOLK,
            GenreFamily.POP to GenreFamily.ELECTRONIC,
            GenreFamily.POP to GenreFamily.RNB,
            GenreFamily.POP to GenreFamily.ROCK,
            GenreFamily.ROCK to GenreFamily.FOLK,
            GenreFamily.ELECTRONIC to GenreFamily.HIPHOP,
            GenreFamily.HIPHOP to GenreFamily.RNB,
            GenreFamily.HIPHOP to GenreFamily.REGGAE,
            GenreFamily.RNB to GenreFamily.JAZZ,
            GenreFamily.RNB to GenreFamily.GOSPEL,
            GenreFamily.JAZZ to GenreFamily.CLASSICAL,
            GenreFamily.REGGAE to GenreFamily.WORLD,
            GenreFamily.WORLD to GenreFamily.HIPHOP,
        )
        val map = HashMap<GenreFamily, MutableSet<GenreFamily>>()
        pairs.forEach { (a, b) ->
            map.getOrPut(a) { mutableSetOf() } += b
            map.getOrPut(b) { mutableSetOf() } += a
        }
        map
    }

    /** Normalized key used to group and compare genres ("" when blank). */
    fun key(genre: String?): String = normalizeText(genre)

    /** URL/id-safe slug of a genre: "Hip-Hop/Rap" → "hip-hop-rap". */
    fun slug(genre: String?): String = key(genre).replace(' ', '-')

    fun family(genre: String?): GenreFamily? = familyOfKey(key(genre))

    fun familyOfKey(key: String): GenreFamily? {
        if (key.isEmpty()) return null
        val padded = " $key "
        phrases.firstOrNull { padded.contains(" ${it.first} ") }?.let { return it.second }
        val tokens = key.split(' ').toSet()
        return words.firstOrNull { (_, set) -> tokens.any { it in set } }?.first
    }

    fun neighboursOf(family: GenreFamily): Set<GenreFamily> = neighbours[family].orEmpty()

    /** 1.0 same genre, 0.6 same family, 0.25 neighbouring family, 0 otherwise (or unknown). */
    fun relation(a: String?, b: String?): Double {
        val ka = key(a)
        val kb = key(b)
        return relationOfKeys(ka, familyOfKey(ka), kb, familyOfKey(kb))
    }

    internal fun relationOfKeys(ka: String, fa: GenreFamily?, kb: String, fb: GenreFamily?): Double = when {
        ka.isEmpty() || kb.isEmpty() -> 0.0
        ka == kb -> SAME
        fa == null || fb == null -> 0.0
        fa == fb -> FAMILY
        fb in neighboursOf(fa) -> NEIGHBOUR
        else -> 0.0
    }
}
