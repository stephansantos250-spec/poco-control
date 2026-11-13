package com.pococontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import java.net.ConnectException
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

/** Busca local do serviço ADB: a porta é anunciada pelo Android, não criada pelo app. */
object PairingNotification {
    const val CHANNEL = "wifi_pairing"
    const val NOTICE_ID = 7812
    const val RESULT_ID = NOTICE_ID
    const val INPUT_KEY = "pair_input"
    const val START = "com.pococontrol.app.PAIR_START"
    const val REPLY = "com.pococontrol.app.PAIR_REPLY"
    const val STOP = "com.pococontrol.app.PAIR_STOP"
    const val EXTRA_START_CONNECTION = "start_wifi_connection"
    const val EXTRA_START_TOKEN = "start_wifi_token"
    const val TOKEN_PREFS = "wifi_pairing_action"
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pareamento Wi-Fi", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); setShowBadge(false) })
    }
    fun base(context: Context): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_poco)
        .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_realista))
        .setColor(AndroidColor.rgb(89, 164, 230)).setCategory(NotificationCompat.CATEGORY_STATUS).setOnlyAlertOnce(true)
    private fun pending(context: Context, action: String, code: Int, mutable: Boolean, host: String = "", port: Int = 0): PendingIntent {
        val intent = Intent(context, PairingService::class.java).setAction(action).putExtra("host", host).putExtra("port", port)
        return PendingIntent.getForegroundService(context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
    }
    fun searching(context: Context): Notification = base(context).setContentTitle("Procurando serviço de pareamento")
        .setContentText("Abra Depuração sem fio e toque em Parear dispositivo com código.")
        .setStyle(NotificationCompat.BigTextStyle().bigText("Ative a depuração sem fio. Abra ‘Parear dispositivo com código’ e aguarde esta notificação oferecer a opção de digitar o código."))
        .setOngoing(true).addAction(0, "Cancelar", pending(context, STOP, 102, false)).build()
    fun input(context: Context, host: String, port: Int, invalid: Boolean = false): Notification {
        val input = RemoteInput.Builder(INPUT_KEY).setLabel("Código de pareamento de 6 dígitos").build()
        val action = NotificationCompat.Action.Builder(0, "Informar código", pending(context, REPLY, 103, true, host, port))
            .addRemoteInput(input).setAllowGeneratedReplies(false).build()
        return base(context).setContentTitle("Serviço de pareamento encontrado")
            .setContentText(if (invalid) "Use exatamente os 6 dígitos exibidos pelo Android." else "Digite o código de 6 dígitos enquanto a janela do Android estiver aberta.")
            .setOngoing(true).addAction(action).addAction(0, "Cancelar", pending(context, STOP, 102, false)).build()
    }
    fun working(context: Context): Notification = base(context).setContentTitle("Pareando Wi-Fi local")
        .setContentText("Aguarde a confirmação do Android.").setOngoing(true).build()
    @Suppress("MissingPermission")
    fun result(context: Context, title: String, text: String, canStartConnection: Boolean = false) {
        channel(context)
        val open = PendingIntent.getActivity(context, 101, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = base(context).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        if (!canStartConnection) {
            builder.addAction(0, "Tentar novamente", pending(context, START, 105, false))
        }
        if (canStartConnection) {
            // The launcher activity is exported; a one-use random token prevents other apps
            // from triggering a connection by forging the notification's launch intent.
            val token = java.util.UUID.randomUUID().toString()
            if (context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE).edit().putString("token", token).commit()) {
                val launch = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_START_CONNECTION, true).putExtra(EXTRA_START_TOKEN, token)
                val start = PendingIntent.getActivity(context, 104, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0, "Começar", start)
            } else {
                builder.setContentText("Abra o POCO CONTROL e toque em Iniciar conexão.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("Abra o POCO CONTROL e toque em Iniciar conexão."))
            }
        }
        NotificationManagerCompat.from(context).notify(RESULT_ID, builder.build())
    }
}

