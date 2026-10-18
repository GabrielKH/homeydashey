package app.homeydash.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Finds Sonos speakers on the Wi-Fi: by their mDNS announcement, and if none answers,
 * by asking each address on the tablet's subnet for a Sonos description on port 1400.
 * One speaker is enough; it knows the rest of the household.
 */
class SonosFinder(private val context: Context, http: OkHttpClient) {
    private val quick = http.newBuilder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()

    suspend fun find(): List<String> {
        val announced = announced()
        return announced.ifEmpty { scanSubnet() }
    }

    private suspend fun announced(): List<String> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val hosts = Collections.synchronizedSet(linkedSetOf<String>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            resolved.host?.hostAddress?.takeIf { '.' in it }?.let(hosts::add)
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    })
                }
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        if (runCatching { nsd.discoverServices("_sonos._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) return emptyList()
        try {
            // Stop as soon as one speaker has answered, or give up after a few seconds.
            repeat(30) {
                if (hosts.isNotEmpty()) return hosts.toList()
                delay(100)
            }
            return hosts.toList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun scanSubnet(): List<String> = withContext(Dispatchers.IO) {
        val gate = Semaphore(48)
        coroutineScope {
            subnetHosts(context).map { host -> async { gate.withPermit { host.takeIf { isSonos(it) } } } }.awaitAll().filterNotNull()
        }
    }

    private fun isSonos(host: String): Boolean = runCatching {
        val request = Request.Builder().url("http://$host:1400/xml/device_description.xml").build()
        quick.newCall(request).execute().use { response ->
            response.isSuccessful && response.body?.string().orEmpty().contains("Sonos", ignoreCase = true)
        }
    }.getOrDefault(false)
}
