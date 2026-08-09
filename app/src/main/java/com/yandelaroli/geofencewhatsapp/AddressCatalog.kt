package com.yandelaroli.geofencewhatsapp

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

object AddressCatalog {
    private const val PREFS = "address_catalog_cache"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    suspend fun districts(context: Context, city: String, state: String): List<String> {
        val key = "districts_${norm(state)}_${norm(city)}"
        cached(context, key)?.let { return it }
        val center = geocode(context, "$city, $state, Brasil") ?: return emptyList()
        val query = """
            [out:json][timeout:25];
            (
              node(around:25000,${center.first},${center.second})[place~"neighbourhood|suburb|quarter"][name];
              relation(around:25000,${center.first},${center.second})[place~"neighbourhood|suburb|quarter"][name];
            );
            out tags;
        """.trimIndent()
        val result = overpass(query).mapNotNull { it.optJSONObject("tags")?.optString("name")?.takeIf(String::isNotBlank) }
            .distinctBy(::norm).sortedBy(::norm)
        save(context, key, result)
        return result
    }

    suspend fun streets(context: Context, district: String, city: String, state: String): List<String> {
        val key = "streets_${norm(state)}_${norm(city)}_${norm(district)}"
        cached(context, key)?.let { return it }
        val center = geocode(context, "$district, $city, $state, Brasil") ?: geocode(context, "$city, $state, Brasil") ?: return emptyList()
        val radius = if (district.isBlank()) 7000 else 4500
        val query = """
            [out:json][timeout:30];
            way(around:$radius,${center.first},${center.second})[highway][name];
            out tags;
        """.trimIndent()
        val result = overpass(query).mapNotNull { it.optJSONObject("tags")?.optString("name")?.takeIf(String::isNotBlank) }
            .distinctBy(::norm).sortedBy(::norm)
        save(context, key, result)
        return result
    }

    fun filter(items: List<String>, typed: String, limit: Int = 30): List<String> {
        val q = norm(typed)
        if (q.isBlank()) return emptyList()
        val parts = q.split(' ').filter(String::isNotBlank)
        return items.asSequence()
            .filter { candidate ->
                val normalized = norm(candidate)
                val words = normalized.split(' ').filter(String::isNotBlank)
                parts.all { part -> words.any { it.startsWith(part) } || normalized.contains(part) }
            }
            .sortedWith(compareBy<String>({ !norm(it).startsWith(q) }, { it.length }, { norm(it) }))
            .take(limit).toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(context: Context, query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val a = Geocoder(context, Locale("pt", "BR")).getFromLocationName(query, 1)?.firstOrNull() ?: return@runCatching null
            a.latitude to a.longitude
        }.getOrNull()
    }

    private suspend fun overpass(query: String) = withContext(Dispatchers.IO) {
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val c = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 12000
            c.readTimeout = 35000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", "GeofenceWhatsAppApp/2.0")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) return@withContext emptyList<JSONObject>()
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val elements = root.optJSONArray("elements") ?: return@withContext emptyList<JSONObject>()
            buildList { for (i in 0 until elements.length()) elements.optJSONObject(i)?.let(::add) }
        } finally { c.disconnect() }
    }

    private fun cached(context: Context, key: String): List<String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = p.getLong("${key}_time", 0L)
        if (System.currentTimeMillis() - time > MAX_AGE_MS) return null
        return p.getString(key, null)?.split('\n')?.filter(String::isNotBlank)
    }

    private fun save(context: Context, key: String, values: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, values.joinToString("\n"))
            .putLong("${key}_time", System.currentTimeMillis()).apply()
    }

    private fun norm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
