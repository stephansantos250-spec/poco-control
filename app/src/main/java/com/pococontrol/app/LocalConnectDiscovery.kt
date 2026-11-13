package com.pococontrol.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface
import java.util.ArrayDeque

/** Só aceita anúncios do próprio aparelho. Faz segunda busca pelo resolvedor padrão do Android. */
class LocalConnectDiscovery(private val context: Context, private val done: (String?, Int?, String?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val queue = ArrayDeque<Pair<NsdServiceInfo, Int>>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var running = false
    private var resolving = false
    private var generation = 0
    private var phase = 0
    private val timeout = Runnable { nextPhaseOrFinish() }

    fun start() {
        if (running) return
        running = true
        try {
            lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("poco-adb-connect-discovery").apply { setReferenceCounted(false); acquire() }
            beginPhase()
        } catch (_: Exception) { finish(null, null, "C02: Não foi possível iniciar a busca. Confira o Wi-Fi e permita o funcionamento do app em segundo plano.") }
    }
    private fun beginPhase() {
        if (!running) return
        generation++
        val thisGeneration = generation
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { handler.post { if (!running || generation != thisGeneration) try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {} } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { if (running && generation == thisGeneration) nextPhaseOrFinish() } }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                if (running && generation == thisGeneration && info.serviceType.contains("_adb-tls-connect")) { queue.add(info to 0); resolveNext() }
            } }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        try {
            if (phase == 0) discoverOnWifi(context, nsd, "_adb-tls-connect._tcp", l)
            else nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            handler.postDelayed(timeout, if (phase == 0) 12000 else 18000)
        } catch (_: Exception) { nextPhaseOrFinish() }
    }
    private fun nextPhaseOrFinish() {
        if (!running) return
        handler.removeCallbacks(timeout)
        generation++
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }; listener = null
        queue.clear(); resolving = false
        if (phase == 0) { phase = 1; handler.postDelayed({ if (running) beginPhase() }, 1200) }
        else finish(null, null, "C01: O Android não anunciou a porta de conexão. Confira a depuração sem fio e o Wi-Fi; tente de novo ou use a porta da tela principal no modo manual.")
    }
    private fun resolveNext() {
        if (!running || resolving || queue.isEmpty()) return
        val (info, attempt) = queue.removeFirst(); resolving = true
        val thisGeneration = generation
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { handler.post {
                    if (!running || generation != thisGeneration) return@post
                    resolving = false
                    if (attempt < 2) handler.postDelayed({ if (running && generation == thisGeneration) { queue.add(info to attempt + 1); resolveNext() } }, 700)
                    resolveNext()
                } }
                override fun onServiceResolved(info: NsdServiceInfo) { handler.post {
                    if (!running || generation != thisGeneration) return@post
                    resolving = false
                    val host = ownAdvertisedIpv4(info)
                    if (host != null && info.port in 1..65535) finish(host, info.port, null) else resolveNext()
                } }
            })
        } catch (_: Exception) {
            resolving = false
            if (attempt < 2) handler.postDelayed({ if (running && generation == thisGeneration) { queue.add(info to attempt + 1); resolveNext() } }, 700)
            handler.postDelayed({ resolveNext() }, 250)
        }
    }
    private fun finish(host: String?, port: Int?, reason: String?) { if (!running) return; stop(); done(host, port, reason) }
    fun stop() {
        running = false; generation++; handler.removeCallbacks(timeout); queue.clear(); resolving = false
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }; listener = null
        lock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }; lock = null
    }
}
