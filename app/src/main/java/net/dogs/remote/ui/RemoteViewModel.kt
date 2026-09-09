package net.dogs.remote.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dogs.remote.data.Attempt
import net.dogs.remote.data.AttemptLog
import net.dogs.remote.data.ProfileStore
import net.dogs.remote.data.TvProfile
import net.dogs.remote.ir.IrDatabase
import net.dogs.remote.ir.IrSender
import net.dogs.remote.ir.isCarrierSupported
import net.dogs.remote.ir.loadIrDatabase
import java.util.UUID

/** Magic Mode state machine. */
sealed interface MagicState {
    data object Idle : MagicState
    data class Running(val done: Int, val total: Int, val currentVariantId: String) : MagicState
    data object Done : MagicState
    data class Saved(val variantId: String) : MagicState
}

/** Blast-across-all-variants state machine. */
sealed interface BlastState {
    data object Idle : BlastState
    data class Running(val done: Int, val total: Int, val buttonId: String) : BlastState
}

/**
 * Button ids eligible as the Magic Mode probe signal. Every option exists on
 * every magic variant in ir_database.json, so switching the probe never
 * silently skips variants. vol_up stays the default, so historical behavior
 * is unchanged unless the user picks otherwise.
 */
val MAGIC_PROBE_OPTIONS = listOf("mute", "vol_up", "vol_down", "power")
const val MAGIC_PROBE_DEFAULT = "vol_up"

