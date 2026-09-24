package com.v2ray.ang.util

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.dto.UrlContentRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

object HttpUtil {

    /**
     * User-Agent sent for a subscription fetch when neither the subscription nor the operator
     * configured one.
     *
     * Panels (Remnawave/3x-ui) pick the response format — XRAY_JSON template vs base64 link list —
     * from this header, using their own client->template mapping, so no value the app can pick on
     * its own guarantees the template: an unknown client string gets the base64 link list, and so
     * does a v2rayNG one unless the operator mapped it. The fallback is therefore the upstream
     * client string — honest about which client this is, understood by every panel, and answered
     * with the link list this app parses. Negotiating the template is the operator's job, via
     * `SUB_USER_AGENT` (see [com.v2ray.ang.auth.BackendConfig.subscriptionUserAgent]).
     *
     * Версия в строке — версия v2rayNG, на которой основано приложение, а не номер выпуска
     * departament. Номер выпуска теперь общий с программой для ПК (1.0.x), и панель, которая
     * выбирает формат подписки по версии клиента, не должна вдруг получить «v2rayNG/1.0.5» вместо
     * привычного ей «v2rayNG/2.2.1».
     */
    private const val UPSTREAM_CLIENT_VERSION = "2.2.1"
    val DEFAULT_SUBSCRIPTION_USER_AGENT: String get() = "v2rayNG/$UPSTREAM_CLIENT_VERSION"

    /**
     * Ask for the XRAY_JSON template first, but keep accepting the base64 link list: a panel that
     * honours Accept must not answer 406 for a plain-text subscription.
     */
    private const val SUBSCRIPTION_ACCEPT = "application/json, text/plain;q=0.9, */*;q=0.8"

    /** Copy chunk for a download that reports progress. `copyTo`'s own default, kept explicit. */
    private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024

    /**
     * Resolves the User-Agent of a subscription request. The caller-supplied value — the
     * per-subscription override, else the operator-configured UA — always wins and is never
     * rewritten, because the panel keys the response format off it; the default only fills a blank.
     *
     * A value that cannot travel in a header also falls back: the override is free text the user
     * types, and OkHttp throws while building the request on a control or non-ASCII character, so
     * one stray character (a Cyrillic client string, say) would otherwise fail every future update
     * of that subscription with nothing but a log line to explain it.
     *
     * Public so a screen that DISPLAYS the User-Agent can display the one that will actually be
     * sent, instead of the stored text: showing a value this function would replace is the exact
     * dishonesty the fallback would otherwise introduce. The editors validate on entry, so the
     * fallback should now only ever fire on a value stored by an older build.
     */
    fun resolveSubscriptionUserAgent(userAgent: String?): String =
        userAgent?.trim()?.takeIf { it.isNotEmpty() && isHeaderSafe(it) }
            ?: DEFAULT_SUBSCRIPTION_USER_AGENT

