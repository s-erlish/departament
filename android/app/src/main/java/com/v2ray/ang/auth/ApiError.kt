package com.v2ray.ang.auth

import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * Sealed hierarchy of failures the API layer can surface. Callers pattern-match on these
 * and never see raw exceptions. Messages must never contain tokens or subscription URLs.
 */
sealed class ApiError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** Backend not configured (blank base URL) — login should not be offered. */
    object NotConfigured : ApiError("Backend not configured")

    /** Network/IO failure (no connectivity, DNS, TLS). */
    class Network(cause: Throwable? = null) : ApiError("Network error", cause)

    /** A request (or the login flow) exceeded its time budget. */
    object Timeout : ApiError("Request timed out")

    /**
     * 401/403 — session invalid or expired. [detail] carries a sanitized snippet of the response
     * body (payment diagnostics), never a token/URL. Compare with `is ApiError.Unauthorized`.
     */
    data class Unauthorized(val detail: String? = null) : ApiError("Unauthorized")

    /** 404 — resource not found (also the "keep polling" signal for telegram-login-check). */
    object NotFound : ApiError("Not found")

    /** 410 — resource gone / login token expired. */
    object Gone : ApiError("Gone")

    /**
     * 429 — too many requests. [detail] carries a sanitized snippet of the response body, exactly
     * as [Unauthorized] and [Server] already do, so a backend that explains the limit in its own
     * words can be quoted instead of paraphrased. See [serverMessage].
     */
    data class RateLimited(val detail: String? = null) : ApiError("Rate limited")

    /** 502/503 — backend temporarily unavailable. [detail] as in [RateLimited]. */
    data class ServiceUnavailable(val detail: String? = null) : ApiError("Service unavailable")

    /**
     * Any other unexpected non-2xx status. [detail] carries a sanitized snippet of the response
     * body (payment diagnostics), never a token/URL.
     */
    class Server(val code: Int, val detail: String? = null) : ApiError("Server error ($code)")

    /** Response body could not be parsed into the expected shape. */
    class Parse(cause: Throwable? = null) : ApiError("Failed to parse response", cause)
}

/**
 * The backend's OWN sentence for this failure, when it sent one, or null.
 *
 * Every `/client/auth` endpoint answers a refusal with `{"message": "<по-русски>"}` — «Этот email
 * уже зарегистрирован», «Некорректные данные», «Регистрация по email не настроена» — and that
 * sentence is more specific than anything this app can infer from a status code alone: 400 covers
 * both "the address is taken" and "the password is too short", and only the panel knows which.
 *
 * The value that reaches here is the ALREADY SANITIZED body snippet
 * ([DepartamentApiClientImpl.sanitizeBody] drops any line naming a token, an authorization header
 * or a URL and caps the rest), so what is quoted can never be a credential. What is left is still
 * a machine's payload, so it is admitted only when it parses AND reads like a sentence: blank,
 * over-long, or still wearing braces/markup, and the caller falls back to the app's own copy.
 *
 * Deliberately NOT applied to every failure in the product. It is for the surfaces where the panel
 * is the only party that knows what went wrong — registration today; a login's 401 keeps
 * «Неверная почта или пароль. Проверьте и повторите.», which names the fix and is in this app's
 * voice.
 */
fun ApiError.serverMessage(): String? {
    val raw = when (this) {
        is ApiError.Server -> detail
        is ApiError.Unauthorized -> detail
        is ApiError.RateLimited -> detail
        is ApiError.ServiceUnavailable -> detail
        else -> null
    } ?: return null

    val parsed = try {
        ApiGson.instance.fromJson(raw, ServerMessageDto::class.java)?.message
    } catch (e: JsonSyntaxException) {
        null
    } ?: return null

    val text = parsed.trim()
    val readable = text.isNotEmpty() &&
        text.length <= MAX_SERVER_MESSAGE_CHARS &&
        text.none { it == '{' || it == '<' } &&
        text.any { it.isLetter() }
    return text.takeIf { readable }
}

/**
 * **The panel's MACHINE-READABLE name for this failure, when it sent one, or null.**
 *
 * A companion to [serverMessage] and not a replacement for it: the sentence is what the user reads,
 * this is what the screen decides with. It exists because one endpoint answers two failures that a
 * status code cannot separate and that belong in two different places on screen —
 * `POST /client/profile/change-email/request` returns 400 `PASSWORD_REQUIRED` when the account has
 * a password and the request carried none, and 401 `INVALID_PASSWORD` when it carried the wrong
 * one. Both are about the password FIELD, and the second one is emphatically not «сессия истекла»,
 * which is what a bare 401 on a signed-in errand otherwise means.
 *
 * Read from the same already-sanitized body [serverMessage] uses, and admitted only when it looks
 * like an identifier rather than prose: a code is `A-Z0-9_`, so anything else is a payload that
 * happened to land in a field of that name and is refused rather than compared against.
 */
fun ApiError.serverCode(): String? {
    val raw = when (this) {
        is ApiError.Server -> detail
        is ApiError.Unauthorized -> detail
        is ApiError.RateLimited -> detail
        is ApiError.ServiceUnavailable -> detail
        else -> null
    } ?: return null

    val parsed = try {
        ApiGson.instance.fromJson(raw, ServerMessageDto::class.java)?.code
    } catch (e: JsonSyntaxException) {
        null
    } ?: return null

    val text = parsed.trim()
    val identifier = text.isNotEmpty() &&
        text.length <= MAX_SERVER_CODE_CHARS &&
        text.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    return text.takeIf { identifier }
}

/**
 * The `{"message": …}` envelope every auth refusal arrives in. `error` is the older spelling, and
 * `code` is the machine name a few of them add beside the sentence — see [serverCode].
 */
private data class ServerMessageDto(
    @SerializedName(value = "message", alternate = ["error"])
    val message: String? = null,
    val code: String? = null,
)

/** A sentence, not a stack trace: anything longer is a payload that leaked into the copy. */
private const val MAX_SERVER_MESSAGE_CHARS = 200

/** A name, not a payload. The longest the panel actually sends is `PASSWORD_REQUIRED` at 17. */
private const val MAX_SERVER_CODE_CHARS = 64

/** 400 on change-email: the account has a password and the request did not carry it. */
const val CODE_PASSWORD_REQUIRED = "PASSWORD_REQUIRED"

/** 401 on change-email: the current password was wrong. NOT a dead session. */
const val CODE_INVALID_PASSWORD = "INVALID_PASSWORD"
