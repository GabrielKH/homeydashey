package app.homeydash.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/** A Homey found on the local network. */
data class FoundHomey(val address: String, val homeyId: String)

/**
 * Looks for Homeys on the Wi-Fi: it listens for their mDNS announcement and also
 * asks every address on the tablet's subnet for Homey's public ping, which answers
 * with an `X-Homey-ID` header. Each Homey is reported once.
 */
class HomeyDiscovery(private val context: Context, http: OkHttpClient) {
    private val quick = http.newBuilder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .build()

    fun discover(): Flow<FoundHomey> = channelFlow {
        val seen = mutableSetOf<String>()
        suspend fun report(found: FoundHomey) {
            val isNew = synchronized(seen) { seen.add(found.homeyId) }
            if (isNew) send(found)
        }

        launch { scanSubnet { report(it) } }

        val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = resolved.host?.hostAddress ?: return
                        launch(Dispatchers.IO) { ping(host)?.let { report(it) } }
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                })
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        val started = runCatching { nsd?.discoverServices("_homey._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess && nsd != null
        awaitClose { if (started) runCatching { nsd?.stopServiceDiscovery(listener) } }
    }.flowOn(Dispatchers.IO)

    /** Pings every host in the tablet's /24 (or smaller) network, a few dozen at a time. */
    private suspend fun scanSubnet(report: suspend (FoundHomey) -> Unit) = coroutineScope {
        val hosts = subnetHosts(context)
        val gate = Semaphore(48)
        hosts.forEach { host ->
            launch(Dispatchers.IO) { gate.withPermit { ping(host)?.let { report(it) } } }
        }
    }

    private fun ping(host: String): FoundHomey? = runCatching {
        val request = Request.Builder().url("http://$host/api/manager/system/ping").build()
        quick.newCall(request).execute().use { response ->
            val id = response.header("X-Homey-ID")?.takeIf { it.isNotBlank() } ?: return null
            FoundHomey(host, id)
        }
    }.getOrNull()
}

/** Every other address on the tablet's own /24, for when a search by name finds nothing. */
internal fun subnetHosts(context: Context): List<String> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val link = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
    val address = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return emptyList()
    val bytes = address.address.address
    val own = bytes[3].toInt() and 0xFF
    val prefix = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}"
    // Larger networks are cut to the tablet's own /24; Homeys are almost always there.
    return (1..254).filter { it != own }.map { "$prefix.$it" }
}
