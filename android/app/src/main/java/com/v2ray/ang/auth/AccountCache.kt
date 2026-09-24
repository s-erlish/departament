package com.v2ray.ang.auth

import android.os.SystemClock
import com.v2ray.ang.auth.dto.DeviceDto
import com.v2ray.ang.auth.dto.PaymentDto

/**
 * Process-lifetime, in-memory cache for recently fetched account data, so re-entering a screen
 * (Devices, Payments, …) renders instantly from memory instead of re-hitting the network every
 * time. Entries carry a fetch timestamp and expire after a TTL (default 1 hour); an explicit
 * pull-to-refresh / force-refresh bypasses the cache and repopulates it.
 *
 * Timestamps use [SystemClock.elapsedRealtime] (monotonic since boot) rather than wall-clock time,
 * so a user changing the device clock can't make a stale entry look fresh (or vice-versa).
 *
 * The cache is tied to the logged-in session: reads AND writes both check [AccountSession], and
 * [AccountSession.wipe] / [AccountSession.endSession] clear it at the moment the session goes.
 * A lazy evict on the next read was not enough on its own — signing straight back in as somebody
 * else performs no read while signed out — so both halves are in place.
 *
 * Designed generically ([get]/[put]) so profile, subscriptions, tariffs, … can be added later by
 * introducing a new key + typed helper without touching the core.
 */
object AccountCache {

    /** Default freshness window: 1 hour. */
    const val DEFAULT_TTL_MS: Long = 3_600_000L

    private data class Entry(val value: Any?, val timestampMs: Long)

    private val entries = HashMap<String, Entry>()

    /**
     * Stores [value] under [key], stamping it with the current monotonic time.
     *
     * Ignored while signed out. A request started before a sign-out can land after it — the
     * payment poll re-asks every 8s for ~48 — and seeding the cache from that late reply would
     * hand the previous account's data to whoever signs in next, since the payments entry is
     * keyed globally rather than per user.
     */
    @Synchronized
    fun put(key: String, value: Any?) {
        if (!AccountSession.isLoggedIn()) {
            entries.clear()
            return
        }
        entries[key] = Entry(value, SystemClock.elapsedRealtime())
    }

    /**
     * Returns the cached value for [key] if the user is still logged in and the entry is younger
     * than [ttlMs]; otherwise null (and evicts the stale/orphaned entry). Logging out clears the
     * whole cache on the next read.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String, ttlMs: Long = DEFAULT_TTL_MS): T? {
        if (!AccountSession.isLoggedIn()) {
            entries.clear()
            return null
        }
        val entry = entries[key] ?: return null
        val ageMs = SystemClock.elapsedRealtime() - entry.timestampMs
        if (ageMs < 0 || ageMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value as? T
    }

    /** Drops a single cached entry (e.g. after a mutation invalidates it). */
    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** Clears everything. Called on logout paths, or eagerly by callers that need a hard reset. */
    @Synchronized
    fun invalidateAll() {
        entries.clear()
    }

    // region typed helpers

    private const val KEY_PAYMENTS = "payments"

    private fun devicesKey(remnawaveUuid: String) = "devices:$remnawaveUuid"

    /** Cached device list for a subscription UUID, or null if missing/stale/logged-out. */
    fun getDevices(remnawaveUuid: String, ttlMs: Long = DEFAULT_TTL_MS): List<DeviceDto>? =
        get(devicesKey(remnawaveUuid), ttlMs)

    fun putDevices(remnawaveUuid: String, devices: List<DeviceDto>) =
        put(devicesKey(remnawaveUuid), devices)

    fun invalidateDevices(remnawaveUuid: String) = invalidate(devicesKey(remnawaveUuid))

    /** Cached payments list, or null if missing/stale/logged-out. */
    fun getPayments(ttlMs: Long = DEFAULT_TTL_MS): List<PaymentDto>? = get(KEY_PAYMENTS, ttlMs)

    fun putPayments(payments: List<PaymentDto>) = put(KEY_PAYMENTS, payments)

    // endregion
}
