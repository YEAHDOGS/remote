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

/** CRUD for profiles, backed by SharedPreferences (no database needed). */
class ProfileStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("dogs_remote_profiles", Context.MODE_PRIVATE)

    fun list(): List<TvProfile> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            TvProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                variantId = o.getString("variantId"),
                createdAt = o.getLong("createdAt"),
                isDefault = o.optBoolean("isDefault", false),
            )
        }.sortedByDescending { it.createdAt }
    }

    fun save(profile: TvProfile) {
        persist(list().filter { it.id != profile.id } + profile)
    }

    fun rename(id: String, name: String) {
        persist(list().map { if (it.id == id) it.copy(name = name) else it })
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

    private companion object {
        const val KEY = "profiles"
    }
}
