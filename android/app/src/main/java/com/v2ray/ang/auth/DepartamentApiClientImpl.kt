package com.v2ray.ang.auth

import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.v2ray.ang.AppConfig
import com.v2ray.ang.auth.dto.AddDevicesRequestDto
import com.v2ray.ang.auth.dto.AuthResult
import com.v2ray.ang.auth.dto.AutoRenewRequestDto
import com.v2ray.ang.auth.dto.ChangeEmailRequestDto
import com.v2ray.ang.auth.dto.DeleteDeviceRequestDto
import com.v2ray.ang.auth.dto.DevicesDto
import com.v2ray.ang.auth.dto.DevicesResult
import com.v2ray.ang.auth.dto.GoogleLoginRequestDto
import com.v2ray.ang.auth.dto.LinkEmailRequestDto
import com.v2ray.ang.auth.dto.LoginRequestDto
import com.v2ray.ang.auth.dto.LoginResponseDto
import com.v2ray.ang.auth.dto.LoginResult
import com.v2ray.ang.auth.dto.PaymentInitDto
import com.v2ray.ang.auth.dto.PaymentRequestDto
import com.v2ray.ang.auth.dto.PaymentResultDto
import com.v2ray.ang.auth.dto.PasswordResetRequestDto
import com.v2ray.ang.auth.dto.PaymentsDto
import com.v2ray.ang.auth.dto.PromoDto
import com.v2ray.ang.auth.dto.PromoRequestDto
import com.v2ray.ang.auth.dto.PublicConfigDto
import com.v2ray.ang.auth.dto.ReferralStatsDto
import com.v2ray.ang.auth.dto.RegisterRequestDto
import com.v2ray.ang.auth.dto.RegisterResponseDto
import com.v2ray.ang.auth.dto.RegisterResult
import com.v2ray.ang.auth.dto.RenameRequestDto
import com.v2ray.ang.auth.dto.ServerStatusDto
import com.v2ray.ang.auth.dto.SetPasswordRequestDto
import com.v2ray.ang.auth.dto.PrimarySubscriptionDto
import com.v2ray.ang.auth.dto.SubscriptionAllDto
import com.v2ray.ang.auth.dto.TariffCatalogDto
import com.v2ray.ang.auth.dto.TelegramCheckResponseDto
import com.v2ray.ang.auth.dto.TelegramCheckResult
import com.v2ray.ang.auth.dto.TelegramTokenDto
import com.v2ray.ang.auth.dto.TwoFaLoginRequestDto
import com.v2ray.ang.auth.dto.UpgradeQuoteDto
import com.v2ray.ang.auth.dto.UpgradeRequestDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.lang.reflect.Type
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * OkHttp + Gson implementation of [DepartamentApiClient].
 *
 * A single request interceptor attaches Accept, User-Agent, the Bearer JWT (from
 * [AuthTokenStore]) and an optional X-HWID header — every value of them through
 * [HttpUtil.isHeaderSafe] first, because OkHttp throws while building the request on anything
 * else. Every failure is mapped to an [ApiError];
 * tokens and subscription URLs are never logged. All calls throw [ApiError.NotConfigured]
 * when the backend base URL is blank, so the whole layer is inert until configured.
 */
