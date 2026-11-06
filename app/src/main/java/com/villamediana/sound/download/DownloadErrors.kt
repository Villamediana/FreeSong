package com.villamediana.sound.download

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.villamediana.sound.R
import com.villamediana.sound.online.OnlineException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** Why a download did not finish, in terms the user can act on. */
internal sealed interface DownloadError {
    data object Network : DownloadError
    data object Timeout : DownloadError
    data class Http(val code: Int) : DownloadError
    data object Forbidden : DownloadError
    data object Unavailable : DownloadError
    data object AgeRestricted : DownloadError
    data object GeoBlocked : DownloadError
    data object RateLimited : DownloadError
    data object Storage : DownloadError
    data object Incomplete : DownloadError
    data object Unknown : DownloadError
}

/** Short, human-readable text of a [DownloadError] (stored in [com.villamediana.sound.data.db.DownloadTask.error]). */
internal fun interface DownloadMessages {
    fun text(error: DownloadError): String
}

internal class AndroidDownloadMessages(private val context: Context) : DownloadMessages {
    override fun text(error: DownloadError): String = when (error) {
        DownloadError.Network -> context.getString(R.string.download_error_network)
        DownloadError.Timeout -> context.getString(R.string.download_error_timeout)
        is DownloadError.Http -> context.getString(R.string.download_error_http, error.code)
        DownloadError.Forbidden -> context.getString(R.string.download_error_forbidden)
        DownloadError.Unavailable -> context.getString(R.string.download_error_unavailable)
        DownloadError.AgeRestricted -> context.getString(R.string.download_error_age)
        DownloadError.GeoBlocked -> context.getString(R.string.download_error_geo)
        DownloadError.RateLimited -> context.getString(R.string.download_error_rate_limited)
        DownloadError.Storage -> context.getString(R.string.download_error_storage)
        DownloadError.Incomplete -> context.getString(R.string.download_error_incomplete)
        DownloadError.Unknown -> context.getString(R.string.download_error_unknown)
    }
}

/** How the engine reacts to an exception thrown while downloading one task. */
internal sealed interface Failure {
    /** The phone lost its connection: back to the queue, no attempt spent, resume when it returns. */
    data object NetworkLost : Failure

    /** Retrying cannot help (video gone, region/age locked, disk full): fail now. */
    data class Permanent(val error: DownloadError) : Failure

    /** Worth another attempt after a short backoff. */
    data class Transient(val error: DownloadError) : Failure
}

internal fun classify(t: Throwable, online: Boolean): Failure = when (t) {
    is OnlineException -> when (t.kind) {
        OnlineException.Kind.NO_NETWORK -> if (online) Failure.Transient(DownloadError.Network) else Failure.NetworkLost
        OnlineException.Kind.NOT_FOUND, OnlineException.Kind.UNAVAILABLE -> Failure.Permanent(DownloadError.Unavailable)
        OnlineException.Kind.AGE_RESTRICTED -> Failure.Permanent(DownloadError.AgeRestricted)
        OnlineException.Kind.GEO_BLOCKED -> Failure.Permanent(DownloadError.GeoBlocked)
        OnlineException.Kind.RATE_LIMITED -> Failure.Transient(DownloadError.RateLimited)
        OnlineException.Kind.UNKNOWN -> if (online) Failure.Transient(DownloadError.Unknown) else Failure.NetworkLost
    }
    is SQLiteFullException -> Failure.Permanent(DownloadError.Storage)
    is HttpStatusException -> when (t.code) {
        403, 410 -> Failure.Transient(DownloadError.Forbidden)
        429 -> Failure.Transient(DownloadError.RateLimited)
        else -> Failure.Transient(DownloadError.Http(t.code))
    }
    is IOException -> when {
        t.isDiskFull() -> Failure.Permanent(DownloadError.Storage)
        !online -> Failure.NetworkLost
        t is SocketTimeoutException || t is InterruptedIOException -> Failure.Transient(DownloadError.Timeout)
        t.message?.startsWith("Incomplete") == true -> Failure.Transient(DownloadError.Incomplete)
        else -> Failure.Transient(DownloadError.Network)
    }
    else -> if (online) Failure.Transient(DownloadError.Unknown) else Failure.NetworkLost
}

private fun Throwable.isDiskFull(): Boolean {
    var e: Throwable? = this
    while (e != null) {
        val m = e.message.orEmpty()
        if ("ENOSPC" in m || "No space left" in m) return true
        e = e.cause
    }
    return false
}

/** True when the phone has a network that claims internet access. */
internal fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
