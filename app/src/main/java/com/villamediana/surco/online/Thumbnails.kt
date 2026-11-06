package com.villamediana.surco.online

/** Picks the sharpest thumbnail and asks Google's image servers for the size we actually want. */
object Thumbnails {
    /** Square song art (YouTube Music rows, track covers). */
    const val SONG = 720
    /** Album / playlist / artist art (detail headers). */
    const val COLLECTION = 1080

    data class Thumb(val url: String, val width: Int, val height: Int)

    private val sizeWH = Regex("=w\\d+-h\\d+")
    private val sizeS = Regex("=s\\d+(?=-|$)")
    private val ytimg = Regex("^https?://i\\d?\\.ytimg\\.com/(vi|vi_webp)/([A-Za-z0-9_-]{11})/([a-z0-9_]+)\\.(jpg|webp)(\\?.*)?$")

    /** Largest by area (unknown sizes count as small), upgraded with [upgrade]. */
    fun best(thumbs: List<Thumb>, size: Int): String? {
        val pick = thumbs.filter { it.url.isNotBlank() }
            .maxWithOrNull(compareBy<Thumb> { it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0) }.thenBy { rank(it.url) })
            ?: return null
        return upgrade(pick.url, size)
    }

    /**
     * googleusercontent / ggpht: rewrite the size suffix (`=w120-h120-l90-rj`, `=s192`) to [size].
     * i.ytimg video frames: drop the signed resize parameters and prefer the full-size 16:9 frame
     * (`hq720`/`maxresdefault` stay as they are — they exist when YouTube listed them).
     */
    fun upgrade(url: String, size: Int): String {
        val u = if (url.startsWith("//")) "https:$url" else url
        if (isGoogleImage(u)) {
            return when {
                sizeWH.containsMatchIn(u) -> u.replace(sizeWH, "=w$size-h$size")
                sizeS.containsMatchIn(u) -> u.replace(sizeS, "=s$size")
                else -> u
            }
        }
        ytimg.matchEntire(u)?.let { m ->
            val id = m.groupValues[2]
            val name = m.groupValues[3]
            val file = when {
                name.startsWith("maxres") -> "maxresdefault"
                name.startsWith("hq720") -> "hq720"
                // Signed hqdefault/mqdefault crops are 16:9 without bars; the unsigned mqdefault is too.
                name.startsWith("hqdefault") || name.startsWith("mqdefault") || name.startsWith("sddefault") ||
                    name == "default" -> if (m.groupValues[5].isNotEmpty()) return u else "mqdefault"
                else -> return u
            }
            return "https://i.ytimg.com/vi/$id/$file.jpg"
        }
        return u
    }

    private fun isGoogleImage(u: String): Boolean =
        u.contains("googleusercontent.com/") || u.contains("ggpht.com/")

    private fun rank(url: String): Int = when {
        url.contains("maxresdefault") -> 3
        url.contains("hq720") -> 2
        isGoogleImage(url) -> 1
        else -> 0
    }
}
