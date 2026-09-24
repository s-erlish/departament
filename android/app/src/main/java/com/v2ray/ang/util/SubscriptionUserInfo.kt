package com.v2ray.ang.util

/**
 * Parsed representation of the `subscription-userinfo` HTTP response header returned by
 * Remnawave / 3x-ui / Marzban / Happ / Hiddify panels, e.g.:
 *
 *   subscription-userinfo: upload=4520000000; download=210000000000; total=536870912000; expire=1749954800
 *
 * All keys are optional. `total`/`upload`/`download` are bytes, `expire` is epoch seconds.
 */
data class SubscriptionUserInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
) {

    /**
     * **The подписка's term has run out, according to the подписка itself.**
     *
     * This is the one authoritative statement about expiry that arrives WITH the config body, on
     * the same response, from the panel that serves both — which is what makes it usable as a gate
     * on the body. When the term is over, the panel does not send a server list: it answers with a
     * notice, one entry pointing at a host that exists to say «подписка истекла». Read as a config
     * list, that notice replaces every real сервер of the подписка with a single fake location the
     * app will then offer, select and try to connect through. @see
     * com.v2ray.ang.handler.AngConfigManager.updateConfigViaSub
     *
     * `expire == 0` is «no expiry», the header's own convention for a perpetual plan. A value far
     * in the future is the SAME statement written differently — several panels send a date around
     * 2088 instead of 0 — and it is excluded for the same reason: a plan that never ends cannot
     * have ended. Only a real date already behind us counts.
     */
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expire in 1 until UNLIMITED_EXPIRE_SECONDS && expire < nowSeconds

    companion object {

        /** ~2088-01-01 in epoch seconds. Some panels send a date this far out to mean "never". */
        const val UNLIMITED_EXPIRE_SECONDS = 3_723_840_000L

        /**
         * Parses the raw header value. Returns null if nothing usable was found.
         */
        fun parse(raw: String?): SubscriptionUserInfo? {
            if (raw.isNullOrBlank()) return null
            val map = raw.split(';')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) return@mapNotNull null
                    val k = part.substring(0, i).trim().lowercase()
                    val v = part.substring(i + 1).trim().toLongOrNull() ?: return@mapNotNull null
                    k to v
                }.toMap()
            if (map.isEmpty()) return null
            return SubscriptionUserInfo(
                upload = map["upload"] ?: 0,
                download = map["download"] ?: 0,
                total = map["total"] ?: 0,
                expire = map["expire"] ?: 0,
            )
        }
    }
}
