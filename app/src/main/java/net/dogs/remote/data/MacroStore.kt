package net.dogs.remote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One step in a macro: fire [buttonId], then wait [delayAfterMs] before the
 * next step. Delays are clamped to
 * [STEP_DELAY_MIN_MS]..[STEP_DELAY_MAX_MS] — macros are user-built sequences,
 * and an unbounded delay would let a typo hang the emitter for minutes.
 */
data class MacroStep(
    val buttonId: String,
    val delayAfterMs: Long,
)

/** A named sequence of button fires for one TV variant ("movie night", ...). */
data class IrMacro(
    val id: String,
    val name: String,
    val variantId: String,
    val steps: List<MacroStep>,
)

/**
 * CRUD for macros, backed by SharedPreferences (same shape as profiles).
 *
 * Everything stored goes through [sanitize]: blank names are rejected,
 * steps are capped at [MAX_STEPS], blank button ids are dropped, and delays
 * are coerced into bounds. A macro id that already exists is replaced
 * (update); new ids are rejected once [MAX_MACROS] is reached. The playback
 * side never trusts the stored data either — see RemoteViewModel.runMacro.
 */
class MacroStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("dogs_remote_macros", Context.MODE_PRIVATE)

    fun list(): List<IrMacro> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val steps = mutableListOf<MacroStep>()
            val sArr = o.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until sArr.length()) {
                val s = sArr.getJSONObject(j)
                steps += MacroStep(
                    buttonId = s.getString("buttonId"),
                    delayAfterMs = s.optLong("delayAfterMs", STEP_DELAY_DEFAULT_MS),
                )
            }
            IrMacro(
                id = o.getString("id"),
                name = o.getString("name"),
                variantId = o.getString("variantId"),
                steps = steps,
            )
        }
    }

    /**
     * Store a macro. Returns false (and stores nothing) when the macro is
     * invalid (blank name, no usable steps) or the macro list is already at
     * [MAX_MACROS] for a brand-new id. Existing ids update in place and are
     * always allowed.
     */
    fun save(macro: IrMacro): Boolean {
        val clean = sanitize(macro) ?: return false
        val current = list()
        val isNew = current.none { it.id == clean.id }
        if (isNew && current.size >= MAX_MACROS) return false
        persist(current.filter { it.id != clean.id } + clean)
        return true
    }

    fun delete(id: String) {
        persist(list().filter { it.id != id })
    }

    private fun persist(macros: List<IrMacro>) {
        val arr = JSONArray()
        for (m in macros) {
            val steps = JSONArray()
            for (s in m.steps) {
                steps.put(
                    JSONObject()
                        .put("buttonId", s.buttonId)
                        .put("delayAfterMs", s.delayAfterMs),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("variantId", m.variantId)
                    .put("steps", steps),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        const val KEY = "macros"
        /** Hard cap on saved macros. */
        const val MAX_MACROS = 20
        /** Hard cap on steps per macro — bounds worst-case run time and log spam. */
        const val MAX_STEPS = 12
        /** Delays below this are pointlessly short; above this, use the TV's remote. */
        const val STEP_DELAY_MIN_MS = 100L
        const val STEP_DELAY_MAX_MS = 5000L
        /** Editor default: enough time for the TV to react. */
        const val STEP_DELAY_DEFAULT_MS = 800L
        /** Macro names are labels, not essays. */
        const val NAME_MAX_LEN = 40

        /**
         * Enforce every invariant above. Returns null when the macro has no
         * name or no usable steps; otherwise returns the clamped copy.
         * Never trusts stored data — a macro loaded from disk may predate
         * these bounds, so [save] re-sanitizes on every write.
         */
        fun sanitize(macro: IrMacro): IrMacro? {
            val name = macro.name.trim().take(NAME_MAX_LEN)
            if (name.isEmpty()) return null
            val steps = macro.steps
                .filter { it.buttonId.isNotBlank() }
                .take(MAX_STEPS)
                .map { it.copy(delayAfterMs = it.delayAfterMs.coerceIn(STEP_DELAY_MIN_MS, STEP_DELAY_MAX_MS)) }
            if (steps.isEmpty()) return null
            return macro.copy(name = name, steps = steps)
        }
    }
}
