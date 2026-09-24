package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

object SpeedtestManager {

    private val tcpTestingSockets = ArrayList<Socket?>()

    private const val PING = "/system/bin/ping"
    private const val PING6 = "/system/bin/ping6"

    /** How often [awaitExit] asks whether the `ping` process has exited. */
    private const val EXIT_POLL_INTERVAL_MS = 25L

    /** Grace period for the output-drain thread once the process has exited. */
    private const val DRAIN_JOIN_MS = 500L

    // One OkHttpClient shared across direct HTTP probes for connection/thread pool reuse.
    private val httpPingClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(5000, TimeUnit.MILLISECONDS)
            .callTimeout(5000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Direct HTTP(S) latency probe against a server's own `host:port` — no proxy, no tunnel.
     *
     * It measures exactly **one** quantity: time-to-first-byte of the response headers for a HEAD
     * request — DNS, TCP connect, TLS handshake, request write, first byte back. It used to fall
     * back to a bare TCP connect whenever HTTP did not answer, which put two different physical
     * quantities in one unlabelled column: a TLS-less server reported one RTT while an HTTPS one
     * reported an RTT plus a full handshake, and the list silently ranked them against each other.
     * A TCP handshake is what [tcping] measures and the picker offers it as its own method, so this
     * probe no longer borrows it. A server that does not answer HTTP here reports -1 — a failure,
     * which the list renders as a failed measurement and never as a number.
     *
     * A redirect is a failure, not a measurement. 3xx away from the address we dialled is the
     * signature of a captive portal or a middlebox answering on the server's behalf, so the time
     * measured belongs to that middlebox, not to the server it would be printed against
     * (docs/ping-methods-design.md, method B: "a redirect = captive portal = failure").
     *
     * Cancellable: cancelling the calling coroutine cancels the in-flight call, so leaving the
     * screen or starting a second measurement stops the socket work instead of only the
     * bookkeeping.
     *
     * @param url the target URL, built from the server's own address and port.
     * @return latency in ms, or -1 on failure. Never 0: 0 is the "not measured" sentinel
     *         ([com.v2ray.ang.dto.entities.ServerAffiliationInfo]) that renders as a blank cell.
     */
    suspend fun httpPing(url: String): Long = withContext(Dispatchers.IO) {
        // Request.Builder.url() parses eagerly and throws IllegalArgumentException on an
        // out-of-range port or an unparseable host. The callers build this URL from
        // profile.serverPort, which is whatever a subscription or a hand-edited profile put there,
        // so the throw is reachable with real data. It must not escape: this runs inside the
        // test scope's children, and an uncaught exception there takes the process down and
        // cancels the scope for the rest of its life, turning one bad row into "ping stops
        // working entirely". A row we cannot even address is simply unreachable: -1.
        val req = try {
            okhttp3.Request.Builder()
                .url(url)
                .head()
                .header("Connection", "close")
                .build()
        } catch (e: IllegalArgumentException) {
            LogUtil.w(AppConfig.TAG, "httpPing: unusable url $url: ${e.message}")
            return@withContext -1L
        }
        val call = httpPingClient.newCall(req)
        // execute() blocks on a socket, which no amount of coroutine cancellation reaches on its
        // own; cancelling the call is what actually closes it.
        val cancelOnDeath = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            val start = System.nanoTime()
            call.execute().use { r ->
                if (r.isRedirect) {
                    LogUtil.w(AppConfig.TAG, "httpPing: $url answered ${r.code}, a redirect: not this server")
                    -1L
                } else {
                    elapsedMs(start)
                }
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "httpPing failed: ${e.message}")
            -1L
        } finally {
            cancelOnDeath.dispose()
        }
    }

    /**
     * ICMP echo probe via the system `ping` binary (works without root on modern Android).
     * Note: many nodes/CDNs drop ICMP, so -1 here often means "filtered", not "node down".
     *
     * The address family has to be chosen explicitly: `/system/bin/ping` is IPv4-only, so an IPv6
     * literal — or a host with only AAAA records — used to fail on the command line and read as
     * "filtered" when nothing had been measured at all. IPv6 targets go to `ping6`, falling back
     * to `ping -6` on builds that ship no `ping6`, and again to the next candidate when a binary
     * answers with a usage error instead of a probe.
     *
     * Cancellable: each rung runs interruptibly, so cancelling the calling coroutine interrupts the
     * wait, and the `finally` below destroys the child process instead of leaving it running.
     *
     * @param host node host or IP; a bracketed IPv6 literal is accepted.
     * @param timeoutSec per-probe timeout in seconds.
     * @return round-trip time in ms, or -1 on failure / no reply. Never 0 (see [httpPing]).
     */
    suspend fun icmpPing(host: String, timeoutSec: Int = 2): Long {
        currentCoroutineContext().ensureActive()
        val target = normalizeIcmpTarget(pickIcmpAddress(host))
        if (target.isEmpty()) return -1L
        for (cmd in pingCommands(target, timeoutSec)) {
            currentCoroutineContext().ensureActive()
            // null = that binary could not probe at all, so try the next candidate; -1 = it ran
            // and got no reply, which is an answer (filtered or down) and ends the ladder.
            val rtt = runInterruptible(Dispatchers.IO) { runPing(cmd, timeoutSec) }
            if (rtt != null) return rtt
        }
        return -1L
    }

