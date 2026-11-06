package com.villamediana.surco.online

import kotlin.math.abs

/**
 * Chooses what "Ver o vídeo" plays. Pure: works on plain descriptions of the streams NewPipe found,
 * so the choice is testable without the network.
 */
internal object VideoStreams {

    /** One video stream (video-only or muxed). */
    data class Video(
        val url: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        /** "avc1.4d401f", "vp9", "av01…" (null when unknown). */
        val codec: String?,
        /** "video/mp4", "video/webm", "video/3gpp". */
        val mimeType: String,
        val bitrate: Int,
    ) {
        val isH264: Boolean get() = codec?.startsWith("avc1", ignoreCase = true) == true ||
            (codec.isNullOrBlank() && mimeType == MP4)
        val isMp4: Boolean get() = mimeType == MP4
        /** "720p" means the short side: a vertical 720×1280 video is 720p too. */
        val size: Int get() = if (width > 0 && height > 0) minOf(width, height) else height
    }

    const val MP4 = "video/mp4"

    /**
     * 1. the best H.264 MP4 video-only stream up to [maxHeight] + the audio stream (merged by the player);
     * 2. a muxed stream up to [maxHeight] (it carries its own audio);
     * 3. any other video-only stream up to [maxHeight] (VP9/AV1) + audio;
     * 4. the smallest H.264 / muxed stream above [maxHeight] — something is better than nothing.
     *
     * Heights are the short side ("720p" also for a vertical video). At the same size, 30 fps wins
     * over 60 fps (half the data, same picture for a music video).
     */
    fun pick(videoOnly: List<Video>, muxed: List<Video>, audio: AudioStream?, maxHeight: Int): VideoStream? {
        val cap = maxHeight.coerceAtLeast(MIN_HEIGHT)
        val better = compareBy<Video>({ it.size }, { if (it.fps in 1..30) 1 else 0 }, { it.bitrate })
        val smaller = compareBy<Video>({ it.size }, { it.fps }, { it.bitrate })
        val withAudio = audio?.takeIf { it.url.isNotBlank() }

        if (withAudio != null) {
            videoOnly.filter { it.isH264 && it.isMp4 && it.size in 1..cap }.maxWithOrNull(better)
                ?.let { return merged(it, withAudio) }
        }
        muxed.filter { it.size in 1..cap }.maxWithOrNull(better)?.let { return single(it) }
        if (withAudio != null) {
            videoOnly.filter { it.size in 1..cap }.maxWithOrNull(compareBy<Video>({ if (it.isMp4) 1 else 0 }).then(better))
                ?.let { return merged(it, withAudio) }
            videoOnly.filter { it.isH264 && it.isMp4 }.minWithOrNull(smaller)?.let { return merged(it, withAudio) }
        }
        return muxed.minWithOrNull(smaller)?.let(::single)
    }

    private fun merged(v: Video, a: AudioStream) =
        VideoStream(v.url, a.url, v.width, v.height, v.mimeType, a.mimeType)

    private fun single(v: Video) = VideoStream(v.url, null, v.width, v.height, v.mimeType, null)

    /** "720p60" / "1080p" → 720 / 1080 (NewPipe leaves height at 0 for some streams). */
    fun heightOf(resolution: String?): Int = resolution?.let { Regex("^(\\d{3,4})p").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

    private const val MIN_HEIGHT = 144
}

/**
 * Finds the official music video of a catalog audio track ("Artist - Topic" uploads show a still
 * cover). Pure scoring over search results: the same song (title, artist and version), a plausible
 * length, preferring real videos over lyric/visualizer uploads.
 */
internal object MusicVideoMatch {

    data class Candidate(
        val videoId: String,
        val title: String,
        val uploader: String,
        val durationMs: Long,
    )

    /** Below this the candidate is a different song (or a fan upload of something else). */
    private const val MIN_TITLE = 0.8

    /** A video may open with an intro or close with a scene, but it is never much shorter than the song. */
    private const val MAX_SHORTER_MS = 15_000L
    private const val MAX_LONGER_MS = 150_000L

    private val stillWords = Regex("\\b(lyric|lyrics|letra|letras|visualizer|visualiser|visualizador|audio|karaoke)\\b")
    private val officialWords = Regex("\\b(official|oficial|officiel|videoclip|videoclipe|clipe|clip|music video|mv)\\b")

    fun best(title: String, artist: String, durationMs: Long?, candidates: List<Candidate>, exclude: String? = null): Candidate? {
        val wantTitle = TitleCleaner.baseTitle(title)
        val wantArtist = TitleCleaner.primaryArtist(artist)
        val wantVersions = TitleCleaner.versions(title)
        return candidates
            .asSequence()
            .filter { it.videoId != exclude && it.durationMs > 0 && !TitleCleaner.isTopicChannel(it.uploader) }
            .mapNotNull { c -> score(c, wantTitle, wantArtist, wantVersions, durationMs)?.let { c to it } }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(c: Candidate, wantTitle: String, wantArtist: String, wantVersions: Set<TitleCleaner.Version>, durationMs: Long?): Double? {
        val cleaned = TitleCleaner.clean(c.title, c.uploader, fromMusicCatalog = false)
        if (TitleCleaner.versions(c.title) != wantVersions) return null
        val titleSim = maxOf(
            TextMatch.similarity(TitleCleaner.baseTitle(cleaned.title), wantTitle),
            // "Artist - Title" read the other way round by the splitter.
            TextMatch.similarity(TitleCleaner.baseTitle(cleaned.artist), wantTitle),
        )
        if (titleSim < MIN_TITLE) return null
        val uploader = TitleCleaner.cleanArtist(c.uploader)
        val artistOk = TextMatch.contains(cleaned.artist, wantArtist) || TextMatch.contains(uploader, wantArtist) ||
            TextMatch.contains(c.title, wantArtist) || TextMatch.similarity(uploader, wantArtist) >= 0.8
        if (!artistOk) return null
        var score = titleSim
        if (durationMs != null && durationMs > 0) {
            val diff = c.durationMs - durationMs
            if (diff < -MAX_SHORTER_MS || diff > MAX_LONGER_MS) return null
            score -= abs(diff) / 60_000.0 * 0.15
        }
        val key = TextMatch.key(c.title)
        if (stillWords.containsMatchIn(key)) score -= 0.3
        if (officialWords.containsMatchIn(key)) score += 0.1
        if (TextMatch.similarity(uploader, wantArtist) >= 0.9 || c.uploader.trim().endsWith("VEVO", ignoreCase = true)) score += 0.15
        return score
    }
}
