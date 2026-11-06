package com.villamediana.sound.online

/**
 * Turns YouTube video titles into song titles: removes the video noise ("(Official Video)",
 * "[Áudio Oficial]", "| Video Oficial", "HD", emoji…), keeps what tells versions apart (live,
 * acoustic, remix, remaster, feat.), and — for uploads that are not from the YouTube Music
 * catalog — splits "Artist - Title". Pure and deterministic.
 */
object TitleCleaner {

    data class Cleaned(
        val title: String,
        val artist: String,
        /** True when the artist came from an "L - R" split with no evidence of which side is the artist. */
        val ambiguousSplit: Boolean = false,
    )

    enum class Version { LIVE, REMIX, ACOUSTIC, INSTRUMENTAL, KARAOKE, COVER, SPED_UP }

    // ---- vocabulary (compared on TextMatch.key, so accents/case don't matter) ----

    /** Words that only describe the video upload. A bracket made only of these (+ connectors) is noise. */
    private val noiseWords = setOf(
        "official", "oficial", "officiel", "offizielles", "ufficiale",
        "video", "videos", "videoclip", "videoclipe", "clip", "clipe", "musical", "music",
        "audio", "lyric", "lyrics", "letra", "letras", "videoletra", "videolyric", "visualizer",
        "visualiser", "visualizador", "hd", "hq", "4k", "8k", "uhd", "fullhd", "full", "1080p", "720p",
        "1080", "720", "mv", "explicit", "performance", "legendado", "legendada", "traducao",
        "traduccion", "subtitulado", "subtitulada", "sub", "subs", "vertical", "teaser", "premiere",
        "estreno", "estreia",
    )

    /** Words that mark a translated/subtitled upload; with them, language names become noise too. */
    private val translationWords = setOf(
        "legendado", "legendada", "traducao", "traduccion", "subtitulado", "subtitulada", "sub", "subs", "letra", "lyrics",
    )
    private val languageWords = setOf("espanol", "castellano", "portugues", "ingles", "english", "spanish", "portuguese")

    /** Glue words: neutral inside a noise bracket ("Video Oficial con Letra"). */
    private val connectorWords = setOf(
        "and", "y", "e", "with", "con", "com", "en", "in", "em", "the", "a", "de", "do", "da", "del",
        "el", "la", "o", "new", "nuevo", "novo",
    )

    /** Single trailing words that may be stripped even without brackets ("Song HD"). */
    private val trailingStrongWords = setOf("hd", "hq", "4k", "8k", "uhd", "1080p", "720p", "mv")

    private val versionPatterns: List<Pair<Version, Regex>> = listOf(
        Version.LIVE to Regex("\\b(live|en vivo|ao vivo|en directo|al vivo|in concert|unplugged)\\b"),
        Version.REMIX to Regex("\\b(remix|rmx|remixed|bootleg|vip mix|club mix)\\b"),
        Version.ACOUSTIC to Regex("\\b(acoustic|acustico|acustica|unplugged|stripped)\\b"),
        Version.INSTRUMENTAL to Regex("\\b(instrumental|backing track)\\b"),
        Version.KARAOKE to Regex("\\b(karaoke|originally performed by|in the style of)\\b"),
        Version.COVER to Regex("\\b(cover|tribute|tributo)\\b"),
        Version.SPED_UP to Regex("\\b(sped up|speed up|slowed|nightcore|reverb|8d audio)\\b"),
    )

    /** Anything that marks a variant worth keeping in the title (checked on the key). */
    private val keepPattern = Regex(
        "\\b(live|vivo|directo|concert|session|sessions|acoustic|acustico|acustica|unplugged|remix|rmx|mix|edit|" +
            "remaster|remastered|remasterizado|remasterizada|remasterisee|version|versao|versiones|demo|mono|stereo|" +
            "instrumental|karaoke|cover|sped|slowed|reverb|extended|radio|deluxe|reprise|interlude|intro|outro|" +
            "bonus|feat|ft|featuring|part|pt|vol|parte|en espanol|spanglish|english|spanish|portuguese|original)\\b",
    )

