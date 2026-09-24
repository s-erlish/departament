package com.v2ray.ang.account

import com.v2ray.ang.auth.dto.PaymentOutcome
import com.v2ray.ang.auth.dto.paymentOutcomeOf
import com.v2ray.ang.util.SubscriptionUserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two verdicts the account track now hangs behaviour on, both of which used to be nobody's
 * decision:
 *
 *  - whether a subscription response is a SERVER LIST or the panel's «истекла» notice, which is
 *    what stands between an expired подписка and every сервер on the device being replaced by one
 *    fake location (see `AngConfigManager.updateConfigViaSub`);
 *  - whether a balance payment actually SETTLED, which is what stands between the backend saying
 *    "declined" and the app saying «Подписка оплачена» (see `AccountViewModel.payWithBalance`).
 *
 * Both are pure functions on purpose — the account layer itself is `object`s over MMKV and cannot
 * be reached from a JVM test — so these are the parts that can be pinned, and they are the parts
 * where a wrong answer costs the user something.
 */
class SubscriptionExpiryAndPaymentStatusTest {

    private companion object {
        /** 2026-09-01T00:00:00Z, a fixed "now" so the assertions do not drift with the clock. */
        const val NOW = 1_788_220_800L
    }

    private fun info(header: String?): SubscriptionUserInfo? = SubscriptionUserInfo.parse(header)

    // region the expiry verdict

    @Test
    fun `a term already behind us is expired`() {
        val parsed = info("upload=1; download=2; total=3; expire=${NOW - 1}")!!
        assertTrue(parsed.isExpired(NOW))
    }

    @Test
    fun `a term still ahead is not expired`() {
        val parsed = info("expire=${NOW + 1}")!!
        assertFalse(parsed.isExpired(NOW))
    }

    @Test
    fun `the exact instant of expiry is not yet over`() {
        assertFalse(info("expire=$NOW")!!.isExpired(NOW))
    }

    @Test
    fun `zero means no expiry, not an expiry in 1970`() {
        assertFalse(info("expire=0")!!.isExpired(NOW))
        // The header may omit the key entirely; the default is the same statement.
        assertFalse(info("upload=1; download=2")!!.isExpired(NOW))
    }

    @Test
    fun `a sentinel date far in the future is 'never', however it is written`() {
        val perpetual = SubscriptionUserInfo.UNLIMITED_EXPIRE_SECONDS
        assertFalse(info("expire=$perpetual")!!.isExpired(NOW))
        assertFalse(info("expire=${perpetual + 86_400}")!!.isExpired(NOW))
        // One second under the sentinel is still a real date, and it is in the future here.
        assertFalse(info("expire=${perpetual - 1}")!!.isExpired(NOW))
    }

    @Test
    fun `no header at all is no verdict, so the body is treated as a server list`() {
        assertNull(info(null))
        assertNull(info(""))
        assertNull(info("nonsense-without-any-pairs"))
    }

    // endregion

    // region the settlement verdict

    @Test
    fun `every spelling the backend uses for a settled payment`() {
        listOf("paid", "success", "succeeded", "completed", "confirmed").forEach {
            assertEquals(it, PaymentOutcome.SETTLED, paymentOutcomeOf(it))
        }
    }

    @Test
    fun `a payment still being settled is never reported as paid`() {
        listOf("pending", "processing", "new", "created", "waiting", "in_progress").forEach {
            assertEquals(it, PaymentOutcome.PENDING, paymentOutcomeOf(it))
        }
    }

    @Test
    fun `a refusal is a refusal`() {
        listOf("failed", "error", "declined", "rejected").forEach {
            assertEquals(it, PaymentOutcome.FAILED, paymentOutcomeOf(it))
        }
        listOf("canceled", "cancelled", "expired").forEach {
            assertEquals(it, PaymentOutcome.CANCELED, paymentOutcomeOf(it))
        }
    }

    @Test
    fun `case and surrounding space do not change the verdict`() {
        assertEquals(PaymentOutcome.SETTLED, paymentOutcomeOf("  PAID "))
        assertEquals(PaymentOutcome.FAILED, paymentOutcomeOf("Declined"))
    }

    @Test
    fun `an unrecognised or absent status is UNKNOWN, and the caller decides`() {
        assertEquals(PaymentOutcome.UNKNOWN, paymentOutcomeOf(""))
        assertEquals(PaymentOutcome.UNKNOWN, paymentOutcomeOf("held_for_review"))
    }

    // endregion
}
