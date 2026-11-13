package com.pococontrol.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.os.Build

/** On Android 13+, prefer the actual Wi-Fi network even when a VPN is active. */
internal fun discoverOnWifi(context: Context, manager: NsdManager, type: String, listener: NsdManager.DiscoveryListener) {
    if (Build.VERSION.SDK_INT >= 33) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (wifi != null) {
            manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, wifi, context.mainExecutor, listener)
            return
        }
    }
    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
}

/** Seleciona apenas um IPv4 anunciado que pertence a uma interface do próprio telefone. */
internal fun ownAdvertisedIpv4(info: android.net.nsd.NsdServiceInfo): String? = try {
    val candidates = mutableListOf<java.net.InetAddress>()
    if (Build.VERSION.SDK_INT >= 34) {
        try { candidates.addAll(info.hostAddresses) } catch (_: Exception) { }
    }
    @Suppress("DEPRECATION")
    info.host?.let { candidates.add(it) }
    val own = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    candidates.mapNotNull { it.hostAddress }.firstOrNull { it.count { c -> c == '.' } == 3 && own.contains(it) }
} catch (_: Exception) { null }