class DepartamentApiClientImpl(
    // Null-tolerant Gson: the backend may send JSON null for non-null Kotlin String fields
    // (e.g. a Telegram-only user has no email). ApiGson maps those to "" to avoid runtime NPEs.
    private val gson: Gson = ApiGson.instance,
    private val http: OkHttpClient = defaultClient(),
) : DepartamentApiClient {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Every value here goes through [HttpUtil.isHeaderSafe] before OkHttp sees it, because
         * OkHttp throws while BUILDING the request on a control or non-ASCII character — from
         * inside this interceptor, where nothing maps it to an [ApiError]. `Build.MODEL` and
         * `Build.VERSION.RELEASE` are whatever the OEM wrote and are not guaranteed ASCII, so
         * unchecked they would take down EVERY backend call on such a device — login included —
         * not just the one header they appear in. Degrading the panel's device label is the
         * acceptable half of that trade; losing the account is not. Same guard, same reasons, as
         * [HttpUtil.attachDeviceHeaders] on the subscription path.
         */
        private val authInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
                // Already header-safe by construction (BackendConfig refuses a value that is not).
                .header("User-Agent", BackendConfig.subscriptionUserAgent)
            AuthTokenStore.getToken()
                ?.takeIf { it.isNotBlank() && HttpUtil.isHeaderSafe(it) }
                ?.let { builder.header("Authorization", "Bearer $it") }
            //  ТА ЖЕ ДЫРА, ЧТО НА ПУТИ ПОДПИСКИ, ТОЛЬКО ДРУГАЯ ДВЕРЬ. Это application-интерцептор:
            //  на редиректе OkHttp зовёт его заново для нового адреса. Свой Authorization он с
            //  межхостового прыжка снимает сам, а НАШИ заголовки — нет, и опознаватель устройства
            //  уехал бы на чужой хост. Панель — наш хост, редирект там был бы настройкой оператора,
            //  но полагаться на это нельзя: правило одно и то же на обоих путях — опознаватель
            //  видит только тот хост, которому запрос адресован.
            val sameHost = HttpUtil.isOriginHost(BackendConfig.baseUrl, chain.request().url.toString())
            val hwid = AuthTokenStore.deviceId().takeIf { HttpUtil.isHeaderSafe(it) }
            if (sameHost && SettingsManager.isSendHwid() && hwid != null) {
                // Stable per-install HWID + real, stable device model so the panel keeps ONE
                // device entry per physical device and labels it with the actual model.
                builder.header(AppConfig.HEADER_HWID, hwid)
                builder.header(AppConfig.HEADER_DEVICE_OS, "android")
                builder.header(AppConfig.HEADER_VER_OS, HttpUtil.headerSafeOr(Build.VERSION.RELEASE, ""))
                builder.header(
                    AppConfig.HEADER_DEVICE_MODEL,
                    HttpUtil.headerSafeOr(Utils.getDeviceName(), HttpUtil.FALLBACK_DEVICE_MODEL)
                )
            }
            chain.proceed(builder.build())
        }

        /**
         * ONE client for the whole app, and it has to be one.
         *
         * `defaultClient()` was the default value of a constructor parameter, so every
         * `DepartamentApiClientImpl()` built a fresh [OkHttpClient] — and one is built per
         * [AccountRepository], which is itself constructed per ViewModel and ad hoc at several call
         * sites (`AccountRepository().autoImportSubscriptions()` runs on every sign-in and on every
         * «Загрузить серверы»). Each client carries its own connection pool, its own dispatcher
         * thread pool and its own idle-cleanup thread, none of which are shut down when the caller
         * goes; nothing is shared either, so every account screen paid for a fresh TCP+TLS
         * handshake against a host the app already had a warm connection to.
         *
         * `by lazy` because building it touches OkHttp's own initialisation, and this object is
         * loaded from the interceptor path on a background thread.
         */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
                .build()
        }

        private fun defaultClient(): OkHttpClient = sharedClient
    }

    // region public

    override suspend fun getPublicConfig(): PublicConfigDto =
        getJson(BackendConfig.Endpoints.publicConfig, PublicConfigDto::class.java)

    override suspend fun getPublicTariffs(): TariffCatalogDto =
        getJson(BackendConfig.Endpoints.publicTariffs, TariffCatalogDto::class.java)

    override suspend fun getServerStatus(): List<ServerStatusDto> {
        val type = object : com.google.gson.reflect.TypeToken<List<ServerStatusDto>>() {}.type
        return getJsonType(BackendConfig.Endpoints.serverStatus, type)
    }

    // endregion

    // region auth

    override suspend fun createTelegramLoginToken(): TelegramTokenDto =
        postJson(BackendConfig.Endpoints.telegramLoginToken, "{}", TelegramTokenDto::class.java)

    override suspend fun checkTelegramLogin(token: String): TelegramCheckResult {
        ensureConfigured()
        val url = urlOf(BackendConfig.Endpoints.telegramLoginCheck)
            .addQueryParameter("token", token)
            .build()
        return exchange(Request.Builder().url(url).get().build()) { resp ->
            when (resp.code) {
                404 -> TelegramCheckResult.NotYet
                410 -> TelegramCheckResult.Expired
                in 200..299 -> {
                    val raw = parse(resp.body.string(), TelegramCheckResponseDto::class.java)
                    val jwt = raw.token
                    val client = raw.client
                    if (raw.confirmed && !jwt.isNullOrBlank() && client != null) {
                        TelegramCheckResult.Confirmed(jwt, client, raw.justCreated)
                    } else {
                        TelegramCheckResult.NotYet
                    }
                }
                else -> throw mapError(resp.code)
            }
        }
    }

    /**
     * Registration. Answers 201 in two shapes and BOTH are a success: a `{token, client}` body is a
     * session (the panel has e-mail verification switched off), and a body without a token means a
     * confirmation letter has been sent instead. Only a non-2xx is a failure, and it arrives here as
     * an [ApiError] carrying the panel's own sentence — see [serverMessage].
     */
    override suspend fun register(email: String, password: String, referralCode: String?): RegisterResult {
        val raw = postJson(
            BackendConfig.Endpoints.register,
            gson.toJson(RegisterRequestDto(email, password, referralCode)),
            RegisterResponseDto::class.java,
        )
        val token = raw.token
        val client = raw.client
        return if (!token.isNullOrBlank() && client != null) {
            RegisterResult.Success(token, client)
        } else {
            RegisterResult.RequiresVerification(raw.message)
        }
    }

    override suspend fun login(email: String, password: String): LoginResult {
        val raw = postJson(BackendConfig.Endpoints.login, gson.toJson(LoginRequestDto(email, password)), LoginResponseDto::class.java)
        val tempToken = raw.tempToken
        val token = raw.token
        val client = raw.client
        return when {
            raw.requires2FA && !tempToken.isNullOrBlank() -> LoginResult.Requires2FA(tempToken)
            !token.isNullOrBlank() && client != null -> LoginResult.Success(token, client)
            else -> throw ApiError.Parse()
        }
    }

    override suspend fun login2fa(tempToken: String, code: String): AuthResult =
        postJson(BackendConfig.Endpoints.twoFaLogin, gson.toJson(TwoFaLoginRequestDto(tempToken, code)), AuthResult::class.java)

    override suspend fun loginGoogle(idToken: String, referralCode: String?): AuthResult =
        postJson(BackendConfig.Endpoints.googleLogin, gson.toJson(GoogleLoginRequestDto(idToken, referralCode)), AuthResult::class.java)

    override suspend fun getMe(): UserProfileDto =
        getJson(BackendConfig.Endpoints.me, UserProfileDto::class.java)

    /** @see DepartamentApiClient.requestPasswordReset */
    override suspend fun requestPasswordReset(email: String) {
        postQuoting(BackendConfig.Endpoints.passwordResetRequest, PasswordResetRequestDto(email))
    }

    /** @see DepartamentApiClient.linkEmailRequest */
    override suspend fun linkEmailRequest(email: String) {
        postQuoting(BackendConfig.Endpoints.linkEmailRequest, LinkEmailRequestDto(email))
    }

    /** @see DepartamentApiClient.setPassword */
    override suspend fun setPassword(newPassword: String) {
        postQuoting(BackendConfig.Endpoints.setPassword, SetPasswordRequestDto(newPassword))
    }

    /** @see DepartamentApiClient.completeOnboarding */
    override suspend fun completeOnboarding() {
        postQuoting(BackendConfig.Endpoints.completeOnboarding)
    }

    /** @see DepartamentApiClient.changeEmailRequest */
    override suspend fun changeEmailRequest(newEmail: String, currentPassword: String?) {
        postQuoting(
            BackendConfig.Endpoints.changeEmailRequest,
            // Blank is not "absent": an empty string would fail the panel's own optional-string
            // check as a VALUE and be compared against the hash, turning "I did not send one" into
            // «Неверный пароль». Only a real null means the field was not sent.
            ChangeEmailRequestDto(newEmail, currentPassword?.takeIf { it.isNotEmpty() }),
        )
    }

    // endregion

    // region subscription

    override suspend fun getPrimarySubscription(): PrimarySubscriptionDto =
        getJson(BackendConfig.Endpoints.subscription, PrimarySubscriptionDto::class.java)

    override suspend fun getSubscriptionAll(): SubscriptionAllDto =
        getJson(BackendConfig.Endpoints.subscriptionAll, SubscriptionAllDto::class.java)

    override suspend fun renameSubscription(scope: String, id: String, name: String) {
        ensureConfigured()
        val body = gson.toJson(RenameRequestDto(name)).toRequestBody(JSON)
        val req = Request.Builder().url(urlOf(BackendConfig.Endpoints.renameSubscription(scope, id)).build()).patch(body).build()
        executeVoid(req)
    }

    override suspend fun getSubscriptionQr(remnawaveUuid: String): ByteArray {
        ensureConfigured()
        val url = urlOf(BackendConfig.Endpoints.subscriptionQr).addQueryParameter("uuid", remnawaveUuid).build()
        return exchange(Request.Builder().url(url).get().build()) { resp ->
            if (!resp.isSuccessful) throw mapError(resp.code)
            resp.body.bytes()
        }
    }

    override suspend fun addDevices(scope: String, id: String, extraDevices: Int, method: String, paymentMethod: String?): PaymentInitDto {
        val json = gson.toJson(AddDevicesRequestDto(extraDevices, method, paymentMethod))
        return postJson(BackendConfig.Endpoints.addDevices(scope, id), json, PaymentInitDto::class.java)
    }

    override suspend fun getUpgradeQuote(targetTariffId: String): UpgradeQuoteDto {
        ensureConfigured()
        val url = urlOf(BackendConfig.Endpoints.upgradeQuote).addQueryParameter("targetTariffId", targetTariffId).build()
        return call(Request.Builder().url(url).get().build(), UpgradeQuoteDto::class.java)
    }

    override suspend fun upgrade(targetTariffId: String, method: String, paymentMethod: String?, subscriptionUuid: String): PaymentInitDto {
        val json = gson.toJson(UpgradeRequestDto(targetTariffId, method, paymentMethod, subscriptionUuid))
        return postJson(BackendConfig.Endpoints.upgrade, json, PaymentInitDto::class.java)
    }

    // endregion

    // region devices

    override suspend fun getDevices(remnawaveUuid: String): DevicesResult {
        ensureConfigured()
        val url = urlOf(BackendConfig.Endpoints.devices).addQueryParameter("uuid", remnawaveUuid).build()
        return exchange(Request.Builder().url(url).get().build()) { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) throw mapError(resp.code, sanitizeBody(body))
            // Keep the raw (sanitized) body so the UI can surface a diagnostic when the parsed
            // list is empty but the subscription reports connected devices (shape mismatch).
            val devices = parse(body, DevicesDto::class.java).devices()
            DevicesResult(devices, resp.code, sanitizeBody(body).orEmpty())
        }
    }

    override suspend fun deleteDevice(hwid: String, remnawaveUuid: String) {
        ensureConfigured()
        val body = gson.toJson(DeleteDeviceRequestDto(hwid, remnawaveUuid)).toRequestBody(JSON)
        executeVoid(Request.Builder().url(urlOf(BackendConfig.Endpoints.deleteDevice).build()).post(body).build())
    }

    // endregion

    // region payments

    override suspend fun payPlatega(req: PaymentRequestDto): PaymentInitDto =
        postJson(BackendConfig.Endpoints.payPlatega, gson.toJson(req), PaymentInitDto::class.java)

    override suspend fun payBalance(req: PaymentRequestDto): PaymentResultDto =
        postJson(BackendConfig.Endpoints.payBalance, gson.toJson(req), PaymentResultDto::class.java)

    override suspend fun getPayments(): PaymentsDto =
        getJson(BackendConfig.Endpoints.payments, PaymentsDto::class.java)

    // endregion

    // region promo / trial / referral

    override suspend fun checkPromo(code: String): PromoDto =
        postJson(BackendConfig.Endpoints.promoCheck, gson.toJson(PromoRequestDto(code)), PromoDto::class.java)

    override suspend fun activatePromo(code: String) {
        ensureConfigured()
        val body = gson.toJson(PromoRequestDto(code)).toRequestBody(JSON)
        executeVoid(Request.Builder().url(urlOf(BackendConfig.Endpoints.promoActivate).build()).post(body).build())
    }

    override suspend fun activateTrial() {
        ensureConfigured()
        val body = "{}".toRequestBody(JSON)
        executeVoid(Request.Builder().url(urlOf(BackendConfig.Endpoints.trial).build()).post(body).build())
    }

    override suspend fun setSecondaryAutoRenew(id: String, autoRenew: Boolean) {
        ensureConfigured()
        val body = gson.toJson(AutoRenewRequestDto(autoRenew)).toRequestBody(JSON)
        executeVoid(Request.Builder().url(urlOf(BackendConfig.Endpoints.secondaryAutoRenew(id)).build()).patch(body).build())
    }

    override suspend fun setPrimaryAutoRenew(autoRenew: Boolean) {
        ensureConfigured()
        val body = gson.toJson(AutoRenewRequestDto(autoRenew)).toRequestBody(JSON)
        executeVoid(Request.Builder().url(urlOf(BackendConfig.Endpoints.primaryAutoRenew).build()).patch(body).build())
    }

    override suspend fun getReferralStats(): ReferralStatsDto =
        getJson(BackendConfig.Endpoints.referralStats, ReferralStatsDto::class.java)

    // endregion

    // region internals

    private fun ensureConfigured() {
        if (!BackendConfig.isConfigured()) throw ApiError.NotConfigured
    }

    private fun urlOf(path: String) = (BackendConfig.baseUrl + path).toHttpUrl().newBuilder()

    private suspend fun <T> getJson(path: String, cls: Class<T>): T {
        ensureConfigured()
        return call(Request.Builder().url(urlOf(path).build()).get().build(), cls)
    }

    private suspend fun <T> getJsonType(path: String, type: Type): T {
        ensureConfigured()
        return callType(Request.Builder().url(urlOf(path).build()).get().build(), type)
    }

    private suspend fun <T> postJson(path: String, json: String, cls: Class<T>): T {
        ensureConfigured()
        val req = Request.Builder().url(urlOf(path).build()).post(json.toRequestBody(JSON)).build()
        return call(req, cls)
    }

    /**
     * A POST whose ANSWER is «it worked», and whose FAILURE has to carry the panel's own words.
     *
     * NOT [executeVoid]: that throws `mapError(code)` with no body behind it, and every account
     * errand that uses this helper is refused with a sentence only the panel can write — «Почта уже
     * привязана», «Пароль уже установлен. Используйте смену пароля.», «Эта почта уже используется
     * другим аккаунтом» — and sometimes with a `code` beside it that decides WHERE on screen the
     * failure belongs ([serverCode]). So the refusal body is read and sanitized like every other
     * quotable one.
     *
     * NOT [postJson] either: that parses the SUCCESS body, and a 200 with an empty body would then
     * be reported as a parse failure for a letter that has already been sent. The success body is
     * read off the socket and dropped — 200 is the whole answer, and the `{message}` in it is the
     * panel's copy of a sentence this app writes itself, with the address in it.
     */
    private suspend fun postQuoting(path: String, body: Any? = null) {
        ensureConfigured()
        // A body-less POST still sends `{}`: the panel parses JSON on these routes, and an empty
        // entity is not the same thing as an empty object to it.
        val json = if (body == null) "{}" else gson.toJson(body)
        val req = Request.Builder()
            .url(urlOf(path).build())
            .post(json.toRequestBody(JSON))
            .build()
        exchange(req) { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw mapError(resp.code, sanitizeBody(text))
        }
    }

    private suspend fun <T> call(request: Request, cls: Class<T>): T = exchange(request) { resp ->
        val body = resp.body.string()
        if (!resp.isSuccessful) throw mapError(resp.code, sanitizeBody(body))
        parse(body, cls)
    }

    private suspend fun <T> callType(request: Request, type: Type): T = exchange(request) { resp ->
        val body = resp.body.string()
        if (!resp.isSuccessful) throw mapError(resp.code, sanitizeBody(body))
        parseType(body, type)
    }

    private suspend fun executeVoid(request: Request) = exchange(request) { resp ->
        if (!resp.isSuccessful) throw mapError(resp.code)
    }

    /**
     * **Одно место, где живёт весь сетевой обмен — и всё оно на [Dispatchers.IO].**
     *
     * ЧИТАТЬ ТЕЛО ОТВЕТА — ТОЖЕ СЕТЕВАЯ РАБОТА. The old `execute()` hopped to IO for
     * `newCall().execute()` and returned the `Response` to the caller — and `execute()` returns as
     * soon as the RESPONSE HEADERS are in. Everything after it — `body.string()`, which reads the
     * payload off the socket, and the Gson parse of that payload — ran back on the caller's
     * dispatcher. `AccountRepository` has no `withContext` anywhere and every ViewModel calls it
     * from `viewModelScope`, i.e. `Dispatchers.Main.immediate`, so THE BODY OF EVERY ACCOUNT
     * REQUEST WAS READ AND PARSED ON THE MAIN THREAD: `/client/profile`, `/subscription/all`,
     * `/client/subscription`, `/client/payments`, the tariff catalog, the payment poll's six
     * rounds. The block is handed the still-open response and runs on IO with it, so the read and
     * the parse land where the call already was.
     *
     * IT IS ALSO CANCELLABLE NOW, which the old shape could not be. A blocking `execute()` inside
     * `withContext` ignores cancellation: the coroutine unwinds, the socket does not, and the
     * request runs to completion on a blocked IO thread with nobody left to read the answer. Two
     * screens ask for the same account data at the same moment by design (Главная and Аккаунт both
     * refresh on resume) and the ViewModel's latest-wins cancels the older job — which until now
     * threw away the ANSWER while still paying for the REQUEST. The completion handler cancels the
     * OkHttp call itself, and [ensureActive] makes sure the resulting «Canceled» IOException
     * unwinds as a cancellation rather than being reported to the user as a network failure.
     */
    private suspend fun <T> exchange(request: Request, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val call = http.newCall(request)
            val onCancel = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use(read)
            } catch (e: SocketTimeoutException) {
                ensureActive()
                throw ApiError.Timeout
            } catch (e: IOException) {
                ensureActive()
                throw ApiError.Network(e)
            } finally {
                onCancel?.dispose()
            }
        }

    private fun mapError(code: Int, detail: String? = null): ApiError = when (code) {
        // Only 401 means "authentication failed / token expired". 403 (Forbidden) is a permission
        // outcome on an otherwise-valid session (e.g. an action not allowed for this account/plan)
        // and must NOT be treated as Unauthorized — otherwise callers would wipe a live session.
        401 -> ApiError.Unauthorized(detail)
        403 -> ApiError.Server(403, detail)
        404 -> ApiError.NotFound
        410 -> ApiError.Gone
        429 -> ApiError.RateLimited(detail)
        502, 503 -> ApiError.ServiceUnavailable(detail)
        else -> ApiError.Server(code, detail)
    }

    /**
     * Reduces an error response body to a short, screenshot-safe diagnostic snippet: drops any
     * line mentioning a token/authorization header or an http(s) URL, then caps at 300 chars.
     * Returns null when nothing usable remains.
     */
    private fun sanitizeBody(body: String): String? {
        if (body.isBlank()) return null
        val cleaned = body.lineSequence()
            .filterNot { line ->
                val l = line.lowercase()
                l.contains("token") || l.contains("authorization") ||
                    l.contains("http://") || l.contains("https://")
            }
            .joinToString("\n")
            .trim()
        if (cleaned.isBlank()) return null
        return if (cleaned.length > 300) cleaned.substring(0, 300) else cleaned
    }

    private fun <T> parse(body: String, cls: Class<T>): T {
        return try {
            gson.fromJson(body, cls) ?: throw ApiError.Parse()
        } catch (e: JsonSyntaxException) {
            throw ApiError.Parse(e)
        }
    }

    private fun <T> parseType(body: String, type: Type): T {
        return try {
            gson.fromJson<T>(body, type) ?: throw ApiError.Parse()
        } catch (e: JsonSyntaxException) {
            throw ApiError.Parse(e)
        }
    }

    // endregion
}
