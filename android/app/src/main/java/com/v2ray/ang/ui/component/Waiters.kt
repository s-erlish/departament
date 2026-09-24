package com.v2ray.ang.ui.component

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A set of coroutines suspended on a condition over state that is NOT a flow.
 *
 * `MainActivity` tracks two errands with plain main-thread counters — the shell's load balance and
 * the account's подписка import — because both are incremented and decremented from callbacks that
 * are not coroutines. Turning either into a `StateFlow` to be able to await it would put the
 * bookkeeping two places at once, and a counter that disagrees with its mirror is worse than no
 * mirror. This is the other direction: leave the state where it is and let the waiters ask it.
 *
 * MAIN THREAD ONLY, and that is what makes it safe without a lock. Every caller — [await], [notifyChanged]
 * and the predicate — runs on the main thread, so a condition cannot change between the check and
 * the suspend, which is the race this shape usually has.
 *
 * Cancellation removes the waiter, so a caller that gives up (every caller here is inside a
 * `withTimeoutOrNull`) leaves nothing behind. A leaked waiter would keep a continuation, and through
 * it the coroutine's whole frame, alive for the life of the Activity.
 */
class Waiters {

    private val waiting = mutableListOf<() -> Unit>()

    /** Suspends until [condition] holds. Returns immediately if it already does. */
    suspend fun await(condition: () -> Boolean) {
        if (condition()) return
        suspendCancellableCoroutine { continuation ->
            lateinit var waiter: () -> Unit
            waiter = {
                if (condition() && continuation.isActive) {
                    waiting.remove(waiter)
                    continuation.resume(Unit)
                }
            }
            waiting += waiter
            continuation.invokeOnCancellation {
                // The continuation may be cancelled off the main thread, and `waiting` is not
                // synchronised; removal is idempotent, and a waiter whose continuation is no longer
                // active is inert anyway, so the worst case is one dead entry cleared on the next
                // notify rather than a torn list.
                waiting.remove(waiter)
            }
        }
    }

    /** Re-tests every waiter's condition. Call after any change to the state they read. */
    fun notifyChanged() {
        if (waiting.isEmpty()) return
        // Over a copy: a waiter that resumes removes itself, and resuming can add another.
        waiting.toList().forEach { it() }
    }
}
