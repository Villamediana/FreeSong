package com.villamediana.surco.playback.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.villamediana.surco.data.AudioQuality
import com.villamediana.surco.online.AudioStream
import com.villamediana.surco.online.NewPipeHttp
import com.villamediana.surco.online.OnlineException
import com.villamediana.surco.online.YouTubeSource
import com.villamediana.surco.online.onlineKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Placeholder uris of online media. The player never sees a googlevideo url (they expire): items
 * carry `surco://stream/<videoId>?q=<quality>` and the data source resolves it when it reads.
 * The quality travels in the uri so a song keeps one format from start to end (a seek reopens the
 * stream at a byte offset of that format), even if the setting changes while it plays.
 */
object StreamUris {
    private const val SCHEME = "surco"
    private const val AUDIO = "stream"
    private const val VIDEO = "video"
    private const val QUALITY = "q"
    const val VIDEO_PART = "v"
    const val AUDIO_PART = "a"

    fun audio(videoId: String, quality: AudioQuality): Uri = Uri.Builder()
        .scheme(SCHEME).authority(AUDIO).appendPath(videoId)
        .appendQueryParameter(QUALITY, quality.name)
        .build()

    fun audioKey(uri: Uri?): StreamKey? {
        if (uri == null || uri.scheme != SCHEME || uri.authority != AUDIO) return null
        val videoId = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        val quality = uri.getQueryParameter(QUALITY)?.let { q -> AudioQuality.entries.firstOrNull { it.name == q } } ?: AudioQuality.BEST
        return StreamKey(videoId, quality)
    }

    /** One part ([VIDEO_PART] / [AUDIO_PART]) of a music video. */
    fun video(videoId: String, part: String): Uri = Uri.Builder()
        .scheme(SCHEME).authority(VIDEO).appendPath(videoId).appendPath(part)
        .build()

    /** videoId to part, for a [video] uri. */
    fun videoPart(uri: Uri?): Pair<String, String>? {
        if (uri == null || uri.scheme != SCHEME || uri.authority != VIDEO) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0].isBlank()) return null
        return segments[0] to segments[1]
    }
}

/** An online song's audio stream, as resolved for one quality. */
data class StreamKey(val videoId: String, val quality: AudioQuality)

/**
 * Resolution of an online stream failed (offline, unavailable, region-locked…). Its [reason] is a
 * custom [PlaybackException] error code, so the app's controller can tell the listener why.
 */
class StreamResolveException(val kind: OnlineException.Kind, cause: Throwable) :
    DataSourceException("Stream resolution failed: ${cause.message}", cause, StreamErrors.code(kind)) {
    /** The phone had no network at all: the stream wasn't even tried, and retrying now won't help. */
    val offline: Boolean get() = cause is NoNetworkException

    companion object {
        fun offline() = StreamResolveException(OnlineException.Kind.NO_NETWORK, NoNetworkException())
    }
}

/** No network with internet on the phone (see [hasInternet]). */
class NoNetworkException : IOException("No network connection")

/** Whether the phone has a network with internet right now. */
internal fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Error codes of online playback, as they reach [androidx.media3.session.MediaController]s. */
object StreamErrors {
    private const val BASE = PlaybackException.CUSTOM_ERROR_CODE_BASE + 4100

    fun code(kind: OnlineException.Kind): Int = BASE + kind.ordinal

    /** The online error behind a player error code, or null when it isn't one of ours. */
    fun kindOf(errorCode: Int): OnlineException.Kind? = OnlineException.Kind.entries.getOrNull(errorCode - BASE)
        ?: when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> OnlineException.Kind.NO_NETWORK
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> OnlineException.Kind.UNAVAILABLE
            else -> null
        }
}

/**
 * Turns a blocking resolution into what a data source may throw: a [StreamResolveException] for
 * online failures, [InterruptedIOException] when the loader gives up.
 */
internal fun <T> resolveBlocking(block: suspend () -> T): T = try {
    runBlocking { block() }
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedIOException("Stream resolution interrupted")
} catch (e: CancellationException) {
    throw InterruptedIOException("Stream resolution cancelled")
} catch (e: InterruptedIOException) {
    throw e
} catch (t: Throwable) {
    throw StreamResolveException(t.onlineKind(), t)
}

/**
 * Stream urls of online songs for the music player: resolved when the player reads (or ahead of
 * time for the next song), cached ~4 h per video and quality, refreshed when googlevideo refuses one.
 * Without network ([online] false) a stream fails at once, before any request.
 */
internal class OnlineStreams(
    scope: CoroutineScope,
    private val online: () -> Boolean = { true },
    private val youTube: () -> YouTubeSource,
) : ChunkedHttpDataSource.UriResolver {

    private val urls = StreamUrlCache<StreamKey, AudioStream>(scope, urlOf = { it.url }) { key, _ ->
        youTube().audioStream(key.videoId, key.quality)
    }

    /** Resolves the stream behind [uri] in the background (no-op for local files, or offline). */
    fun prefetch(uri: Uri?) {
        val key = StreamUris.audioKey(uri) ?: return
        if (online()) urls.prefetch(key)
    }

    override fun resolve(uri: Uri, refresh: Boolean): Uri {
        val key = StreamUris.audioKey(uri) ?: return uri
        // Even a cached url would only fail later, after the HTTP layer's own retries.
        if (!online()) throw StreamResolveException.offline()
        return Uri.parse(resolveBlocking { urls.get(key, refresh) }.url)
    }
}

/** The data sources online media is read through: local files as usual, streams in 2 MB ranges. */
@OptIn(UnstableApi::class)
internal object StreamDataSources {
    /** HTTP the way the downloader talks to googlevideo (same User-Agent, generous timeouts). */
    fun http(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(NewPipeHttp.USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)

    /** file://, content:// … as [DefaultDataSource] does; everything else through the chunked source. */
    fun factory(context: Context, resolver: ChunkedHttpDataSource.UriResolver): DataSource.Factory =
        DefaultDataSource.Factory(context, ChunkedHttpDataSource.Factory(http(), ChunkedHttpDataSource.DEFAULT_CHUNK_SIZE, resolver))
}

/**
 * Retries what may get better (a dropped connection, a network that comes and goes) the default
 * way, but gives up at once on a stream that can't be resolved (unavailable, region-locked,
 * age-gated) or when the phone has no network at all: the service then moves on without the
 * listener waiting through retries.
 */
@OptIn(UnstableApi::class)
internal class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val resolve = generateSequence(loadErrorInfo.exception as Throwable?) { it.cause }.take(8)
            .filterIsInstance<StreamResolveException>().firstOrNull()
        if (resolve != null) {
            if (resolve.offline) return C.TIME_UNSET
            return when (resolve.kind) {
                OnlineException.Kind.NO_NETWORK, OnlineException.Kind.UNKNOWN -> super.getRetryDelayMsFor(loadErrorInfo)
                else -> C.TIME_UNSET
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
