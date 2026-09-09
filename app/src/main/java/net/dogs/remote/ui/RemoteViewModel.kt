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

    /** Tries vol_up across every variant in magic order (Samsung first). */
    fun startMagic() {
        if (magicJob?.isActive == true) return
        val variants = db.magicVariants
        magicJob = viewModelScope.launch {
            for ((i, v) in variants.withIndex()) {
                ensureActive()
                magicState = MagicState.Running(i, variants.size, v.id)
                val btn = v.buttons["vol_up"]
                if (btn != null) {
                    withContext(Dispatchers.IO) { sender.transmit(btn.freqHz, btn.pattern) }
                    attemptLog.log(
                        Attempt(System.currentTimeMillis(), v.id, v.label, "vol_up", worked = false),
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
     */
    fun magicWorked(nickname: String, variantId: String) {
        magicJob?.cancel()
        attemptLog.markWorked(variantId, "vol_up")
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
        viewModelScope.launch(Dispatchers.IO) {
            sender.transmit(btn.freqHz, btn.pattern)
            attemptLog.log(
                Attempt(System.currentTimeMillis(), variant.id, variant.label, buttonId, worked = false),
            )
            withContext(Dispatchers.Main) { attempts = attemptLog.list() }
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
                    withContext(Dispatchers.IO) { sender.transmit(btn.freqHz, btn.pattern) }
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
            withContext(Dispatchers.IO) {
                sender.transmit(btn.freqHz, btn.pattern)
                attemptLog.log(
                    Attempt(System.currentTimeMillis(), variant.id, variant.label, buttonId, worked = false),
                )
            }
            withContext(Dispatchers.Main) { attempts = attemptLog.list() }
            delay(REPEAT_INITIAL_DELAY_MS)
            while (true) {
                ensureActive()
                withContext(Dispatchers.IO) { sender.transmit(btn.freqHz, btn.pattern) }
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
    }
}
