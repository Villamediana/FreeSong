package com.villamediana.surco.online

import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/** NewPipeExtractor's HTTP bridge over OkHttp. */
class NewPipeHttp(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val responseBody = response.body.string()
            return Response(response.code, response.message, response.headers.toMultimap(), responseBody, response.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        @Volatile private var initialized = false

        /** Idempotent. Uses the phone language/country so YouTube Music answers in the right market. */
        fun init(client: OkHttpClient, locale: Locale = Locale.getDefault()) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                val country = locale.country.takeIf { it.length == 2 } ?: "BR"
                NewPipe.init(NewPipeHttp(client), Localization(locale.language.ifEmpty { "pt" }, country), ContentCountry(country))
                initialized = true
            }
        }
    }
}
