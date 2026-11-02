package de.sofahub.app.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gemeinsame Basis (Connection-Pool/Dispatcher geteilt); Ableitungen setzen Zeitlimits und Regeln pro Zweck. */
object Http {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Für GitHub-API und Update-Downloads: nur https, Redirects werden selbst verfolgt und nur auf [allowedHosts]
     * zugelassen (kein Rückfall auf http, keine fremden Hosts).
     */
    fun githubClient(allowedHosts: Set<String>): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(HttpsOnlyInterceptor(allowedHosts))
        .addInterceptor(RedirectInterceptor(allowedHosts))
        .build()
}

class BlockedRequestException(message: String) : IOException(message)

class HttpsOnlyInterceptor(private val allowedHosts: Set<String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        check(url)
        return chain.proceed(chain.request())
    }

    fun check(url: HttpUrl) {
        if (!url.isHttps) throw BlockedRequestException("Nur https erlaubt")
        if (url.host !in allowedHosts) throw BlockedRequestException("Host nicht erlaubt")
    }
}

/** Folgt bis zu 5 Redirects, jeder Zielhost muss https und erlaubt sein. Authorization-Header werden nie weitergereicht. */
class RedirectInterceptor(private val allowedHosts: Set<String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hops = 0
        while (response.isRedirect) {
            if (++hops > 5) {
                response.close()
                throw BlockedRequestException("Zu viele Weiterleitungen")
            }
            val location = response.header("Location")
            val target = location?.let { request.url.resolve(it) }
            response.close()
            if (target == null) throw BlockedRequestException("Weiterleitung ohne Ziel")
            HttpsOnlyInterceptor(allowedHosts).check(target)
            request = request.newBuilder().url(target).removeHeader("Authorization").build()
            response = chain.proceed(request)
        }
        return response
    }
}

