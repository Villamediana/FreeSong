package com.villamediana.sound.online

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * A page read through the phone's own browser engine, off screen.
 *
 * Some sites (Cifra Club, behind Akamai) turn an HTTP library away by the shape of its connection,
 * whatever its headers say, and let Chrome through; a WebView is Chrome. Only the site's own files
 * load — no images, no ads, nothing from other hosts — and the page is read as soon as its
 * [ready] element is parsed, without waiting for the rest.
 */
object BrowserFetch {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun html(context: Context, url: String, ready: String, timeoutMs: Long = 25_000): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.Main) {
            val site = siteOf(android.net.Uri.parse(url).host.orEmpty())
            val view = WebView(context.applicationContext)
            val handler = Handler(Looper.getMainLooper())
            try {
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkImage = true
                view.settings.loadsImagesAutomatically = false
                view.settings.mediaPlaybackRequiresUserGesture = true
                suspendCancellableCoroutine { cont ->
                    // The page once [ready] is parsed; "-" when it loaded all the way without one.
                    val probe = "(function(){var e=document.querySelector(${JSONObject.quote(ready)});" +
                        "if(e&&document.readyState!=='loading')return document.documentElement.outerHTML;" +
                        "return document.readyState==='complete'&&location.href!=='about:blank'?'-':'';})()"
                    var emptyLoads = 0
                    fun read() {
                        if (!cont.isActive) return
                        view.evaluateJavascript(probe) { raw ->
                            if (!cont.isActive) return@evaluateJavascript
                            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                            when {
                                text.length > 1 -> cont.resume(text)
                                // Loaded and nothing there (refused, or another page): a few seconds for a
                                // guard page to move on, then give up.
                                text == "-" && ++emptyLoads > 8 -> cont.resume("")
                                else -> handler.postDelayed({ read() }, 400)
                            }
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        var polling = false

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val host = request.url.host.orEmpty()
                            return if (siteOf(host) == site || host.endsWith("sscdn.co")) null
                            else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageCommitVisible(view: WebView, url: String?) = start()

                        override fun onPageFinished(view: WebView, url: String?) = start()

                        fun start() {
                            if (polling) return
                            polling = true
                            read()
                        }
                    }
                    view.loadUrl(url)
                }
            } finally {
                handler.removeCallbacksAndMessages(null)
                view.stopLoading()
                view.destroy()
            }
        }
    }

    /** "www.cifraclub.com.br" → "cifraclub.com.br" (the last two labels, three under a country's .com/.net). */
    private fun siteOf(host: String): String {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        val keep = if (parts.size >= 3 && parts[parts.size - 1].length == 2 && parts[parts.size - 2] in setOf("com", "net", "org", "co")) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }
}
