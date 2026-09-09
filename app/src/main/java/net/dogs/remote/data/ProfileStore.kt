package net.dogs.remote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved TV: venue nickname + the variant that worked there. */
data class TvProfile(
    val id: String,
    val name: String,
    val variantId: String,
    val createdAt: Long,
    val isDefault: Boolean,
)

/**
 * CRUD for profiles, backed by SharedPreferences (no database needed).
 *
 * The read side never trusts the stored data: [list] tolerates corrupt JSON
 * (botched write, stale restore) by yielding an empty list instead of
 * crashing the app at startup — [RemoteViewModel] builds its state from
 * this at init — skips malformed entries individually, re-runs every entry
 * through [sanitize] so legacy rows can't resurrect blank names or overlong
 * labels, caps the parsed list at [MAX_PROFILES], and collapses multiple
 * defaults to the first so the "at most one default" invariant survives a
 * botched write. Corruption heals naturally: the next [save] rewrites clean
 * data.
 */
class ProfileStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("dogs_remote_profiles", Context.MODE_PRIVATE)

    /** Load all profiles, newest first. Never throws and never returns unsanitized data. */
    fun list(): List<TvProfile> {
        val arr =
            try {
                JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            } catch (e: Exception) {
                return emptyList()
            }
        val parsed =
            List(minOf(arr.length(), MAX_PROFILES)) { i ->
                try {
                    parseProfile(arr.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
            }.mapNotNull { it }.mapNotNull { sanitize(it) }
        // Collapse multiple defaults to the first; a botched write could have set several.
        var seenDefault = false
        return parsed
            .map { p ->
                if (p.isDefault && !seenDefault) {
                    seenDefault = true
                    p
                } else {
                    p.copy(isDefault = false)
                }
            }.sortedByDescending { it.createdAt }
    }

    /** Parse one stored profile object; throws on malformed shapes. */
    private fun parseProfile(o: JSONObject): TvProfile =
        TvProfile(
            id = o.getString("id"),
            name = o.getString("name"),
            variantId = o.getString("variantId"),
            createdAt = o.getLong("createdAt"),
            isDefault = o.optBoolean("isDefault", false),
        )

    fun save(profile: TvProfile) {
        val clean = sanitize(profile) ?: return
        persist(list().filter { it.id != clean.id } + clean)
    }

    fun rename(id: String, name: String) {
        val cleanName = name.trim().take(NAME_MAX_LEN)
        if (cleanName.isEmpty()) return
        persist(list().map { if (it.id == id) it.copy(name = cleanName) else it })
    }

    fun delete(id: String) {
        val remaining = list().filter { it.id != id }
        // Keep exactly one default while any profiles exist.
        val fixed =
            if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
                remaining.mapIndexed { i, p -> if (i == 0) p.copy(isDefault = true) else p }
            } else {
                remaining
            }
        persist(fixed)
    }

    fun setDefault(id: String) {
        persist(list().map { it.copy(isDefault = it.id == id) })
    }

    private fun persist(profiles: List<TvProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("variantId", p.variantId)
                    .put("createdAt", p.createdAt)
                    .put("isDefault", p.isDefault),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        const val KEY = "profiles"
        /** Hard cap on saved profiles — bounds prefs size and list() work. */
        const val MAX_PROFILES = 50
        /** Profile names are venue labels, not essays. */
        const val NAME_MAX_LEN = 40

        /**
         * Enforce every invariant above. Returns null when the profile has a
         * blank id, a blank name, or a blank variant id; otherwise returns
         * the trimmed/capped copy. Never trusts stored data — a profile
         * loaded from disk may predate these bounds, so [save] and [rename]
         * sanitize on every write and [list] re-sanitizes on every load.
         */
        fun sanitize(profile: TvProfile): TvProfile? {
            val name = profile.name.trim().take(NAME_MAX_LEN)
            if (profile.id.isBlank() || name.isEmpty() || profile.variantId.isBlank()) {
                return null
            }
            return profile.copy(name = name)
        }
    }
}