    private val ws = Regex("\\s+")
    private val featToken = Regex("(?i)(?<=[\\s(\\[])(featuring|feat|ft)(?![\\p{L}\\p{N}])\\.?\\s*")
    private val partFeat = Regex("(?i)\\((part\\.|participa[cç][aã]o( especial)?( de)?)\\s+(?=\\p{L})")
    private val hashtags = Regex("(^|\\s)#[\\p{L}\\p{N}_]+")
    private val prodGroup = Regex("(?i)^(prod(\\.|uced)?|produced)(\\s+by)?\\s+.+$")
    private val bracketGroup = Regex("\\(([^()]*)\\)|\\[([^\\[\\]]*)]|\\{([^{}]*)}")
    private val pipeSplit = Regex("\\s*[|｜¦]\\s*")
    private val dashSep = Regex("\\s+[-–—]\\s+|\\s+[–—]|[–—]\\s+")
    private val topicSuffix = Regex("(?i)\\s*-\\s*(topic|t[oó]pico|tema|thema|sujet|argomento)$")
    private val vevoSuffix = Regex("(?i)\\s*vevo$")
    private val officialSuffix = Regex(
        "(?i)[\\s\\-|]+(\\(?(official|oficial|officiel)\\)?|official (youtube )?channel|canal oficial|oficial channel|official music)$",
    )
    private val camelBoundary = Regex("(?<=\\p{Ll})(?=\\p{Lu})")
    private val leadingQuoted = Regex("^[\"“”«„'‘’](.+?)[\"“”»'’‘](.*)$")
    private val kpopQuoted = Regex("^(.{1,60}?)\\s+['‘’\"“](.+?)['’‘\"”](.*)$")
    /** "Live at Wembley", "Remastered 2011", "2011 Remaster", "Radio Edit"… (on the key). */
    private val versionSuffixStart = Regex(
        "^((19|20)\\d{2}\\s+)?(live|en vivo|ao vivo|en directo|directo|acoustic|acustico|acustica|unplugged|" +
            "remix|rmx|remixed|demo|mono|stereo|instrumental|karaoke|radio (edit|version|mix)|edit|extended|" +
            "sped up|slowed|reprise|interlude|bonus track|single (version|edit)|album version|" +
            "original (mix|version)|remaster|remastered|remasterizado|remasterizada|remasterise|digital remaster|" +
            "versao|version|club mix|dub mix|vip mix)\\b.*",
    )

    // ---------------------------------------------------------------------------------------

    /**
     * Full cleanup. Catalog songs (YouTube Music) already have proper titles: they only lose
     * noise; everything else may also be split into artist and title.
     */
    fun clean(title: String, artist: String, fromMusicCatalog: Boolean): Cleaned {
        val cleanArtist = cleanArtist(artist).ifBlank { artist.trim() }
        if (fromMusicCatalog) return Cleaned(cleanTitle(title).ifBlank { title.trim() }, cleanArtist)

        var t = preClean(title)

        // "Song | Artist", "Artist - Song | Label", "Song | Official Video", "QUEVEDO || BZRP Music Sessions #52"
        val segments = t.split(pipeSplit).map { it.trim() }.filter { it.isNotEmpty() && !isNoise(it) }
        t = when {
            segments.isEmpty() -> t
            segments.size == 1 -> segments[0]
            else -> {
                val notArtist = segments.filterNot { sameArtist(it, cleanArtist, strict = true) }
                when {
                    notArtist.size < segments.size && notArtist.isNotEmpty() -> notArtist.first()
                    splitDash(segments[0]) != null -> segments[0]
                    segments.size == 2 -> segments.joinToString(" - ")
                    else -> segments.first()
                }
            }
        }
        // "Song - Video Oficial" → "Song" before deciding who is the artist.
        splitAllDashes(t).let { parts ->
            if (parts.size > 1) t = parts.filterNot { isNoise(it) }.ifEmpty { parts.take(1) }.joinToString(" - ")
        }

        var outArtist = cleanArtist
        var outTitle = t
        var ambiguous = false
        val split = splitDash(t)
        if (split != null) {
            val (l, r) = split
            when {
                sameArtist(l, cleanArtist) -> { outArtist = l; outTitle = r }
                isVersionSuffix(r) -> Unit
                sameArtist(r, cleanArtist) && !isVersionSuffix(l) -> { outArtist = r; outTitle = l }
                l.length <= 80 -> { outArtist = l; outTitle = r; ambiguous = true }
            }
        } else {
            kpopQuoted.matchEntire(t)?.let { m ->
                val rest = m.groupValues[3].trim()
                if (rest.isEmpty() || isNoise(rest) || rest.startsWith("(") || rest.startsWith("[")) {
                    val l = m.groupValues[1].trim()
                    outArtist = l
                    outTitle = (m.groupValues[2] + " " + rest).trim()
                    ambiguous = !sameArtist(l, cleanArtist)
                }
            }
        }

        // "Artist feat. Other - Song" → artist "Artist", title "Song (feat. Other)"
        val featInArtist = featToken.find(outArtist)
        if (featInArtist != null && featInArtist.range.first > 0) {
            val guests = outArtist.substring(featInArtist.range.last + 1).trim().trim('(', ')', '[', ']').trim()
            outArtist = outArtist.substring(0, featInArtist.range.first).trim().trimEnd('(', '[', ',', '&').trim()
            if (guests.isNotEmpty() && featToken.find(outTitle) == null) outTitle = "$outTitle (feat. $guests)"
        }

        val finalTitle = cleanTitle(outTitle).ifBlank { cleanTitle(title).ifBlank { title.trim() } }
        val finalArtist = cleanArtist(outArtist).ifBlank { cleanArtist }
        return Cleaned(finalTitle, finalArtist, ambiguous)
    }

