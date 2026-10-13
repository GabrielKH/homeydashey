package app.homeydash.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.homeydash.homey.Forecast
import app.homeydash.homey.Place
import app.homeydash.homey.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.coroutines.resume

/** Where the weather comes from. */
enum class WeatherSource(val key: String, val label: String) {
    Homey("homey", "Homey's location"),
    Gps("gps", "This tablet (GPS)"),
    Ip("ip", "Internet address"),
    Place("place", "Chosen place"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Homey
    }
}

data class WeatherState(
    val placeName: String? = null,
    val forecast: Forecast? = null,
    val error: String? = null,
)

/** Keeps the overview's weather current: finds the location, then asks Open-Meteo every 20 minutes. */
class WeatherRepository(
    private val context: Context,
    http: OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** The connected Homey's location, or null. */
    private val homeyLocation: suspend () -> Pair<Double, Double>?,
) {
    val client = WeatherClient(http)

    val source: StateFlow<WeatherSource> = combine(settings.weatherSource, settings.weatherPlace) { s, _ -> WeatherSource.of(s) }
        .stateIn(scope, SharingStarted.Eagerly, WeatherSource.Homey)
    val place: StateFlow<Place?> = combine(settings.weatherSource, settings.weatherPlace) { _, p -> p?.let(::decode) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            combine(settings.weatherSource, settings.weatherPlace) { s, p -> s to p }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            while (true) {
                delay(REFRESH_MS)
                refresh()
            }
        }
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            try {
                val (name, lat, lon) = locate()
                val forecast = client.forecast(lat, lon)
                _state.value = WeatherState(name, forecast, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Couldn't load the weather")
            }
        }
    }

    fun choose(source: WeatherSource, place: Place? = null) {
        scope.launch { settings.setWeather(source.key, place?.let(::encode)) }
    }

    suspend fun search(query: String): List<Place> =
        try {
            client.search(query, Locale.getDefault().language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun locate(): Triple<String, Double, Double> {
        // Read the saved choice directly: the StateFlows above may not have caught up yet.
        val chosen = settings.weatherPlace.first()?.let(::decode)
        when (WeatherSource.of(settings.weatherSource.first())) {
            WeatherSource.Place -> chosen?.let { return Triple(it.name, it.latitude, it.longitude) }
            WeatherSource.Gps -> {
                val here = deviceLocation() ?: error("No location from the tablet yet. Allow location in Settings → Weather.")
                return Triple(cityName(here.latitude, here.longitude) ?: "Here", here.latitude, here.longitude)
            }
            WeatherSource.Homey -> runCatching { homeyLocation() }.getOrNull()?.let { (lat, lon) ->
                return Triple(cityName(lat, lon) ?: "Home", lat, lon)
            }
            WeatherSource.Ip -> Unit
        }
        // The internet address is the fallback for every source.
        val ip = client.ipLocation() ?: error("Couldn't work out where you are. Pick a place in Settings → Weather.")
        return Triple(ip.name, ip.latitude, ip.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }?.let { return it }
        if (Build.VERSION.SDK_INT < 30 || providers.isEmpty()) return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                manager.getCurrentLocation(providers.first(), null, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun cityName(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()
    }

    private fun encode(place: Place) = listOf(place.name, place.detail.orEmpty(), place.latitude.toString(), place.longitude.toString()).joinToString("\t")

    private fun decode(text: String): Place? {
        val parts = text.split('\t')
        if (parts.size < 4) return null
        return Place(parts[0], parts[1].ifEmpty { null }, parts[2].toDoubleOrNull() ?: return null, parts[3].toDoubleOrNull() ?: return null)
    }

    private companion object {
        const val REFRESH_MS = 20 * 60 * 1000L
    }
}