    /**
     * Mirrors OkHttp's own header-value check: printable ASCII, plus tab.
     *
     * Public because every value this app puts in a header has to pass it before OkHttp sees it:
     * OkHttp throws while BUILDING the request, so an unchecked non-ASCII value does not degrade
     * the request, it kills it. Callers outside this file need the same test to stay consistent
     * with what the request builders below accept.
     */
    fun isHeaderSafe(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

    /**
     * [value] when it can travel in a header (see [isHeaderSafe]), else [fallback].
     *
     * Public for the same reason [isHeaderSafe] is: every caller that builds a request — the
     * subscription fetch below and the backend API client — has to apply the same guard to the
     * same OEM-supplied values, or the half that skips it throws for the same devices.
     */
    fun headerSafeOr(value: String?, fallback: String): String =
        value?.takeIf { isHeaderSafe(it) } ?: fallback

    /** Mirrors [Utils.getDeviceName]'s own blank fallback, for a model name no header can carry. */
    const val FALLBACK_DEVICE_MODEL = "Android Device"

    /**
     * The host of [url], for a log line that must name an endpoint without leaking one.
     *
     * A подписка address carries the account token in its path — logging the URL whole put a working
     * credential into «Журнал» and into every log a user might send to support. The host answers the
     * question a log actually has ("which server did we talk to") and carries no secret.
     */
    fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(invalid address)"

    /**
     * Converts the domain part of a URL string to its IDN (Punycode, ASCII Compatible Encoding) format.
     *
     * For example, a URL like "https://例子.中国/path" will be converted to "https://xn--fsqu00a.xn--fiqs8s/path".
     *
     * @param str The URL string to convert (can contain non-ASCII characters in the domain).
     * @return The URL string with the domain part converted to ASCII-compatible (Punycode) format.
     */
    fun toIdnUrl(str: String): String {
        val url = URL(str)
        val host = url.host
        val asciiHost = IDN.toASCII(url.host, IDN.ALLOW_UNASSIGNED)
        if (host != asciiHost) {
            return str.replace(host, asciiHost)
        } else {
            return str
        }
    }

    /**
     * Converts a Unicode domain name to its IDN (Punycode, ASCII Compatible Encoding) format.
     * If the input is an IP address or already an ASCII domain, returns the original string.
     *
     * @param domain The domain string to convert (can include non-ASCII internationalized characters).
     * @return The domain in ASCII-compatible (Punycode) format, or the original string if input is an IP or already ASCII.
     */
    fun toIdnDomain(domain: String): String {
        // Return as is if it's a pure IP address (IPv4 or IPv6)
        if (Utils.isPureIpAddress(domain)) {
            return domain
        }

        // Return as is if already ASCII (English domain or already punycode)
        if (domain.all { it.code < 128 }) {
            return domain
        }

        // Otherwise, convert to ASCII using IDN
        return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED)
    }