/** Serviço em primeiro plano mantém a descoberta enquanto a tela do Android está aberta. */
class PairingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nsd: NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var running = false
    private var resolving = false
    private var working = false
    private var foundName: String? = null
    private var foundHost: String? = null
    private var foundPort: Int = 0
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val searchTimeout = Runnable { finishResult("Serviço não encontrado", "Confira se o Wi-Fi e a depuração sem fio estão ativos, abra a tela de código e permita que o app funcione em segundo plano. Você também pode tentar o pareamento manual.") }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); nsd = getSystemService(NsdManager::class.java); PairingNotification.channel(this); NotificationManagerCompat.from(this).cancel(7811); NotificationManagerCompat.from(this).cancel(7813) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == PairingNotification.STOP) { stopSearch(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (action != PairingNotification.START && action != PairingNotification.REPLY) { stopSelf(); return START_NOT_STICKY }
        val currentHost = foundHost
        foreground(when {
            action == PairingNotification.REPLY || working -> PairingNotification.working(this)
            currentHost != null && foundPort > 0 -> PairingNotification.input(this, currentHost, foundPort)
            else -> PairingNotification.searching(this)
        })
        if (action == PairingNotification.START) {
            NotificationManagerCompat.from(this).cancel(7813)
            if (!running && !working) startSearch()
        } else if (!working) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotification.INPUT_KEY)?.toString()?.trim() ?: ""
            val host = intent?.getStringExtra("host") ?: ""
            val port = intent?.getIntExtra("port", 0) ?: 0
            if (!running || foundHost == null || host != foundHost || port != foundPort) {
                finishResult("Código expirado", "A tela de pareamento foi fechada ou a porta mudou. Toque em Tentar novamente e gere outro código.")
            } else if (!isPairingCode(code)) {
                update(PairingNotification.input(this, host, port, invalid = true))
            } else pair(host, port, code)
        }
        return START_NOT_STICKY
    }
    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(PairingNotification.NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(PairingNotification.NOTICE_ID, notification)
    }
    @Suppress("MissingPermission")
    private fun update(notification: Notification) { NotificationManagerCompat.from(this).notify(PairingNotification.NOTICE_ID, notification) }
    private fun startSearch() {
        if (Build.VERSION.SDK_INT < 30) { finishResult("Android incompatível", "A depuração sem fio precisa do Android 11 ou superior."); return }
        running = true; foundName = null
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("poco-adb-pair-discovery").apply { setReferenceCounted(false); acquire() }
            val current = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) { handler.post { if (!running) try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {} } }
                override fun onStartDiscoveryFailed(type: String, error: Int) { handler.post { if (running) finishResult("Busca indisponível", "Não foi possível buscar a porta automaticamente. Tente novamente ou use o pareamento manual.") } }
                override fun onDiscoveryStopped(type: String) {}
                override fun onStopDiscoveryFailed(type: String, error: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { handler.post { if (running && info.serviceType.contains("_adb-tls-pairing")) { queue.add(info); resolveNext() } } }
                override fun onServiceLost(info: NsdServiceInfo) { handler.post { if (running && info.serviceName == foundName) { foundName = null; foundHost = null; foundPort = 0; update(PairingNotification.searching(this@PairingService)) } } }
            }
            listener = current
            discoverOnWifi(this, nsd, "_adb-tls-pairing._tcp", current)
            handler.postDelayed(searchTimeout, 120000)
        } catch (_: Exception) { finishResult("Busca indisponível", "Não foi possível iniciar a busca local. Confira o Wi-Fi ou tente o pareamento manual.") }
    }
    private fun resolveNext() {
        if (!running || resolving || queue.isEmpty()) return
        val info = queue.removeFirst(); resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { handler.post { resolving = false; resolveNext() } }
                override fun onServiceResolved(info: NsdServiceInfo) { handler.post {
                    resolving = false
                    val host = ownAdvertisedIpv4(info)
                    if (running && info.port in 1..65535 && host != null) {
                        foundName = info.serviceName; foundHost = host; foundPort = info.port
                        update(PairingNotification.input(this@PairingService, host, info.port))
                    }
                    resolveNext()
                } }
            })
        } catch (_: Exception) { resolving = false; handler.postDelayed({ resolveNext() }, 250) }
    }
    private fun stopSearch() {
        running = false; handler.removeCallbacks(searchTimeout); queue.clear(); foundName = null; foundHost = null; foundPort = 0
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }; listener = null
        lock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }; lock = null
    }
    private fun pair(host: String, port: Int, code: String) {
        working = true; stopSearch(); update(PairingNotification.working(this))
        val finished = AtomicBoolean(false)
        val timeout = Runnable { if (finished.compareAndSet(false, true)) finishResult("Pareamento não confirmado", "A tentativa demorou. Confira no Android se o pareamento foi concluído antes de repetir.") }
        handler.postDelayed(timeout, 30000)
        thread(isDaemon = true, name = "notification-adb-pair") {
            var failure: Throwable? = null
            try {
                LocalWifiAdb.init(applicationContext)
                LocalWifiAdb.pairLocal(host, port, code)
            } catch (e: Throwable) { if (e is VirtualMachineError || e is ThreadDeath) throw e; failure = e }
            handler.post {
                if (!finished.compareAndSet(false, true)) return@post
                handler.removeCallbacks(timeout)
                val error = failure
                val detail = if (error == null) "Agora toque em Começar para conectar o POCO CONTROL via depuração sem fio."
                    else pairingFailure(error).message + "\n" + safeErrorDetail(error)
                finishResult(if (error == null) "Pareamento concluído" else "Não foi possível parear", detail, error == null)
            }
        }
    }
    private fun finishResult(title: String, text: String, canStartConnection: Boolean = false) { stopSearch(); working = false; stopForeground(STOP_FOREGROUND_DETACH); PairingNotification.result(this, title, text, canStartConnection); stopSelf() }
    override fun onDestroy() { stopSearch(); super.onDestroy() }
}
