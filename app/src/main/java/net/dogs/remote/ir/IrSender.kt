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

    /** Transmit one raw pattern. Returns false when there is no emitter or it fails. */
    fun transmit(freqHz: Int, pattern: IntArray): Boolean {
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
