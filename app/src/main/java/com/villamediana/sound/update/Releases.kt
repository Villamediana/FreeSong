package com.villamediana.sound.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A published version of the app: what the update sheet shows and where its APK is. */
data class AppRelease(
    /** "1.13.0" (the tag without its "v"). */
    val version: String,
    /** The release text, as written on GitHub (light Markdown). */
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    /** Hex SHA-256 of the APK, when GitHub gives it ("digest": "sha256:…"). */
    val sha256: String?,
)

/** GitHub's "latest release" answer → [AppRelease]. Drafts, pre-releases and releases without an APK give null. */
object ReleaseFeed {
    /** The newest public release of the app (GitHub leaves drafts and pre-releases out of /latest). */
    const val LATEST_URL = "https://api.github.com/repos/Villamediana/FreeSong/releases/latest"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): AppRelease? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        if (root.bool("draft") == true || root.bool("prerelease") == true) return null
        val version = root.str("tag_name")?.trim()?.removePrefix("v")?.removePrefix("V")
            ?.takeIf { Versions.parts(it) != null } ?: return null
        val apk = (root["assets"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { a -> a.str("name")?.endsWith(".apk", ignoreCase = true) == true && a.str("browser_download_url") != null }
            ?: return null
        val sha = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        return AppRelease(
            version = version,
            notes = root.str("body").orEmpty().trim(),
            apkUrl = apk.str("browser_download_url")!!,
            apkName = apk.str("name")!!,
            sizeBytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256 = sha,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/** "1.12.4" against "1.13.0", number by number. */
object Versions {
    /** The numbers of a version ("1.12.4" → [1, 12, 4]); anything after a "-" or "+" is ignored. */
    fun parts(version: String): List<Int>? {
        val core = version.trim().substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }

    fun compare(a: String, b: String): Int {
        val pa = parts(a) ?: return 0
        val pb = parts(b) ?: return 0
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean =
        parts(candidate) != null && parts(current) != null && compare(candidate, current) > 0
}

/** The release text in plain lines for the sheet: bullets, short headings, paragraphs. Markdown marks dropped. */
object ReleaseNotes {
    sealed interface Line {
        val text: String
        data class Heading(override val text: String) : Line
        data class Bullet(override val text: String) : Line
        data class Paragraph(override val text: String) : Line
    }

    fun lines(notes: String): List<Line> = notes.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.matches(RULE) }
        .mapNotNull { raw ->
            when {
                raw.startsWith("#") -> clean(raw.trimStart('#')).takeIf { it.isNotEmpty() }?.let { Line.Heading(it) }
                BULLET.containsMatchIn(raw) -> clean(raw.replaceFirst(BULLET, "")).takeIf { it.isNotEmpty() }?.let { Line.Bullet(it) }
                else -> clean(raw).takeIf { it.isNotEmpty() }?.let { Line.Paragraph(it) }
            }
        }
        .toList()

    /** Bold/italic/code marks off, links down to their text. */
    private fun clean(s: String): String = s
        .replace(LINK, "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()

    private val BULLET = Regex("^([-*+•]|\\d+[.)])\\s+")
    private val LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    private val RULE = Regex("^([-*_]\\s*){3,}$")
}
