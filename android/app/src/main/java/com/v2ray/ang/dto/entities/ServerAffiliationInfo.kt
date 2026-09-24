package com.v2ray.ang.dto.entities

/**
 * The last latency measurement stored for one server.
 *
 * [testDelayMillis] carries three different facts, and telling them apart is the whole point:
 * a positive number is a measurement, [NOT_MEASURED] means nobody has measured this server yet,
 * and anything negative means a measurement was attempted and failed. "Failed" and "never taken"
 * are not the same thing and must never render the same way (00-rules.md 15) — which is why the
 * negative case has no number to print: -1 is a marker, not a latency, and printing it as one
 * ("-1ms") both breaks the number form of 00-rules.md 9.2 and states a duration nobody measured.
 *
 * "Measurement in flight" is deliberately NOT one of these values. It is transient UI state that
 * belongs to the screen doing the measuring (see `MainViewModel.isMeasuring`), not to the stored
 * result: a display sentinel written here would outlive the run that wrote it and would be read as
 * a failure by everything that treats a negative delay as "unreachable" — including
 * «Удалить недоступные», which deletes servers.
 */
data class ServerAffiliationInfo(var testDelayMillis: Long = 0L) {

    /** What the stored number actually says about the server. */
    enum class PingResult {
        /** No check has been run against this server since it was added or last cleared. */
        NOT_MEASURED,

        /** A real latency: [testDelayMillis] is a duration this server answered in. */
        MEASURED,

        /** A check ran and this server did not answer it. There is no duration to show. */
        FAILED,
    }

    val pingResult: PingResult
        get() = when {
            testDelayMillis > 0L -> PingResult.MEASURED
            testDelayMillis == NOT_MEASURED -> PingResult.NOT_MEASURED
            else -> PingResult.FAILED
        }

    /**
     * The latency as it is written in the interface: `48 мс`, `1 234 мс` (00-rules.md 9.2 — thin
     * space as the thousands separator, non-breaking space before the unit).
     *
     * Empty for both states that have no duration: a failure and a server never measured. The
     * caller renders those two differently, and it must, but neither of them owns a number.
     */
    fun getTestDelayString(): String =
        if (pingResult == PingResult.MEASURED) formatMillis(testDelayMillis) else ""

    private fun formatMillis(ms: Long): String {
        val digits = ms.toString()
        val out = StringBuilder(digits.length + 4)
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(THIN_SPACE)
            out.append(c)
        }
        return out.append(NBSP).append(UNIT_MS).toString()
    }

    companion object {
        /** Nothing has been measured yet: the list leaves the cell blank. */
        const val NOT_MEASURED = 0L

        /** A check ran and the server did not answer. */
        const val FAILED = -1L

        /** U+2009, the thousands separator 00-rules.md 9.2 asks for. Escaped rather than
         *  pasted: an invisible literal is what a later editor silently normalises away. */
        private const val THIN_SPACE = '\u2009'

        /** U+00A0, so a latency and its unit can never be split across a line break. */
        private const val NBSP = '\u00A0'

        // Hardcoded like the "ms" it replaces: this DTO has no Context. The unit belongs in
        // res/values/strings.xml (see the ping_result_* strings requested in the copy pass).
        private const val UNIT_MS = "мс"
    }
}