    /** Noise removal + "feat." normalization. Never splits. */
    fun cleanTitle(title: String): String {
        var t = preClean(title)
        val segments = t.split(pipeSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size > 1) t = segments.firstOrNull { !isNoise(it) } ?: segments.first()
        // "Song - Video Oficial" / "Song - Official Audio"
        val dashParts = splitAllDashes(t)
        if (dashParts.size > 1) {
            val kept = dashParts.filterIndexed { i, p -> i == 0 || !isNoise(p) }
            t = kept.joinToString(" - ")
        }
        t = stripTrailingNoise(t)
        t = normalizeFeat(t)
        t = wrapLooseFeat(t)
        return tidy(t)
    }

    /** "Soda Stereo - Topic" → "Soda Stereo", "AnittaVEVO" → "Anitta", "Soda Stereo Oficial" → "Soda Stereo". */
    fun cleanArtist(artist: String): String {
        var a = removeEmoji(artist).replace(ws, " ").trim()
        a = a.replace(topicSuffix, "").trim()
        if (vevoSuffix.containsMatchIn(a) && a.length > 4) {
            a = a.replace(vevoSuffix, "").trim()
            if (!a.contains(' ')) a = a.replace(camelBoundary, " ")
        }
        val withoutOfficial = a.replace(officialSuffix, "").trim()
        if (withoutOfficial.isNotEmpty()) a = withoutOfficial
        a = normalizeFeat(a)
        return tidy(a)
    }

    /** "Soda Stereo - Topic": YouTube's auto-generated channel for an artist's catalog audio. */
    fun isTopicChannel(name: String): Boolean = topicSuffix.containsMatchIn(name.trim())

    /** "ft." / "Ft" / "FEAT" / "featuring" → "feat."; "(Part. X)" → "(feat. X)". */
    fun normalizeFeat(s: String): String =
        s.replace(partFeat, "(feat. ").replace(featToken, "feat. ").replace(ws, " ").trim()

    /** Title reduced to the song name, for matching across catalogs: no brackets, no "- Remastered", no feat. */
    fun baseTitle(title: String): String {
        var t = cleanTitle(title)
        t = t.replace(bracketGroup, " ")
        splitDash(t)?.let { (l, r) -> if (isVersionSuffix(r)) t = l }
        featToken.find(t)?.let { m -> if (m.range.first > 0) t = t.substring(0, m.range.first) }
        return tidy(t).ifBlank { tidy(cleanTitle(title)) }
    }