/**
 * Single source of truth for all four screens.
 *
 * All IR transmits run on Dispatchers.IO; all state updates happen on the
 * main thread. Long sweeps (magic / blast) are cancellable Jobs — leaving a
 * screen or pressing Stop cancels them, so the emitter never runs away.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    val db: IrDatabase = loadIrDatabase(app)
    val sender = IrSender(app)

    private val profileStore = ProfileStore(app)
    private val attemptLog = AttemptLog(app)

    var profiles by mutableStateOf(profileStore.list())
        private set
    var attempts by mutableStateOf(attemptLog.list())
        private set
    var magicState by mutableStateOf<MagicState>(MagicState.Idle)
        private set
    var blastState by mutableStateOf<BlastState>(BlastState.Idle)
        private set
    var blastMode by mutableStateOf(false)

    /**
     * Last transmit-failure message, or null when the last transmit
     * succeeded (or nothing has been sent yet). Transmits used to fail
     * silently — a tap that did nothing looked identical to a tap the TV
     * ignored. Surfacing the failure lets the user tell "phone problem"
     * apart from "wrong code". Write only through [transmitAndReport];
     * dismiss from the UI with [clearTransmitError].
     */
    var transmitError: String? by mutableStateOf(null)
        private set

    /** Dismiss the transmit-failure notice (also auto-clears on next success). */
    fun clearTransmitError() {
        transmitError = null
    }

    /**
     * Single funnel for every IR transmit in this ViewModel: runs the
     * blocking transmit on Dispatchers.IO, then reports the result on the
     * main thread. A failed transmit sets [transmitError]; a success
     * clears any previous failure. Returns the transmit result.
     */
    private suspend fun transmitAndReport(freqHz: Int, pattern: IntArray): Boolean {
        val ok = withContext(Dispatchers.IO) { sender.transmit(freqHz, pattern) }
        withContext(Dispatchers.Main) {
            transmitError = if (ok) null else TRANSMIT_ERROR_MSG
        }
        return ok
    }

    /**
     * Which button the Magic sweep probes with. Write through
     * [setMagicProbeButtonId], which rejects anything outside
     * [MAGIC_PROBE_OPTIONS], so the probe can never become an unknown id.
     */
    var magicProbeButtonId: String by mutableStateOf(MAGIC_PROBE_DEFAULT)
        private set

    fun setMagicProbeButtonId(buttonId: String) {
        if (buttonId in MAGIC_PROBE_OPTIONS) magicProbeButtonId = buttonId
    }

    /** Carrier ranges this device's emitter reports (empty = unknown, fail-open). */
    val carrierRanges: List<IntRange> = sender.carrierRanges()

    /**
     * Variant ids whose buttons need carrier frequencies outside what this
     * device reports supporting. Flagged on `any` out-of-range button — if
     * one button can't go out, the variant can't fully work. Stays empty when
     * the device reports nothing (unknown caps = no warnings).
     */
    val unsupportedVariantIds: Set<String> =
        db.brands.flatMap { it.variants }
            .filter { v ->
                v.buttons.values.any { btn ->
                    !isCarrierSupported(btn.freqHz, carrierRanges)
                }
            }
            .map { it.id }
            .toSet()

    /** Variant selected in Manual Mode; Profiles "Use" writes here too. */
    var manualVariantId: String? by mutableStateOf(
        profileStore.list().find { it.isDefault }?.variantId
            ?: db.magicVariants.firstOrNull()?.id,
    )

    private var magicJob: Job? = null
    private var blastJob: Job? = null

    // ---------------- magic mode ----------------

    /** Tries the selected probe signal across every variant in magic order (Samsung first). */
    fun startMagic() {
        if (magicJob?.isActive == true) return
        // Captured here: changing the selector mid-sweep must not rewire a
        // sweep already in flight.
        val probeButtonId = magicProbeButtonId
        val variants = db.magicVariants
        magicJob = viewModelScope.launch {
            for ((i, v) in variants.withIndex()) {
                ensureActive()
                magicState = MagicState.Running(i, variants.size, v.id)
                val btn = v.buttons[probeButtonId]
                if (btn != null) {
                    transmitAndReport(btn.freqHz, btn.pattern)
                    attemptLog.log(
                        Attempt(System.currentTimeMillis(), v.id, v.label, probeButtonId, worked = false),
                    )
                    attempts = attemptLog.list()
                }
                delay(MAGIC_GAP_MS)
            }
            magicState = MagicState.Done
        }
    }

    fun stopMagic() {
        magicJob?.cancel()
        magicState = MagicState.Idle
    }

    /**
     * User tapped IT WORKED: save the variant that was being probed when
     * they tapped it as a profile.
     *
     * [variantId] is captured by the UI at IT WORKED tap time — NOT read
     * from [magicState] here — because the sweep keeps advancing while the
     * name dialog is open. Reading the state at Save time could save a
     * later, wrong variant (or silently no-op once the sweep finished).
     * [probeButtonId] is captured at tap time the same way, so the attempt
     * log marks the signal that was actually transmitted. Unknown values
     * fall back to [MAGIC_PROBE_DEFAULT]; the selector never sends one, but
     * the log must stay truthful regardless of the caller.
     */
    fun magicWorked(nickname: String, variantId: String, probeButtonId: String) {
        magicJob?.cancel()
        val probe = if (probeButtonId in MAGIC_PROBE_OPTIONS) probeButtonId else MAGIC_PROBE_DEFAULT
        attemptLog.markWorked(variantId, probe)
        attempts = attemptLog.list()
        val variant = db.variantsById[variantId] ?: return
        addProfile(nickname.ifBlank { variant.label }, variantId)
        manualVariantId = variantId
        magicState = MagicState.Saved(variantId)
    }

    // ---------------- manual mode ----------------

    fun sendButton(variantId: String, buttonId: String) {
        val variant = db.variantsById[variantId] ?: return
        if (blastMode) {
            startBlast(buttonId)
            return
        }
        val btn = variant.buttons[buttonId] ?: return
        viewModelScope.launch {
            transmitAndReport(btn.freqHz, btn.pattern)
            attemptLog.log(
                Attempt(System.currentTimeMillis(), variant.id, variant.label, buttonId, worked = false),
            )
            attempts = attemptLog.list()
        }
    }

    /** Blasts one button across every variant (rate-limited, cancellable). */
    fun startBlast(buttonId: String) {
        if (blastJob?.isActive == true) return
        val variants = db.magicVariants
        blastJob = viewModelScope.launch {
            for ((i, v) in variants.withIndex()) {
                ensureActive()
                blastState = BlastState.Running(i, variants.size, buttonId)
                val btn = v.buttons[buttonId]
                if (btn != null) {
                    transmitAndReport(btn.freqHz, btn.pattern)
                    attemptLog.log(
                        Attempt(System.currentTimeMillis(), v.id, v.label, buttonId, worked = false),
                    )
                    attempts = attemptLog.list()
                }
                delay(BLAST_GAP_MS)
            }
            blastState = BlastState.Idle
        }
    }

    fun stopBlast() {
        blastJob?.cancel()
        blastState = BlastState.Idle
    }

    // ---------------- hold-to-repeat ----------------

    private var repeatJob: Job? = null

    /**
     * Hold-to-repeat for volume/channel keys: fires once immediately, then
     * repeats every [REPEAT_INTERVAL_MS] after [REPEAT_INITIAL_DELAY_MS]
     * until [stopRepeat] is called (finger lift). A single attempt is logged
     * per press so the log doesn't flood.
     */
    fun startRepeat(variantId: String, buttonId: String) {
        stopRepeat()
        val variant = db.variantsById[variantId] ?: return
        val btn = variant.buttons[buttonId] ?: return
        repeatJob = viewModelScope.launch {
            transmitAndReport(btn.freqHz, btn.pattern)
            attemptLog.log(
                Attempt(System.currentTimeMillis(), variant.id, variant.label, buttonId, worked = false),
            )
            attempts = attemptLog.list()
            delay(REPEAT_INITIAL_DELAY_MS)
            while (true) {
                ensureActive()
                transmitAndReport(btn.freqHz, btn.pattern)
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    // ---------------- profiles ----------------

    fun addProfile(name: String, variantId: String) {
        profileStore.save(
            TvProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                variantId = variantId,
                createdAt = System.currentTimeMillis(),
                isDefault = profiles.isEmpty(),
            ),
        )
        profiles = profileStore.list()
    }

    fun renameProfile(id: String, name: String) {
        profileStore.rename(id, name)
        profiles = profileStore.list()
    }

    fun deleteProfile(id: String) {
        profileStore.delete(id)
        profiles = profileStore.list()
    }

    fun setDefaultProfile(id: String) {
        profileStore.setDefault(id)
        profiles = profileStore.list()
    }

    fun clearLog() {
        attemptLog.clear()
        attempts = attemptLog.list()
    }

    private companion object {
        /** Gap between Magic Mode probes — fast, but gives the TV time to react. */
        const val MAGIC_GAP_MS = 650L
        /** Gap between blast transmits. */
        const val BLAST_GAP_MS = 400L
        /** Hold-to-repeat: delay before repeating starts. */
        const val REPEAT_INITIAL_DELAY_MS = 450L
        /** Hold-to-repeat: interval between repeated transmits. */
        const val REPEAT_INTERVAL_MS = 220L
        /** Surfaced when IrSender.transmit returns false (no emitter / framework failure). */
        const val TRANSMIT_ERROR_MSG =
            "IR transmit failed — the phone's emitter may be busy or unavailable. Check it and try again."
    }
}
