package com.yandelaroli.geofencewhatsapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RuleStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<GeofenceRule> {
        val raw = prefs.getString(KEY_RULES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    GeofenceRule(
                        id = item.optString("id"),
                        name = item.optString("name", "Local"),
                        latitude = item.optDouble("latitude"),
                        longitude = item.optDouble("longitude"),
                        radiusMeters = item.optDouble("radiusMeters", 150.0).toFloat(),
                        phone = item.optString("phone"),
                        message = item.optString("message"),
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }.filter { it.id.isNotBlank() }
    }

    fun save(rules: List<GeofenceRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("latitude", rule.latitude)
                    .put("longitude", rule.longitude)
                    .put("radiusMeters", rule.radiusMeters.toDouble())
                    .put("phone", rule.phone)
                    .put("message", rule.message)
                    .put("enabled", rule.enabled)
            )
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    fun find(id: String): GeofenceRule? = load().firstOrNull { it.id == id }

    companion object {
        private const val PREFS = "geofence_rules_v2"
        private const val KEY_RULES = "rules"
    }
}
