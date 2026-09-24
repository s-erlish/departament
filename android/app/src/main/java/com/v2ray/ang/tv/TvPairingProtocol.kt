package com.v2ray.ang.tv

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Rendezvous protocol for the QR-based Wi-Fi subscription transfer.
 *
 * The QR shown on the TV carries ONLY non-secret rendezvous data:
 *
 *     dvpntv://v1?ip=192.168.1.42&port=48213&token=<base64url-128bit>
 *
 * The secret subscription URL never appears in the QR; it travels only over the
 * direct LAN socket in the phone -> TV POST body, gated by the one-time [token].
 */
object TvPairingProtocol {

    const val SCHEME = "dvpntv"
    const val VERSION_HOST = "v1"

    /** Path of the single accepted endpoint on the TV listener. */
    const val PAIR_PATH = "/pair"

    /** Bearer auth scheme prefix used in the Authorization header. */
    const val BEARER_PREFIX = "Bearer "

    /** One-time token lifetime. Kept short to minimise the exposure window. */
    const val TOKEN_TTL_MILLIS = 120_000L

    /** JSON body version accepted by the TV. */
    const val PAYLOAD_VERSION = 1

    private const val KEY_IP = "ip"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    private const val JSON_KEY_VERSION = "v"
    private const val JSON_KEY_URL = "url"
    private const val JSON_KEY_REMARKS = "remarks"

    private const val JSON_KEY_IMPORTED = "imported"
    private const val JSON_KEY_ERROR = "error"

    private val secureRandom by lazy { SecureRandom() }

    /**
     * Generates a fresh 128-bit one-time token, base64url encoded (no padding).
     */
    fun generateToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Builds the QR payload string encoding the TV rendezvous data.
     */
    fun buildPairUri(ip: String, port: Int, token: String): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(VERSION_HOST)
            .appendQueryParameter(KEY_IP, ip)
            .appendQueryParameter(KEY_PORT, port.toString())
            .appendQueryParameter(KEY_TOKEN, token)
            .build()
            .toString()
    }

    /**
     * Non-secret rendezvous info parsed from a scanned QR.
     */
    data class PairInfo(val ip: String, val port: Int, val token: String)

    /**
     * Returns true when [raw] looks like a TV-pairing QR (cheap prefix check).
     */
    fun isPairUri(raw: String?): Boolean {
        return raw != null && raw.startsWith("$SCHEME://")
    }

    /**
     * Parses a scanned QR string into [PairInfo], or null if it is not a valid
     * dvpntv pairing URI.
     */
    fun parsePairUri(raw: String?): PairInfo? {
        if (!isPairUri(raw)) return null
        return try {
            val uri = Uri.parse(raw)
            if (!uri.host.equals(VERSION_HOST, ignoreCase = true)) return null
            val ip = uri.getQueryParameter(KEY_IP)?.trim().orEmpty()
            val port = uri.getQueryParameter(KEY_PORT)?.trim()?.toIntOrNull() ?: return null
            val token = uri.getQueryParameter(KEY_TOKEN)?.trim().orEmpty()
            if (ip.isEmpty() || token.isEmpty() || port !in 1..65535) return null
            PairInfo(ip, port, token)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Builds the JSON body the phone POSTs to the TV.
     */
    fun buildRequestJson(url: String, remarks: String?): String {
        return JSONObject()
            .put(JSON_KEY_VERSION, PAYLOAD_VERSION)
            .put(JSON_KEY_URL, url)
            .apply { if (!remarks.isNullOrEmpty()) put(JSON_KEY_REMARKS, remarks) }
            .toString()
    }

    /**
     * Request body received on the TV.
     */
    data class PairRequest(val url: String, val remarks: String?)

    /**
     * Parses the JSON body received on the TV, or null if malformed / missing url.
     */
    fun parseRequestJson(body: String?): PairRequest? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = JSONObject(body)
            val url = json.optString(JSON_KEY_URL).trim()
            if (url.isEmpty()) return null
            val remarks = json.optString(JSON_KEY_REMARKS).trim().ifEmpty { null }
            PairRequest(url, remarks)
        } catch (e: Exception) {
            null
        }
    }

    /** Success response body, e.g. {"imported":1}. */
    fun buildSuccessJson(imported: Int): String =
        JSONObject().put(JSON_KEY_IMPORTED, imported).toString()

    /** Error response body, e.g. {"error":"duplicate"}. */
    fun buildErrorJson(error: String): String =
        JSONObject().put(JSON_KEY_ERROR, error).toString()
}
