package net.dogs.remote.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log

/**
 * Thin wrapper around Android's [ConsumerIrManager].
 *
 * All transmits are synchronous and fast (a few ms); callers are expected to
 * run them off the main thread. Invalid patterns are rejected by the
 * framework with IllegalArgumentException, which we catch and report.
 */
class IrSender(context: Context) {
    private val manager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val hasEmitter: Boolean
        get() = manager?.hasIrEmitter() == true

    /** Transmit one raw pattern. Returns false when there is no emitter or it fails.
     *
     * Trust boundary: [transmit] never hands the framework an unchecked
     * payload. [isValidTransmit] rejects anything outside the realistic IR
     * carrier band, empty or oversized patterns, and non-positive
     * durations *before* the emitter is touched, so a bad caller (or bad
     * data that slipped past the database parse) fails here instead of
     * relying on the framework's IllegalArgumentException.
     */
    fun transmit(freqHz: Int, pattern: IntArray): Boolean {
        if (!isValidTransmit(freqHz, pattern)) {
            Log.w(TAG, "refusing malformed IR transmit: freq=${freqHz}Hz words=${pattern.size}")
            return false
        }
        val m = manager ?: return false
        return try {
            m.transmit(freqHz, pattern)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "IR transmit failed", t)
            false
        }
    }

    /**
     * Carrier-frequency ranges this device's IR emitter reports supporting,
     * from [ConsumerIrManager.getCarrierFrequencies]. Empty list means the
     * device told us nothing (no manager, no emitter, or an OEM that doesn't
     * report ranges) — callers must treat "unknown" as fail-open, never as
     * a reason to block or warn.
     */
    fun carrierRanges(): List<IntRange> {
        return try {
            manager?.carrierFrequencies
                ?.mapNotNull { r ->
                    val min = r.minFrequency
                    val max = r.maxFrequency
                    if (min > 0 && max >= min) min..max else null
                }
                ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "could not read carrier frequencies", t)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "IrSender"
    }
}

/**
 * Pure carrier-compatibility check. A frequency is supported when it falls
 * inside any reported range; an empty (unknown) range list is fail-open —
 * the device may simply not report, so we warn about nothing.
 */
fun isCarrierSupported(freqHz: Int, ranges: List<IntRange>): Boolean =
    ranges.isEmpty() || ranges.any { freqHz in it }

/** Real IR carrier frequencies live between 10 kHz and 100 kHz. */
internal const val IR_TRANSMIT_FREQ_MIN_HZ = 10_000
internal const val IR_TRANSMIT_FREQ_MAX_HZ = 100_000
/**
 * Patterns in the shipped database are a few hundred words at most; 10k
 * words is a sane ceiling so a malformed payload can't ask the emitter to
 * sit on one burst forever.
 */
internal const val IR_TRANSMIT_PATTERN_MAX_WORDS = 10_000

/**
 * Emitter trust boundary: true when [freqHz] sits in the realistic IR
 * carrier band and [pattern] is a non-empty, bounded list of positive
 * on/off microsecond durations. Pure so the contract is testable without
 * Android tooling; [IrSender.transmit] runs every payload through it.
 */
fun isValidTransmit(freqHz: Int, pattern: IntArray): Boolean {
    if (freqHz !in IR_TRANSMIT_FREQ_MIN_HZ..IR_TRANSMIT_FREQ_MAX_HZ) return false
    if (pattern.isEmpty() || pattern.size > IR_TRANSMIT_PATTERN_MAX_WORDS) return false
    return pattern.all { it > 0 }
}