    /**
     * Runs one `ping` invocation. Returns ms, -1 for "ran, no reply", or null for "unusable".
     *
     * The pipe is drained on its own thread and the exit is awaited against a deadline. Reading to
     * EOF on this thread first — which is what this did — made the timeout decorative: a `ping`
     * that never exits never closes its stdout, so the read never returned, the wait after it was
     * never reached, and the `destroy()` below never ran. The thread was then lost for good.
     */
    private fun runPing(cmd: List<String>, timeoutSec: Int): Long? {
        var process: Process? = null
        val output = StringBuilder()
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = p
            val drain = Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                } catch (e: IOException) {
                    // destroy() closes the pipe under the reader: nothing left to read, and the
                    // output collected so far is still whatever the process managed to print.
                }
            }, "icmp-ping-drain").apply { isDaemon = true; start() }

            val exitCode = awaitExit(p, (timeoutSec + 2L) * 1000L)
            if (exitCode == null) {
                LogUtil.w(AppConfig.TAG, "icmpPing: ${cmd.first()} did not exit in ${timeoutSec + 2}s")
                // It ran and never answered. That is a failed measurement of this server, not an
                // unusable binary, so the ladder ends here rather than trying the next candidate.
                return -1L
            }
            drain.join(DRAIN_JOIN_MS)
            val out = synchronized(output) { output.toString() }
            when {
                exitCode == 0 -> parseIcmpRtt(out) ?: -1L
                looksLikeUsageError(out) -> null
                else -> -1L
            }
        } catch (e: InterruptedException) {
            // Cancelled. Re-assert the flag and let it out: runInterruptible turns it into the
            // CancellationException the caller is waiting for. Swallowing it here would report a
            // failed measurement for a probe that was simply abandoned.
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            LogUtil.w(AppConfig.TAG, "icmpPing: ${cmd.first()} unusable: ${e.message}")
            null
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "icmpPing failed: ${e.message}")
            -1L
        } finally {
            process?.destroy()
        }
    }

    /**
     * Waits up to [timeoutMs] for [process] and returns its exit code, or null if it is still
     * running when the deadline passes.
     *
     * Polled rather than `Process.waitFor(timeout, unit)` because that overload is API 26 and this
     * app ships to API 24. Polling with [Thread.sleep] is also what makes the wait interruptible,
     * which is how a coroutine cancellation reaches a running `ping`.
     */
    private fun awaitExit(process: Process, timeoutMs: Long): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            try {
                return process.exitValue()
            } catch (e: IllegalThreadStateException) {
                if (System.nanoTime() - deadline >= 0L) return null
                Thread.sleep(EXIT_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * IPv4 first when the host has both, because that is the family `ping` handles unaided; an
     * AAAA-only host still returns IPv6 and is then pinged as IPv6 rather than mis-read as down.
     * A literal resolves to null here (see `HttpUtil.resolveHostToIP`) and is used as given.
     */
    private fun pickIcmpAddress(host: String): String {
        val h = host.trim()
        val resolved = HttpUtil.resolveHostToIP(h)?.filter { it.isNotBlank() } ?: return h
        return resolved.firstOrNull { !isIpv6Literal(it) } ?: resolved.firstOrNull() ?: h
    }

    /** `ping` wants a bare literal: no brackets, and an IPv4-mapped address pinged as IPv4. */
    private fun normalizeIcmpTarget(address: String): String {
        var a = address.trim()
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length - 1)
        if (a.startsWith("::ffff:", ignoreCase = true) && a.contains('.')) a = a.substring(7)
        return a
    }

    /** A hostname can never contain ':', so a colon is a reliable IPv6-literal marker. */
    private fun isIpv6Literal(address: String) = address.contains(':')

    private fun pingCommands(target: String, timeoutSec: Int): List<List<String>> {
        val tail = listOf("-c", "1", "-W", timeoutSec.toString(), target)
        if (!isIpv6Literal(target)) return listOf(listOf(PING) + tail)
        val cmds = ArrayList<List<String>>(2)
        if (File(PING6).canExecute()) cmds.add(listOf(PING6) + tail)
        cmds.add(listOf(PING, "-6") + tail)
        return cmds
    }

    /** "64 bytes from …: icmp_seq=1 ttl=52 time=23.4 ms" → 23. Some builds print a comma. */
    private fun parseIcmpRtt(out: String): Long? {
        val raw = ICMP_TIME.find(out)?.groupValues?.get(1) ?: return null
        val ms = raw.replace(',', '.').toFloatOrNull() ?: return null
        return ms.roundToLong().coerceAtLeast(1L)
    }

    /** Tells "this binary/flag does not exist here" from "the node did not reply". */
    private fun looksLikeUsageError(out: String): Boolean {
        val l = out.lowercase()
        return "usage" in l || "unknown option" in l || "invalid option" in l ||
                "unrecognized option" in l || "bad option" in l || "illegal option" in l
    }

    private val ICMP_TIME = Regex("""time[=<]\s*([0-9]+(?:[.,][0-9]+)?)""")

    /** Elapsed ms, floored at 1 so a genuine sub-millisecond probe is not stored as "not tested". */
    private fun elapsedMs(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)

    /**
     * TCP connect latency to `url:port`, best of two tries, cancellable between tries.
     *
     * @return the faster handshake in ms, or -1 when neither try connected.
     */
    suspend fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (!currentCoroutineContext().isActive) {
                break
            }
            if (one != -1L && (time == -1L || one < time)) {
                time = one
            }
        }
        return time
    }

    /**
     * Measures the time taken to establish a TCP connection to a given host and port.
     *
     * Timed with [System.nanoTime]: a wall-clock delta can be shifted by an NTP correction
     * mid-handshake, which would print a latency nobody measured. The socket is registered for
     * [closeAllTcpSockets] and de-registered/closed in `finally`, so a failed probe cannot leave a
     * dead socket in that list for a later cancel to trip over.
     *
     * @param url The host to connect to.
     * @param port The port to connect to.
     * @param timeoutMs Connect timeout.
     * @return The connection time in milliseconds (never 0, see [httpPing]), or -1 on failure.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 3000): Long {
        val socket = Socket()
        synchronized(this) {
            tcpTestingSockets.add(socket)
        }
        try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(url, port), timeoutMs)
            return elapsedMs(start)
        } catch (e: UnknownHostException) {
            // A PROBE THAT FAILS IS A RESULT, NOT AN ERROR, and this is the app's noisiest log line
            // by a wide margin. «Проверить все» probes every server in the list; a server that is
            // down, filtered, or simply unreachable from the current network is the everyday
            // outcome, the return value below already SAYS so (-1), and the row already draws it.
            // Writing an ERROR with a stack trace per failed probe filled «Журнал» with red during
            // exactly the operation the user ran on purpose.
            //
            // Kept at warn, one line, no trace: a failed probe is still worth being able to see.
            LogUtil.w(AppConfig.TAG, "Ping: unknown host $url")
        } catch (e: IOException) {
            LogUtil.w(AppConfig.TAG, "Ping: $url:$port unreachable (${e.message})")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            synchronized(this) {
                tcpTestingSockets.remove(socket)
            }
            try {
                socket.close()
            } catch (e: IOException) {
                LogUtil.w(AppConfig.TAG, "socketConnectTime close failed: ${e.message}")
            }
        }
        return -1
    }

    /**
     * Closes all TCP sockets that are currently being tested.
     */
    fun closeAllTcpSockets() {
        synchronized(this) {
            tcpTestingSockets.forEach {
                it?.close()
            }
            tcpTestingSockets.clear()
        }
    }

    /**
     * The exit IP as the geo-API sees it.
     *
     * UPSTREAM'S, AND WITHOUT A CALLER HERE ON PURPOSE. Its one call site was
     * `CoreServiceManager.measureV2rayDelay`, which fired it after every successful 30-second probe
     * — an HTTP request through the local proxy, 120 an hour while a tunnel is up — and handed the
     * answer to a status line this product does not have, so it was discarded on arrival. The call
     * is gone; the function stays, because it is upstream's and a screen that wants to show the exit
     * IP would ask exactly this.
     */
    fun getRemoteIPInfo(): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return "(${country ?: "unknown"}) ${ip ?: "unknown"}"
    }
}
