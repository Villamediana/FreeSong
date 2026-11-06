package com.villamediana.sound.online

import androidx.annotation.StringRes
import com.villamediana.sound.R
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** Maps whatever NewPipe / OkHttp threw into an [OnlineException] with a meaningful [OnlineException.Kind]. */
internal object OnlineErrors {

    fun map(t: Throwable, what: String): OnlineException {
        if (t is OnlineException) return t
        val kind = kindOf(t)
        return OnlineException("$what: ${t.message ?: t.javaClass.simpleName}", t, kind)
    }

    fun kindOf(t: Throwable): OnlineException.Kind {
        for (c in chain(t)) {
            when (c) {
                is OnlineException -> return c.kind
                is ReCaptchaException, is SignInConfirmNotBotException -> return OnlineException.Kind.RATE_LIMITED
                is AgeRestrictedContentException -> return OnlineException.Kind.AGE_RESTRICTED
                is GeographicRestrictionException, is UnsupportedContentInCountryException -> return OnlineException.Kind.GEO_BLOCKED
                is ContentNotAvailableException -> {
                    val msg = c.message.orEmpty().lowercase()
                    return if ("exist" in msg || "not found" in msg) OnlineException.Kind.NOT_FOUND else OnlineException.Kind.UNAVAILABLE
                }
                is ContentNotSupportedException, is AccountTerminatedException -> return OnlineException.Kind.UNAVAILABLE
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException,
                is SSLException, is SocketException -> return OnlineException.Kind.NO_NETWORK
            }
        }
        return if (chain(t).any { it is IOException }) OnlineException.Kind.NO_NETWORK else OnlineException.Kind.UNKNOWN
    }

    /** Worth one more try: a dropped connection or a timeout — not "offline", not a refusal. */
    fun isTransient(t: Throwable): Boolean {
        val all = chain(t)
        if (all.any { it is UnknownHostException || it is ReCaptchaException || it is ContentNotAvailableException || it is OnlineException }) return false
        return all.any { it is SocketTimeoutException || it is InterruptedIOException || it is SocketException || (it is IOException && it !is ConnectException) }
    }

    private fun chain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>(4)
        var c: Throwable? = t
        while (c != null && c !in out && out.size < 8) { out += c; c = c.cause }
        return out
    }
}

/** Runs [block] (blocking network work), retrying once on a transient I/O failure, and maps errors. */
internal inline fun <T> onlineCall(what: String, block: () -> T): T {
    try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (first: Throwable) {
        if (!OnlineErrors.isTransient(first)) throw OnlineErrors.map(first, what)
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (second: Throwable) {
            throw OnlineErrors.map(second, what)
        }
    }
}

/** A short, localized explanation of an online failure — for snackbars and the downloads screen. */
@StringRes
fun OnlineException.Kind.messageRes(): Int = when (this) {
    OnlineException.Kind.NO_NETWORK -> R.string.online_error_no_network
    OnlineException.Kind.NOT_FOUND -> R.string.online_error_not_found
    OnlineException.Kind.AGE_RESTRICTED -> R.string.online_error_age_restricted
    OnlineException.Kind.GEO_BLOCKED -> R.string.online_error_geo_blocked
    OnlineException.Kind.UNAVAILABLE -> R.string.online_error_unavailable
    OnlineException.Kind.RATE_LIMITED -> R.string.online_error_rate_limited
    OnlineException.Kind.UNKNOWN -> R.string.online_error_unknown
}

/** Kind of any throwable coming out of the online layer (UNKNOWN for non-network bugs). */
fun Throwable.onlineKind(): OnlineException.Kind = OnlineErrors.kindOf(this)