    /** The first credited artist: "Bad Bunny & Jhayco" → "Bad Bunny", "Anitta feat. X" → "Anitta". */
    fun primaryArtist(artist: String): String {
        val a = cleanArtist(artist)
        val cut = Regex("(?i)\\s*(,|&|\\+|\\bfeat\\.|\\bx\\b|\\bvs\\.?|\\bwith\\b|\\by\\b|\\be\\b|/)\\s*").find(a)
        return if (cut != null && cut.range.first > 0) a.substring(0, cut.range.first).trim() else a
    }

    /** Variants a title declares (live, remix…), so "Song" is not matched with "Song (Live)". */
    fun versions(title: String): Set<Version> {
        val k = TextMatch.key(title)
        return versionPatterns.filter { (_, re) -> re.containsMatchIn(k) }.map { it.first }.toSet()
    }

    /** True when the whole text only describes the video ("Official Video", "Letra", "HD"…). */
    fun isNoise(text: String): Boolean {
        val tokens = TextMatch.key(text).replace(Regex("\\bm v\\b"), "mv").split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val translated = tokens.any { it in translationWords }
        var noise = 0
        for (tk in tokens) {
            when {
                tk in noiseWords -> noise++
                tk in connectorWords -> Unit
                translated && tk in languageWords -> Unit
                tk.length == 4 && tk.all { it.isDigit() } && (tk.startsWith("19") || tk.startsWith("20")) -> Unit
                else -> return false
            }
        }
        return noise > 0
    }

    // ---------------------------------------------------------------------------------------

    private fun preClean(raw: String): String {
        var t = raw
            .replace('【', '[').replace('】', ']').replace('（', '(').replace('）', ')')
            .replace('［', '[').replace('］', ']')
        t = removeEmoji(t)
        t = t.replace(hashtags, " ")
        t = t.replace(ws, " ").trim()
        t = cleanBrackets(t)
        t = unquoteLeading(t)
        return t.replace(ws, " ").trim()
    }

    private fun cleanBrackets(input: String): String =
        bracketGroup.replace(input) { m ->
            val open = m.value.first()
            val close = m.value.last()
            val inner = (m.groups[1] ?: m.groups[2] ?: m.groups[3])?.value?.trim().orEmpty()
            when {
                inner.isEmpty() -> " "
                isNoise(inner) -> " "
                prodGroup.matches(inner) -> " "
                else -> {
                    // "(Live - Official Video)" → "(Live)"; "(Official Live Video)" → "(Live)"
                    val parts = inner.split(Regex("\\s+[-–|/,]\\s+|\\s*[|/]\\s*")).filter { it.isNotBlank() }
                    val kept = parts.filterNot { isNoise(it) }.ifEmpty { parts }
                    var text = kept.joinToString(" - ").trim()
                    if (keepPattern.containsMatchIn(TextMatch.key(text))) text = trimNoiseWords(text)
                    "$open${normalizeFeat(text)}$close"
                }
            }
        }

