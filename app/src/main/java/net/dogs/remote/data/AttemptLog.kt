package net.dogs.remote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One recorded transmit: what was tried, where, and whether it worked. */
data class Attempt(
    val ts: Long,
    val variantId: String,
    val variantLabel: String,
    val button: String,
    val worked: Boolean,
)

/**
 * Append-only attempt log (capped at [MAX]), backed by SharedPreferences.
 * This is the attempt-analysis log: every Magic Mode probe and every manual
 * or blast transmit is recorded here.
 */
class AttemptLog(context: Context) {
    private val prefs =
        context.getSharedPreferences("dogs_remote_log", Context.MODE_PRIVATE)

    fun log(attempt: Attempt) {
        persist((listOf(attempt) + list()).take(MAX))
    }

    fun list(): List<Attempt> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Attempt(
                ts = o.getLong("ts"),
                variantId = o.getString("variantId"),
                variantLabel = o.getString("variantLabel"),
                button = o.getString("button"),
                worked = o.optBoolean("worked", false),
            )
        }
    }

    /** Marks the most recent un-worked matching attempt as worked. */
    fun markWorked(variantId: String, button: String) {
        val items = list().toMutableList()
        val idx = items.indexOfFirst {
            it.variantId == variantId && it.button == button && !it.worked
        }
        if (idx >= 0) {
            items[idx] = items[idx].copy(worked = true)
            persist(items)
        }
    }

    fun clear() {
        persist(emptyList())
    }

    private fun persist(items: List<Attempt>) {
        val arr = JSONArray()
        for (a in items) {
            arr.put(
                JSONObject()
                    .put("ts", a.ts)
                    .put("variantId", a.variantId)
                    .put("variantLabel", a.variantLabel)
                    .put("button", a.button)
                    .put("worked", a.worked),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "attempts"
        const val MAX = 200
    }
}
