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
 *
 * The read side never trusts the stored data: [list] tolerates corrupt JSON
 * and skips malformed entries, so a botched write or a stale backup restore
 * can never crash the app at startup — [RemoteViewModel] builds its state
 * from this at init.
 */
class AttemptLog(context: Context) {
    private val prefs =
        context.getSharedPreferences("dogs_remote_log", Context.MODE_PRIVATE)

    fun log(attempt: Attempt) {
        persist((listOf(attempt) + list()).take(MAX))
    }

    /**
     * Load all attempts, newest first. Never throws: a corrupt prefs string
     * (botched write, stale backup restore) yields an empty list instead of
     * crashing the app at startup. Malformed entries are skipped
     * individually so one bad row doesn't nuke the rest, and the list is
     * capped at [MAX] even if the stored array grew past it by other means.
     */
    fun list(): List<Attempt> {
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        } catch (e: Exception) {
            return emptyList()
        }
        return List(minOf(arr.length(), MAX)) { i ->
            try {
                parseAttempt(arr.getJSONObject(i))
            } catch (e: Exception) {
                null
            }
        }.mapNotNull { it }
    }

    /** Parse one stored attempt; throws on malformed shapes. */
    private fun parseAttempt(o: JSONObject): Attempt =
        Attempt(
            ts = o.getLong("ts"),
            variantId = o.getString("variantId"),
            variantLabel = o.getString("variantLabel"),
            button = o.getString("button"),
            worked = o.optBoolean("worked", false),
        )

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
