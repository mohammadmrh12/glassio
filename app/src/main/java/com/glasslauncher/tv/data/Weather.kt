package com.glasslauncher.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.roundToInt

/** What the top bar shows: current conditions for the chosen city (Open-Meteo, no key needed). */
data class WeatherNow(
    val city: String, val tempC: Double, val code: Int, val day: Boolean,
    val hiC: Double = Double.NaN, val loC: Double = Double.NaN, val feelsC: Double = Double.NaN,
    val humidity: Int = -1, val windKmh: Double = Double.NaN,
) {
    /** sun | moon | partly | cloud | fog | drizzle | rain | snow | storm */
    val icon: String get() = when (code) {
        0, 1 -> if (day) "sun" else "moon"
        2 -> "partly"
        3 -> "cloud"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> "cloud"
    }
    val condition: String get() = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Broken Clouds"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61 -> "Light Rain"
        63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Hail Storm"
        else -> ""
    }
    fun temp(fahrenheit: Boolean) = (if (fahrenheit) tempC * 9 / 5 + 32 else tempC).roundToInt().toString() + "°"
}

class Weather(private val settings: Settings, private val http: Http, private val scope: CoroutineScope) {
    val now = MutableStateFlow<WeatherNow?>(null)
    private var place: Triple<String, Double, Double>? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (settings.weatherMode != "off" || (settings.menuMode != "off" && settings.menuWeather)) runCatching { refresh() }.onFailure { Glog.w("Weather", it) }
                delay(15 * 60_000L)
            }
        }
    }

    /** The place the city name resolved to, shown in Settings ("Dhī Qār, Iraq"). */
    val found = MutableStateFlow("")

    private val month = 30L * 24 * 3600_000

    /**
     * Finds the city. Open-Meteo first; then OpenStreetMap (understands Arabic names); then, for names typed
     * as one word ("dhiqar"), the same word split in two ("dhi qar").
     */
    private fun locate(city: String): Pair<Double, Double>? {
        fun meteo(q: String) = runCatching {
            http.json("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(q, "UTF-8"), month)
                .optJSONArray("results")?.optJSONObject(0)?.let { r ->
                    found.value = listOfNotNull(r.optString("name"), r.optString("country").takeIf { it.isNotBlank() }).joinToString(", ")
                    r.getDouble("latitude") to r.getDouble("longitude")
                }
        }.getOrNull()
        fun osm(q: String) = runCatching {
            http.jsonArray("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8"), month)
                .optJSONObject(0)?.let { r ->
                    found.value = r.optString("display_name").split(",").let { (it.firstOrNull().orEmpty() + ", " + it.lastOrNull().orEmpty().trim()) }
                    r.getString("lat").toDouble() to r.getString("lon").toDouble()
                }
        }.getOrNull()
        meteo(city)?.let { return it }
        osm(city)?.let { return it }
        val w = city.trim()
        if (' ' !in w && w.length in 5..12) for (i in 2..w.length - 2) meteo(w.substring(0, i) + " " + w.substring(i))?.let { return it }
        return null
    }

    /** Called when the city changes in Settings. */
    fun reload() { place = null; scope.launch(Dispatchers.IO) { runCatching { refresh() } } }

    private fun refresh() {
        val city = settings.weatherCity.ifBlank { "Baghdad" }
        val p = place?.takeIf { it.first == city } ?: run {
            val ll = locate(city) ?: run { found.value = "City not found"; return }
            Triple(city, ll.first, ll.second).also { place = it }
        }
        val j = http.json(
            "https://api.open-meteo.com/v1/forecast?latitude=${p.second}&longitude=${p.third}&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto",
            10 * 60_000L,
        )
        val c = j.optJSONObject("current") ?: return
        val d = j.optJSONObject("daily")
        now.value = WeatherNow(
            found.value.substringBefore(",").ifBlank { city }, c.optDouble("temperature_2m"), c.optInt("weather_code"), c.optInt("is_day", 1) == 1,
            d?.optJSONArray("temperature_2m_max")?.optDouble(0) ?: Double.NaN, d?.optJSONArray("temperature_2m_min")?.optDouble(0) ?: Double.NaN,
            c.optDouble("apparent_temperature"), c.optInt("relative_humidity_2m", -1), c.optDouble("wind_speed_10m"),
        )
    }
}
