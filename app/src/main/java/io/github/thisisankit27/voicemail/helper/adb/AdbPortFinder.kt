package io.github.thisisankit27.voicemail.helper.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.thisisankit27.voicemail.core.AppLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds the TCP port of this phone's Wireless debugging server. The port is random on every start,
 * and adbd announces it over mDNS as `_adb-tls-connect._tcp` (the same discovery `adb` on a PC uses).
 * Several phones on the same Wi-Fi may announce; only our own adbd accepts on 127.0.0.1.
 */
object AdbPortFinder {
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    const val LEGACY_PORT = 5555

    fun isOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
    } catch (_: Exception) {
        false
    }

    /** Returns the Wireless debugging port, or null if none appears within [timeoutMs]. */
    suspend fun findTlsPort(context: Context, timeoutMs: Long): Int? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { AppLog.w("mDNS discovery failed code=$errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            return withTimeoutOrNull(timeoutMs) {
                val tried = mutableSetOf<Int>()
                while (true) {
                    val service = found.receive()
                    val port = resolvePort(nsd, service) ?: continue
                    if (port in tried) continue
                    tried += port
                    if (isOpen(port)) {
                        AppLog.i("Wireless debugging found on port $port")
                        return@withTimeoutOrNull port
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { }
        }
    }

    @Suppress("DEPRECATION") // resolveService(info, listener) is the only form on API 31-33.
    private suspend fun resolvePort(nsd: NsdManager, service: NsdServiceInfo): Int? {
        val result = Channel<Int?>(1)
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { result.trySend(null) }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) { result.trySend(serviceInfo.port) }
        })
        return withTimeoutOrNull(4_000) { result.receive() }
    }
}
