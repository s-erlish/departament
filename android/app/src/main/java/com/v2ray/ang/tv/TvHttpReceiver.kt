package com.v2ray.ang.tv

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny, single-purpose HTTP listener that runs on the TV while the
 * "Receive subscription" screen is open.
 *
 * Design constraints (see docs/smart-tv-transfer-design.md):
 * - Binds to 0.0.0.0 on an ephemeral port so the phone can reach it over the LAN.
 * - Accepts exactly ONE token-authorized `POST /pair`, then stops.
 * - Enforces a short TTL; after expiry the socket is closed and the token invalid.
 * - Rejects non-LAN peers and locks out after a few bad-token attempts.
 *
 * It intentionally avoids any external HTTP framework: a raw ServerSocket loop is
 * enough for one request and keeps the change self-contained.
 */
class TvHttpReceiver(
    private val token: String,
    private val ttlMillis: Long,
    /**
     * Invoked on the listener's background thread with a validated payload.
     * Must perform the import synchronously and return the outcome, which is
     * translated into the HTTP response.
     */
    private val onImport: (TvPairingProtocol.PairRequest) -> Outcome
) {

    enum class Result { SUCCESS, DUPLICATE, INVALID_URL, ERROR }

    data class Outcome(val result: Result, val importedCount: Int = 0)

    private val running = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private var badAttempts = 0
    private var deadline = 0L

    /** The bound ephemeral port. Valid only after [start] returns true. */
    var port: Int = 0
        private set

    /**
     * Binds the listener and starts the accept loop on a daemon thread.
     * @return true on success; false if binding failed.
     */
    fun start(): Boolean {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            // bindAddr = null -> wildcard 0.0.0.0; port 0 -> ephemeral.
            ss.bind(InetSocketAddress(0))
            ss.soTimeout = 1000
            serverSocket = ss
            port = ss.localPort
            deadline = System.currentTimeMillis() + ttlMillis
            running.set(true)
            thread = Thread({ acceptLoop() }, "TvHttpReceiver").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvHttpReceiver: failed to start listener", e)
            stop()
            false
        }
    }

    /** Stops the listener and releases the socket. Safe to call multiple times. */
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
    }

    /** True once the single allowed import has been consumed. */
    fun isConsumed(): Boolean = consumed.get()

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get() && !consumed.get()) {
            if (System.currentTimeMillis() > deadline) {
                LogUtil.i(AppConfig.TAG, "TvHttpReceiver: token TTL expired, closing listener")
                break
            }
            val client = try {
                ss.accept()
            } catch (e: SocketTimeoutException) {
                continue // loop back to re-check TTL / running
            } catch (e: Exception) {
                if (running.get()) {
                    LogUtil.e(AppConfig.TAG, "TvHttpReceiver: accept failed", e)
                }
                break
            }
            try {
                handleClient(client)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "TvHttpReceiver: error handling client", e)
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        stop()
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream()
        val output = client.getOutputStream()

        // LAN-only guard: never service an off-LAN peer.
        if (!TvNetworkUtils.isLanAddress(client.inetAddress)) {
            writeResponse(output, "403 Forbidden", TvPairingProtocol.buildErrorJson("forbidden"))
            return
        }

        // TTL guard at request time.
        if (System.currentTimeMillis() > deadline) {
            writeResponse(output, "410 Gone", TvPairingProtocol.buildErrorJson("expired"))
            running.set(false)
            return
        }

        val requestLine = readLine(input)
        if (requestLine.isNullOrBlank()) {
            writeResponse(output, "400 Bad Request", TvPairingProtocol.buildErrorJson("bad_request"))
            return
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            writeResponse(output, "400 Bad Request", TvPairingProtocol.buildErrorJson("bad_request"))
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        // Read headers.
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }

        if (method != "POST" || path != TvPairingProtocol.PAIR_PATH) {
            writeResponse(output, "404 Not Found", TvPairingProtocol.buildErrorJson("not_found"))
            return
        }

        // Token check (constant time).
        val auth = headers["authorization"].orEmpty()
        val presented = if (auth.startsWith(TvPairingProtocol.BEARER_PREFIX)) {
            auth.substring(TvPairingProtocol.BEARER_PREFIX.length).trim()
        } else {
            ""
        }
        if (!constantTimeEquals(presented, token)) {
            badAttempts++
            LogUtil.e(AppConfig.TAG, "TvHttpReceiver: bad token attempt ($badAttempts)")
            writeResponse(output, "401 Unauthorized", TvPairingProtocol.buildErrorJson("unauthorized"))
            if (badAttempts >= MAX_BAD_ATTEMPTS) {
                LogUtil.e(AppConfig.TAG, "TvHttpReceiver: too many bad attempts, locking out")
                running.set(false)
            }
            return
        }

        // Read body.
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
            writeResponse(output, "400 Bad Request", TvPairingProtocol.buildErrorJson("bad_request"))
            return
        }
        val bodyBytes = readBody(input, contentLength)
        val request = TvPairingProtocol.parseRequestJson(String(bodyBytes, Charsets.UTF_8))
        if (request == null) {
            writeResponse(output, "400 Bad Request", TvPairingProtocol.buildErrorJson("bad_request"))
            return
        }

        // Mark consumed BEFORE the (potentially slow) import so a second concurrent
        // request cannot also be serviced.
        consumed.set(true)

        val outcome = try {
            onImport(request)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvHttpReceiver: import failed", e)
            Outcome(Result.ERROR)
        }

        when (outcome.result) {
            Result.SUCCESS -> writeResponse(
                output,
                "200 OK",
                TvPairingProtocol.buildSuccessJson(outcome.importedCount)
            )

            Result.DUPLICATE -> writeResponse(
                output,
                "409 Conflict",
                TvPairingProtocol.buildErrorJson("duplicate")
            )

            Result.INVALID_URL -> writeResponse(
                output,
                "400 Bad Request",
                TvPairingProtocol.buildErrorJson("invalid_url")
            )

            Result.ERROR -> writeResponse(
                output,
                "500 Internal Server Error",
                TvPairingProtocol.buildErrorJson("import_failed")
            )
        }
        running.set(false)
    }

    /**
     * Reads a single CRLF- (or LF-) terminated line as UTF-8, without consuming
     * beyond the terminator. Returns null at end of stream with no data.
     */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) buffer.toString("UTF-8") else null
            }
            sawAny = true
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                var len = bytes.size
                if (len > 0 && bytes[len - 1] == '\r'.code.toByte()) len--
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) {
                // Defensive: abort absurdly long header lines.
                return buffer.toString("UTF-8")
            }
        }
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val r = input.read(body, read, length - read)
            if (r == -1) break
            read += r
        }
        return if (read == length) body else body.copyOf(read)
    }

    private fun writeResponse(output: OutputStream, status: String, json: String) {
        try {
            val bodyBytes = json.toByteArray(Charsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(bodyBytes)
            output.flush()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvHttpReceiver: failed to write response", e)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        private const val MAX_BAD_ATTEMPTS = 5
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
    }
}
