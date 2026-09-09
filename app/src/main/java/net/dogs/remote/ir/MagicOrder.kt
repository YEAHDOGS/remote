package net.dogs.remote.ir

import net.dogs.remote.data.Attempt

/**
 * Learned Magic Mode sweep order.
 *
 * Magic Mode's job is to find the venue's TV fast. The shipped magic order
 * (Samsung first, then the rest) is the best guess for a TV nobody has ever
 * seen — but once the user has tapped IT WORKED at venues, those variants
 * are overwhelmingly the best guess for the *next* venue too.
 *
 * [magicSweepOrder] reorders [magicVariants] so every variant with at least
 * one WORKED attempt is tried first, ordered by most-recent worked attempt
 * (newest first); variants that have never worked keep their shipped magic
 * order behind them. WORKED entries only — mere attempts are not evidence
 * that anything responds, so they must not influence the order.
 *
 * [clearedAtMs] is the reset affordance: WORKED attempts older than it are
 * ignored, so a user who taps "reset learned order" (e.g. after an
 * accidental IT WORKED) starts fresh without the attempt log itself being
 * rewritten. Defaults to 0, which keeps all history — the previous behavior.
 *
 * Pure function: no Android dependencies, no side effects, deterministic.
 * Unknown variant ids in the attempt log are ignored, empty attempt history
 * yields the shipped order unchanged. Regression-tested by
 * tools/test_magic_order.py.
 */
fun magicSweepOrder(
    magicVariants: List<IrVariant>,
    attempts: List<Attempt>,
    clearedAtMs: Long = 0,
): List<IrVariant> {
    val lastWorkedTs: Map<String, Long> = attempts
        .filter { it.worked && it.ts >= clearedAtMs }
        .groupBy { it.variantId }
        .mapValues { (_, rows) -> rows.maxOf { it.ts } }
    val (known, unknown) = magicVariants.partition { it.id in lastWorkedTs }
    // stable: ties keep shipped magic order
    val knownOrdered = known.sortedByDescending { lastWorkedTs.getValue(it.id) }
    return knownOrdered + unknown
}