    private fun trimNoiseWords(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && TextMatch.key(words.first()).let { it in noiseWords }) words.removeAt(0)
        while (words.size > 1 && TextMatch.key(words.last()).let { it in noiseWords }) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }

    private fun stripTrailingNoise(t: String): String {
        // Only text outside brackets at the very end: "Song Official Video", "Song HD".
        val words = t.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return t
        for (k in 1 until words.size) {
            val suffix = words.subList(k, words.size)
            if (suffix.any { it.contains('(') || it.contains(')') || it.contains('[') || it.contains(']') }) continue
            val suffixText = suffix.joinToString(" ")
            if (!isNoise(suffixText)) continue
            val tokens = TextMatch.tokens(suffixText)
            val strong = tokens.size >= 2 || tokens.singleOrNull() in trailingStrongWords
            val rest = words.subList(0, k).joinToString(" ")
            if (strong && rest.any { it.isLetterOrDigit() }) return rest.trimEnd(' ', '-', '–', '—', '|', ':', ',')
        }
        return t
    }

    /** "Song feat. X" (outside brackets) → "Song (feat. X)". */
    private fun wrapLooseFeat(t: String): String {
        val m = Regex("(?<![(\\[])\\bfeat\\. ").find(t) ?: return t
        if (m.range.first == 0) return t
        val before = t.substring(0, m.range.first)
        if (before.count { it == '(' } > before.count { it == ')' }) return t // already inside "(…"
        val after = t.substring(m.range.last + 1)
        val stop = Regex("\\s+[(\\[]|\\s+[-–—]\\s+").find(after)?.range?.first ?: after.length
        val guests = after.substring(0, stop).trim()
        if (guests.isEmpty()) return t
        return "${before.trim()} (feat. $guests)${after.substring(stop)}"
    }

    private fun unquoteLeading(t: String): String {
        leadingQuoted.matchEntire(t)?.let { m ->
            val rest = m.groupValues[2]
            if (rest.isBlank() || rest.trimStart().startsWith("(") || rest.trimStart().startsWith("[") || isNoise(rest)) {
                return (m.groupValues[1] + rest).trim()
            }
        }
        // 'Artist - "Song" (Live)'
        return t.replace(Regex("(\\s[-–—]\\s)[\"“‘'](.+?)[\"”’'](?=\\s|$)")) { it.groupValues[1] + it.groupValues[2] }
    }

    /** First top-level "L - R" split (outside brackets), or null. */
    private fun splitDash(t: String): Pair<String, String>? {
        val depthAt = depthMap(t)
        val m = dashSep.findAll(t).firstOrNull { depthAt[it.range.first] == 0 } ?: return null
        val l = t.substring(0, m.range.first).trim()
        val r = t.substring(m.range.last + 1).trim()
        if (l.isEmpty() || r.isEmpty()) return null
        if (!l.any { it.isLetterOrDigit() } || !r.any { it.isLetterOrDigit() }) return null
        return l to r
    }

    private fun splitAllDashes(t: String): List<String> {
        val depthAt = depthMap(t)
        val parts = mutableListOf<String>()
        var start = 0
        for (m in dashSep.findAll(t)) {
            if (depthAt[m.range.first] != 0) continue
            parts += t.substring(start, m.range.first).trim()
            start = m.range.last + 1
        }
        parts += t.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    private fun depthMap(t: String): IntArray {
        val out = IntArray(t.length + 1)
        var d = 0
        for (i in t.indices) {
            val c = t[i]
            if (c == '(' || c == '[' || c == '{') d++
            out[i] = d
            if ((c == ')' || c == ']' || c == '}') && d > 0) d--
        }
        out[t.length] = d
        return out
    }

    private fun isVersionSuffix(s: String): Boolean = versionSuffixStart.matches(TextMatch.key(s))

    /**
     * Whether [candidate] names the uploader [artist]. Loose mode also accepts credits that include
     * it ("Anitta, Pedro Sampaio" for "Anitta"); strict mode wants the same name.
     */
    private fun sameArtist(candidate: String, artist: String, strict: Boolean = false): Boolean {
        if (artist.isBlank() || candidate.isBlank()) return false
        val c = cleanArtist(candidate.replace(bracketGroup, " "))
        if (c.isBlank()) return false
        if (TextMatch.similarity(c, artist) >= 0.85) return true
        if (strict) return false
        return TextMatch.contains(c, artist) || (TextMatch.tokens(c).size >= 2 && TextMatch.contains(artist, c))
    }

    private fun removeEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val type = Character.getType(cp)
            val drop = type == Character.OTHER_SYMBOL.toInt() ||
                cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF ||
                cp in 0xE0020..0xE007F
            if (!drop) sb.appendCodePoint(cp) else sb.append(' ')
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun tidy(s: String): String {
        var t = s.replace(ws, " ").trim()
        t = t.replace(Regex("\\(\\s+"), "(").replace(Regex("\\s+\\)"), ")")
            .replace(Regex("\\[\\s+"), "[").replace(Regex("\\s+]"), "]")
            .replace(Regex("\\(\\s*\\)|\\[\\s*]"), "")
        t = t.trim(' ', '-', '–', '—', '|', ':', ',', ';', '~', '/', '•', '·')
        return t.replace(ws, " ").trim()
    }
}
