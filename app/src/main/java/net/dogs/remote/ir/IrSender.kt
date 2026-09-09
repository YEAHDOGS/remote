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

    private companion object {
        const val TAG = "IrSender"
    }
}
