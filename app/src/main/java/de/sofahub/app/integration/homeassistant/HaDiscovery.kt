package de.sofahub.app.integration.homeassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

data class FoundInstance(val name: String, val url: String)

/**
 * Findet Home-Assistant-Instanzen im lokalen Netz über deren Zeroconf-Ankündigung (`_home-assistant._tcp`).
 * Nötig, weil Fire OS 8 (Android 11) `.local`-Namen wie `homeassistant.local` nicht auflösen kann – gemessen am Testgerät.
 * Bevorzugt wird die angekündigte `internal_url`, sonst `http://<IPv4>:<Port>`.
 */
class HaDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(durationMs: Long = 8_000): Flow<FoundInstance> = channelFlow {
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { pending.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { pending.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        val started = runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        if (!started) return@channelFlow
        val seen = mutableSetOf<String>()
        // resolveService verträgt unter Android 11 nur eine Auflösung gleichzeitig: nacheinander abarbeiten.
        launch {
            for (info in pending) {
                val resolved = resolve(info) ?: continue
                val found = toInstance(resolved) ?: continue
                if (seen.add(found.url)) send(found)
            }
        }
        delay(durationMs)
        runCatching { nsd.stopServiceDiscovery(listener) }
        pending.close()
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        val result = CompletableDeferred<NsdServiceInfo?>()
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { result.complete(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { result.complete(serviceInfo) }
            })
        }.onFailure { return null }
        return withTimeoutOrNull(4_000) { result.await() }
    }

    private fun toInstance(info: NsdServiceInfo): FoundInstance? {
        val attrs = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
        val name = attrs["location_name"]?.takeIf { it.isNotBlank() } ?: info.serviceName
        @Suppress("DEPRECATION")
        val host = info.host
        val ip = (host as? Inet4Address)?.hostAddress
        val internal = attrs["internal_url"]?.takeIf { it.startsWith("http") && !hostOf(it).endsWith(".local") }
        val url = internal ?: ip?.let { "http://$it:${info.port}" } ?: return null
        return FoundInstance(name, url.removeSuffix("/"))
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp."
    }
}