    /**
     * Resolves a hostname to an IP address, returns original input if it's already an IP
     *
     * @param host The hostname or IP address to resolve
     * @param ipv6Preferred Whether to prefer IPv6 addresses, defaults to false
     * @return The resolved IP address or the original input (if it's already an IP or resolution fails)
     */
    fun resolveHostToIP(host: String, ipv6Preferred: Boolean = false): List<String>? {
        try {
            // If it's already an IP address, return it as a list
            if (Utils.isPureIpAddress(host)) {
                return null
            }

            // Get all IP addresses
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) {
                return null
            }

            // Sort addresses based on preference
            val sortedAddresses = if (ipv6Preferred) {
                addresses.sortedWith(compareByDescending { it is Inet6Address })
            } else {
                addresses.sortedWith(compareBy { it is Inet6Address })
            }

            val ipList = sortedAddresses.mapNotNull { it.hostAddress }

            LogUtil.i(AppConfig.TAG, "Resolved IPs for $host: ${ipList.joinToString()}")

            return ipList
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve host to IP", e)
            return null
        }
    }


    /**
     * What a plain GET actually ended in — because "null" answers two different questions with the
     * same word, and a caller that cannot tell them apart reports the wrong thing.
     *
     * [code] is the HTTP status when a server answered at all, and [NO_RESPONSE] when none did
     * (offline, blocked, DNS, timeout, TLS). A 404 from a feed that has nothing to list is an
     * ANSWER; it is not an outage, and it must not be reported as one — see
     * `UpdateCheckerManager.fetch`, which is where that distinction was missing and turned an empty
     * release list into «Не удалось связаться с сервером обновлений» plus a stack trace in «Журнал».
     */
    data class UrlContentOutcome(val body: String?, val code: Int) {
        val answered: Boolean get() = code != NO_RESPONSE
        val successful: Boolean get() = code in 200..299

        companion object {
            /** Nothing on the other end replied, so there is no status to reason about. */
            const val NO_RESPONSE = -1
        }
    }

    /**
     * Retrieves the content of a URL as a string.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     */
    fun getUrlContent(request: UrlContentRequest): String? {
        val outcome = getUrlOutcome(request)
        // The status is only worth a line when the caller is not going to look at it — the ones
        // that do (getUrlOutcome) decide for themselves whether it is news.
        if (outcome.answered && !outcome.successful) {
            LogUtil.w(AppConfig.TAG, "Failed to get URL content, code=${outcome.code}")
        }
        return outcome.body
    }

    /**
     * The same GET as [getUrlContent], but it reports WHAT happened instead of only whether a body
     * came back, and it says nothing to the log: the caller decides which outcomes are news.
     */
    fun getUrlOutcome(request: UrlContentRequest): UrlContentOutcome {
        val url = request.url ?: return UrlContentOutcome(null, UrlContentOutcome.NO_RESPONSE)
        val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
        if (request.httpPort != 0 && !request.proxyUsername.isNullOrBlank() && !request.proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(request.proxyUsername, request.proxyPassword))
        }
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return UrlContentOutcome(null, response.code)
                }
                return UrlContentOutcome(response.body?.string(), response.code)
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to get URL content: ${e.message}")
        }
        return UrlContentOutcome(null, UrlContentOutcome.NO_RESPONSE)
    }

    // `getUrlContentWithUserAgent` USED TO STAND HERE: the same подписка GET as
    // [getUrlContentWithUserAgentEx] below — same headers, same embedded basic auth, same device
    // headers, same three-hop redirect loop — differing only in that it threw the response headers
    // away and returned the body alone. It lost its last caller when the fetch started reading
    // `subscription-userinfo` (which is what tells an expired подписка apart from an empty one) and
    // the операторские directives beside it, and a second copy of a redirect loop is exactly the
    // kind of thing that stays behind and then drifts: the Ex variant has since grown the HWID
    // headers and the Accept negotiation, and this one would have had to grow them too, silently,
    // to keep answering the same way. One fetch, one place.

    /**
     * Body plus selected response headers of a subscription fetch.
     * announce/supportUrl/webPageUrl are raw header values (may be `base64:`-prefixed).
     */
    data class UrlContentResult(
        val body: String,
        val subscriptionUserInfo: String?,
        val announce: String? = null,
        val supportUrl: String? = null,
        val webPageUrl: String? = null,
        val profileTitle: String? = null,
        // Operator signal that this is a managed/hidden template subscription.
        val hidden: String? = null,
    )

    /**
     * Same as [getUrlContentWithUserAgent] but also returns the `subscription-userinfo`
     * response header (used for traffic/expiry metadata), which the plain variant discards.
     */
    fun getUrlContentWithUserAgentEx(request: UrlContentRequest): UrlContentResult {
        var currentUrl = request.url
        var redirects = 0
        val maxRedirects = 3

        while (redirects++ < maxRedirects) {
            if (currentUrl == null) continue
            val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = false)
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("User-agent", resolveSubscriptionUserAgent(request.userAgent))
                .header("Accept", SUBSCRIPTION_ACCEPT)
                .header("Connection", "close")

            attachDeviceHeaders(request, requestBuilder, currentUrl)

            applyEmbeddedBasicAuthHeader(currentUrl, requestBuilder)

            if (request.httpPort != 0 && !request.proxyUsername.isNullOrBlank() && !request.proxyPassword.isNullOrBlank()) {
                requestBuilder.header("Proxy-Authorization", Credentials.basic(request.proxyUsername, request.proxyPassword))
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isRedirect -> {
                        val location = response.header("Location")
                        if (location.isNullOrEmpty()) {
                            throw IOException("Redirect location not found")
                        }
                        currentUrl = resolveLocation(currentUrl, location)
                        if (currentUrl.isNullOrEmpty()) {
                            throw IOException("Failed to resolve redirect location")
                        }
                        return@use
                    }

                    response.isSuccessful -> {
                        return UrlContentResult(
                            body = response.body?.string() ?: "",
                            subscriptionUserInfo = response.header("subscription-userinfo"),
                            announce = response.header("announce"),
                            supportUrl = response.header("support-url"),
                            webPageUrl = response.header("profile-web-page-url"),
                            profileTitle = response.header("profile-title"),
                            hidden = response.header("profile-hidden") ?: response.header("hidden"),
                        )
                    }

                    else -> {
                        throw IOException("Request failed with status code ${response.code}")
                    }
                }
            }
        }
        throw IOException("Too many redirects")
    }

    /**
     * Attaches the stable Remnawave device identity to a subscription request: the persisted
     * per-install HWID plus device OS/version/model. Remnawave keys the panel device entry off
     * [AppConfig.HEADER_HWID] (stable -> one entry per device, no slot pollution) and labels it
     * from [AppConfig.HEADER_DEVICE_MODEL] (the real model, not a User-Agent guess). All values
     * are stable across calls, so they never register new device entries.
     *
     * **AND ONLY TO THE HOST THE SUBSCRIPTION WAS ADDRESSED TO.** [getUrlContentWithUserAgentEx]
     * follows up to three redirects itself, building a fresh request per hop, and this used to be
     * re-attached to every one of them — so a `Location:` pointing anywhere at all was enough to
     * make the app hand a stranger the identifier that follows this install across every update it
     * ever makes, plus the device model and the OS version beside it. Nobody in the middle needs
     * any of that: the panel's device ledger lives on the host the request was made TO, and a
     * provider that answers from a CDN still delivers the body without it.
     *
     * [currentUrl] is therefore compared against [UrlContentRequest.url] — the address the user
     * (or the account import) actually gave us — and the headers go out only while the two name
     * the same host. See [isOriginHost] for what "the same host" means and what it deliberately
     * does not mean.
     *
     * Every value goes through [isHeaderSafe] first. The model comes from `Build.MODEL`, which is
     * whatever the OEM wrote there and is not guaranteed ASCII; unchecked, one such device would
     * throw here and fail every subscription update forever. Degrading the panel's device label is
     * the acceptable half of that trade, losing the subscription is not.
     */
    internal fun attachDeviceHeaders(
        request: UrlContentRequest,
        requestBuilder: Request.Builder,
        currentUrl: String,
    ) {
        if (!isOriginHost(request.url, currentUrl)) return
        val hwid = request.hwid?.takeIf { it.isNotBlank() && isHeaderSafe(it) } ?: return
        requestBuilder.addHeader(AppConfig.HEADER_HWID, hwid)
        requestBuilder.addHeader(AppConfig.HEADER_DEVICE_OS, "android")
        requestBuilder.addHeader(AppConfig.HEADER_VER_OS, headerSafeOr(Build.VERSION.RELEASE, ""))
        requestBuilder.addHeader(
            AppConfig.HEADER_DEVICE_MODEL,
            headerSafeOr(Utils.getDeviceName(), FALLBACK_DEVICE_MODEL)
        )
    }

    /**
     * **Is [url] still the host [originUrl] named?** The one question that decides whether the
     * device identity travels on this hop.
     *
     * THE HOST, NOT THE ADDRESS. A подписка that redirects `http` -> `https`, or `/sub/abc` ->
     * `/api/sub/abc`, is the same server answering about itself; refusing it would break working
     * subscriptions to prevent nothing. Compared case-insensitively because DNS is, so `Example.com`
     * and `example.com` are one host and not two.
     *
     * A SUBDOMAIN IS A DIFFERENT HOST. `cdn.example.com` is not `example.com` here, deliberately,
     * and this is the strict end of the choice: a sibling host under one registrable domain is
     * usually the same operator, but "usually" is the whole of the argument for it and the cost of
     * being wrong is the identifier itself. Nothing in this app relies on such a redirect — the
     * only subscription addresses it fetches are the ones the panel hands out and the ones a user
     * pasted — so strictness costs nothing that is known to exist.
     *
     * False when either address will not parse: an unreadable origin is not a host we can claim to
     * recognise, and silence is the safe answer to a question we cannot answer.
     */
    internal fun isOriginHost(originUrl: String?, url: String): Boolean {
        val origin = runCatching { URL(originUrl) }.getOrNull()?.host?.takeIf { it.isNotBlank() }
            ?: return false
        val current = runCatching { URL(url) }.getOrNull()?.host?.takeIf { it.isNotBlank() }
            ?: return false
        return origin.equals(current, ignoreCase = true)
    }

    /**
     * The `user:pass@` half of the address being fetched, turned into a `Authorization: Basic`
     * header. Taken from [rawUrl] — the address of THIS hop — and never from the original, which is
     * why a redirect has never been able to carry an embedded credential off to another host: a
     * `Location` without userinfo simply produces no header. That is the shape [attachDeviceHeaders]
     * was missing and now has.
     */
    internal fun applyEmbeddedBasicAuthHeader(rawUrl: String, requestBuilder: Request.Builder) {
        val parsed = runCatching { URL(rawUrl) }.getOrNull() ?: return
        parsed.userInfo?.let { userInfo ->
            val colon = userInfo.indexOf(':')
            val user = runCatching {
                Utils.decodeURIComponent(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            }.getOrDefault(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            val pass = runCatching {
                Utils.decodeURIComponent(if (colon >= 0) userInfo.substring(colon + 1) else "")
            }.getOrDefault(if (colon >= 0) userInfo.substring(colon + 1) else "")
            requestBuilder.header("Authorization", Credentials.basic(user, pass))
        }
    }

    /**
     * **The one client every request in this file is cut from.**
     *
     * `OkHttpClient()` is not a request object; it is a pool. Each instance owns a `Dispatcher`, a
     * `ConnectionPool`, a `RouteDatabase` — and, the expensive one, its own lazily built
     * `SSLSocketFactory` and `X509TrustManager`, which means loading and parsing the platform trust
     * store on the first HTTPS call each instance makes. [buildOkHttpClient] used to mint a fresh
     * one PER REQUEST, and `getUrlContentWithUserAgent*` mint one per REDIRECT HOP as well, so a
     * subscription behind one redirect paid for two of everything and could not reuse so much as a
     * TLS session between the two legs of its own fetch.
     *
     * `newBuilder()` hands the per-call settings (timeouts, proxy, redirects) their own client
     * while sharing all of that — which is exactly the split OkHttp is designed around. Nothing
     * about a request changes: the proxy and the timeouts are still set per call below, and this
     * root has neither.
     */
    private val sharedClient: OkHttpClient by lazy { OkHttpClient() }

    private fun buildOkHttpClient(
        timeout: Int,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        followRedirects: Boolean
    ): OkHttpClient {
        val builder = sharedClient.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)

        if (httpPort != 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, httpPort)))
            if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                            .build()
                    }
                }
            }
        }

        return builder.build()
    }

    private fun resolveLocation(baseUrl: String, raw: String): String? {
        return try {
            val locUri = URI(raw)
            val baseUri = URI(baseUrl)
            val resolved = if (locUri.isAbsolute) locUri else baseUri.resolve(locUri)
            resolved.toURL().toString()
        } catch (_: Exception) {
            try {
                URL(raw).toString()
            } catch (_: MalformedURLException) {
                try {
                    URL(URL(baseUrl), raw).toString()
                } catch (_: MalformedURLException) {
                    null
                }
            }
        }
    }

    /**
     * @param onProgress bytes written so far / total, or total `-1` when the server sent no
     *   `Content-Length`. Called on the calling thread, so a UI consumer marshals it itself. It is
     *   optional and defaults to null: a download the user is not watching should not pay for a
     *   callback per chunk, and the existing asset downloader does not want one.
     */
    fun downloadToFile(
        request: UrlContentRequest,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        val url = request.url ?: return false
        val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
        if (request.httpPort != 0 && !request.proxyUsername.isNullOrBlank() && !request.proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(request.proxyUsername, request.proxyPassword))
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Failed to download file, code=${response.code}, url=$url")
                    return false
                }
                val body = response.body ?: return false
                if (onProgress == null) {
                    body.byteStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val total = body.contentLength()
                    var written = 0L
                    onProgress(0L, total)
                    body.byteStream().use { input ->
                        targetFile.outputStream().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress(written, total)
                            }
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download file: $url", e)
            false
        }
    }
}
