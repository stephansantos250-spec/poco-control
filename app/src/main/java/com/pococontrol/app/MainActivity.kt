package com.pococontrol.app

import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import rikka.shizuku.ShizukuRemoteProcess

private val Background = Color(0xFF06142D)
private val Panel = Color(0xFF10264B)
private val TextMuted = Color(0xFFC5DAF7)
private val BrightBlue = Color(0xFF42B5FF)
private val BrightGreen = Color(0xFF4DE48D)
private val Amber = Color(0xFFFFD47B)

data class DeviceStatus(val developer: Boolean?, val adb: Boolean?, val wifiAdb: Boolean?, val accessibility: Boolean?, val defaultVpn: Boolean?)
data class QuickApp(val packageName: String, val label: String)
data class AccessService(val component: String, val app: String, val service: String)

private val targetGroups = listOf("SD Maid (AppCleaner) - ADB/Root Toolbox", "Shizuku-Next", "TogglDev", "Tooler")
private fun accessTargetGroup(item: AccessService): String? {
    // Android pode mostrar o nome do serviço, e não o do app, na tela de acessibilidade.
    // Uma correspondência exata evita desligar outros serviços do SD Maid SE.
    fun matches(vararg names: String) = names.any { name ->
        item.app.trim().equals(name, true) || item.service.trim().equals(name, true)
    }
    return when {
        matches("SD Maid (AppCleaner) - ADB/Root Toolbox", "SD Maid (AppCleaner) – ADB/Root Toolbox") -> targetGroups[0]
        item.component.startsWith("moe.shizuku.privileged.api/") || matches("Shizuku-Next") -> "Shizuku-Next"
        matches("TogglDev", "ToggleDev") -> "TogglDev"
        matches("Tooler") -> "Tooler"
        else -> null
    }
}
private fun targetAccessServices(services: List<AccessService>?): List<AccessService> =
    services?.filter { accessTargetGroup(it) != null } ?: emptyList()

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf(DeviceStatus(null, null, null, null, null))
    private var message by mutableStateOf("Verifique a conexão antes de usar os modos automáticos.")
    private var shizukuReady by mutableStateOf(false)
    private var connectionDescription by mutableStateOf("Verificação pendente")
    private var accessibilityServices by mutableStateOf<List<AccessService>?>(null)
    private var accessDialog by mutableStateOf(false)
    private var selectedTab by mutableIntStateOf(0)
    private var appsInventory by mutableStateOf<List<InstalledApp>?>(null)
    private var inventoryLoading by mutableStateOf(false)
    private var inventoryMessage by mutableStateOf("")
    private var inventoryGeneration = 0
    private var availableServices by mutableStateOf<List<AvailableService>?>(null)
    private var servicesLoading by mutableStateOf(false)
    private var serviceAction by mutableStateOf<ServiceAction?>(null)
    private var notificationsEnabled by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNotificationStatus()
        if (!notificationsEnabled) message = "Notificações não permitidas. Abra as configurações de notificações para revisar."
        else refreshAvailableServices()
    }
    private var settingsDialog by mutableStateOf(false)
    private var setupDialog by mutableStateOf(false)
    private var wifiDialog by mutableStateOf(false)
    private var connectDiscovery: LocalConnectDiscovery? = null
    private var wifiReady by mutableStateOf(false)
    private var pairingInFlight = false
    private var pairingWaiting = false
    private var wifiMessage by mutableStateOf("Pareie usando a tela Depuração sem fio do Android.")
    private var appearance by mutableStateOf(AppAppearance.LIQUID)
    private var lastTarget by mutableStateOf<List<String>?>(null)
    private var busy by mutableStateOf(false)
    private var confirmMode by mutableStateOf<String?>(null)
    private var shortcuts by mutableStateOf<List<QuickApp>>(emptyList())
    private var installedApps by mutableStateOf<List<QuickApp>>(emptyList())
    private var pickerOpen by mutableStateOf(false)
    private var loadingApps by mutableStateOf(false)
    private var loadError by mutableStateOf(false)
    private var pickerGeneration = 0
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    // Inventory scans never hold up privileged operations; repeated scans coalesce.
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var inventoryRescan = false
    private var servicesRescan = false
    private val prefs by lazy { getSharedPreferences("mode_snapshot", MODE_PRIVATE) }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (!isDestroyed) { InstalledIconCache.clear(); refreshAvailableServices(); if (selectedTab == 2) refreshInventory() } }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { if (!isDestroyed) updateConnection() } }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { if (!isDestroyed) updateConnection() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grant ->
        updateConnection()
        message = if (grant == PackageManager.PERMISSION_GRANTED) "Permissão concedida. Toque no modo desejado novamente." else "Permissão negada. Autorize o POCO CONTROL no Shizuku para usar os modos automáticos."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalWifiAdb.init(applicationContext)
        setupDialog = !prefs.getBoolean("connection_setup_seen", false)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        appearance = try { AppAppearance.valueOf(prefs.getString("appearance", AppAppearance.LIQUID.name) ?: AppAppearance.LIQUID.name) }
            catch (_: IllegalArgumentException) { AppAppearance.LIQUID }
        loadShortcuts()
        selectedTab = if (intent?.getBooleanExtra("open_accessibility_tab", false) == true) 1 else 0
        AccessibilityDiscovery.schedule(applicationContext)
        val packageChanges = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, packageChanges, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            if (selectedTab == 2) {
                InstalledAppsScreen(appearance, appsInventory, inventoryLoading, inventoryMessage,
                    onTab = { tab -> selectedTab = tab; if (tab == 1) refreshAvailableServices() },
                    onRefresh = { refreshInventory() }, onOpenAppInfo = { openAppInfo(it) },
                    onOpenAppNotifications = { openAppNotifications(it) },
                    onOpenAccessibility = { openInventorySettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                    onOpenNotificationAccess = { openInventorySettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
            } else PocoScreen(status, message, shizukuReady || wifiReady, busy, connectionDescription, lastTarget, accessibilityServices,
                selectedTab = selectedTab, onTab = { tab -> selectedTab = tab; if (tab == 1) refreshAvailableServices(); if (tab == 2) refreshInventory() },
                availableServices = availableServices, servicesLoading = servicesLoading, serviceAction = serviceAction,
                notificationsEnabled = notificationsEnabled, onRequestNotifications = { requestNotifications() },
                onNotificationSettings = { openNotificationSettings() }, onRefreshServices = { refreshAvailableServices() },
                onServiceAction = { item, enable -> serviceAction = ServiceAction(item, enable) },
                onDismissServiceAction = { serviceAction = null },
                onConfirmServiceAction = { action -> serviceAction = null; runServiceAction(action) },
                appearance = appearance, settingsDialog = settingsDialog,
                onShowSettings = { settingsDialog = true }, onCloseSettings = { settingsDialog = false },
                onAppearance = { selected -> appearance = selected; prefs.edit().putString("appearance", selected.name).apply() },
                onBank = { refreshStatus(); confirmMode = "wifi" }, onPoco = { refreshStatus(); confirmMode = "poco" }, onNormal = { confirmMode = "normal" },
                onRefresh = { refreshStatus(); updateConnection() },
                onTest = { runOperation("teste") }, onWifiSetup = { wifiDialog = true }, onSetup = { setupDialog = true },
                onSettings = { openSettings(it) }, onLaunch = { pkg, label -> launchApp(pkg, label) },
                shortcuts = shortcuts, installedApps = installedApps, pickerOpen = pickerOpen,
                loadingApps = loadingApps, loadError = loadError,
                onShowPicker = { loadInstalledApps() }, onClosePicker = { closePicker() },
                onAddShortcut = { addShortcut(it) }, onRemoveShortcut = { removeShortcut(it) },
                accessDialog = accessDialog, onShowAccess = { refreshStatus(); accessDialog = true },
                onCloseAccess = { accessDialog = false }, onDisableAccess = { selected -> accessDialog = false; disableSelectedAccessibility(selected) },
                confirmMode = confirmMode, onDismiss = { confirmMode = null },
                onConfirm = { mode -> confirmMode = null; runOperation(mode, if (mode == "wifi" || mode == "poco") targetAccessServices(accessibilityServices) else emptyList()) })
            val dialogPalette = if (appearance == AppAppearance.LIQUID) liquidPalette else materialPalette
            CompositionLocalProvider(LocalAppPalette provides dialogPalette) {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = dialogPalette.accent, onPrimary = Color(0xFF071827),
                background = Color(0xFF0A0E14), surface = Color(0xFF121820),
                surfaceContainerHigh = Color(0xFF121820), surfaceVariant = Color(0xFF263345),
                onSurface = Color(0xFFF4F8FF), onSurfaceVariant = Color(0xFFCAD7E6),
                outline = Color(0xFF6B8299))) {
            if (setupDialog) ConnectionSetupDialog(
                onClose = { setupDialog = false; prefs.edit().putBoolean("connection_setup_seen", true).apply() }, status = message,
                onShizuku = { checkShizuku() }, onOpenShizuku = { launchApp("moe.shizuku.privileged.api", "Shizuku Next") },
                onWifi = { setupDialog = false; prefs.edit().putBoolean("connection_setup_seen", true).apply(); wifiDialog = true })
            if (wifiDialog) WifiConnectionDialog(wifiReady, wifiMessage, busy,
                onClose = { wifiDialog = false }, onAndroidSettings = { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                onPair = { host, port, code -> pairWifi(host, port, code) }, onPairNotice = { createPairNotice() },
                onConnect = { host, port -> connectWifi(host, port) }, onAutoConnect = { discoverConnection() },
                onDisconnect = { LocalWifiAdb.disconnect(); updateConnection(); wifiMessage = "Conexão encerrada. Para revogar o pareamento, remova o dispositivo nas configurações do Android." })
            }
            }
        }
        handleConnectionIntent(intent)
    }
    override fun onResume() { super.onResume(); refreshStatus(); updateConnection(); updateNotificationStatus(); refreshAvailableServices(); if (selectedTab == 2) refreshInventory() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_accessibility_tab", false)) { selectedTab = 1; refreshAvailableServices() }
        handleConnectionIntent(intent)
    }
    private fun handleConnectionIntent(input: Intent?) {
        if (input?.getBooleanExtra(PairingNotification.EXTRA_START_CONNECTION, false) != true) return
        val token = input.getStringExtra(PairingNotification.EXTRA_START_TOKEN)
        input.removeExtra(PairingNotification.EXTRA_START_CONNECTION)
        input.removeExtra(PairingNotification.EXTRA_START_TOKEN)
        val actionPrefs = getSharedPreferences(PairingNotification.TOKEN_PREFS, MODE_PRIVATE)
        val expected = actionPrefs.getString("token", null)
        if (token == null || expected == null || token != expected || !actionPrefs.edit().remove("token").commit()) return
        NotificationManagerCompat.from(this).cancel(PairingNotification.RESULT_ID)
        NotificationManagerCompat.from(this).cancel(7813)
        selectedTab = 0; setupDialog = false; wifiDialog = true
        main.post { if (!isDestroyed) discoverConnection() }
    }
    private fun refreshInventory() {
        if (inventoryLoading) { inventoryRescan = true; return }
        val generation = ++inventoryGeneration
        inventoryLoading = true
        inventoryMessage = ""
        scanExecutor.execute {
            val result = try { AppInventory.scan(applicationContext) } catch (_: Exception) { null }
            main.post { if (!isDestroyed && generation == inventoryGeneration) {
                appsInventory = result
                inventoryLoading = false
                if (result == null) inventoryMessage = "Não foi possível consultar os aplicativos. Abra as configurações do Android."
                if (inventoryRescan) { inventoryRescan = false; refreshInventory() }
            } }
        }
    }
    private fun launchInventoryIntent(intent: Intent) {
        inventoryMessage = ""
        try { startActivity(intent) } catch (_: Exception) { inventoryMessage = "Esta tela não está disponível neste aparelho. Abra as configurações do Android." }
    }
    private fun openAppInfo(pkg: String) {
        launchInventoryIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", pkg, null)))
    }
    private fun openAppNotifications(pkg: String) {
        launchInventoryIntent(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
    }
    private fun openInventorySettings(action: String) { launchInventoryIntent(Intent(action)) }
    private fun updateNotificationStatus() {
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    private fun openNotificationSettings() {
        try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        catch (_: Exception) { message = "Abra as configurações do aplicativo no Android para permitir notificações." }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openNotificationSettings()
    }
    private fun refreshAvailableServices() {
        if (servicesLoading) { servicesRescan = true; return }
        servicesLoading = true
        scanExecutor.execute {
            val result = try { AccessibilityDiscovery.scan(applicationContext).also { AccessibilityDiscovery.recordAndNotify(applicationContext, it) } }
                catch (_: Exception) { null }
            main.post { if (!isDestroyed) {
                availableServices = result; servicesLoading = false
                if (servicesRescan) { servicesRescan = false; refreshAvailableServices() }
            } }
        }
    }
    private fun runServiceAction(action: ServiceAction) {
        if (busy || !checkConnection()) return
        busy = true
        message = "Solicitando alteração do serviço ao Android…"
        executor.execute {
            val result = try { changeOneService(ShellSettings(), action) }
                catch (_: Exception) { "Conexão interrompida; não foi possível confirmar. Confira o serviço nas configurações do Android." }
            main.post { if (!isDestroyed) { busy = false; message = result; refreshStatus(); updateConnection(); refreshAvailableServices() } }
        }
    }
    private fun changeOneService(shell: ShellSettings, action: ServiceAction): String {
        val component = action.item.component
        if (AccessibilityDiscovery.scan(applicationContext).none { it.component == component })
            return "O serviço não está mais disponível. Atualize a lista e confira nas configurações."
        val raw = shell.readAccessibility()
        val before = accessibilityComponents(raw) ?: return "Não foi possível ler a lista de serviços; nada foi alterado."
        val beforeSet = before.mapNotNull { canonicalComponent(it) }.toSet()
        val requested = if (action.enable) beforeSet + component else beforeSet - component
        if (shell.readAccessibility() != raw) return "A lista mudou durante a operação; nada foi alterado. Atualize e tente novamente."
        val newList = if (action.enable) (before + if (component in beforeSet) emptyList() else listOf(component))
                      else before.filterNot { canonicalComponent(it) == component }
        val written = if (requested == beforeSet) "" else shell.writeAccessibility(newList.joinToString(":"))
        if (written.startsWith("ERROR:")) return "Alteração não confirmada. Confira no Android; pode ser necessária ativação manual. Detalhe: ${written.take(140)}"
        val after = accessibilityComponents(shell.readAccessibility())?.mapNotNull { canonicalComponent(it) }?.toSet()
        if (after != requested) return "O Android não confirmou a alteração. Confira o serviço nas configurações."
        // An explicit per-service choice supersedes that service's pending mode snapshot.
        val saved = savedAccessibility()
        if (saved != null && component in saved) {
            val remaining = saved - component
            val edit = prefs.edit()
            if (remaining.isEmpty()) edit.remove("access_snapshot") else edit.putString("access_snapshot", JSONArray(remaining.toList()).toString())
            if (!edit.commit()) return "Lista alterada, mas o registro do modo normal não pôde ser atualizado. Confira antes de restaurar."
        } else if (saved == null) return "Lista alterada, mas o registro do modo normal é inválido. Confira antes de restaurar."
        return if (action.enable) "Serviço incluído na lista do Android. Confira se está funcionando; o sistema pode exigir ativação manual." else "Serviço removido da lista do Android. Confira nas configurações."
    }
    override fun onDestroy() {
        connectDiscovery?.stop(); connectDiscovery = null
        unregisterReceiver(packageReceiver)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        executor.shutdown()
        scanExecutor.shutdown()
        super.onDestroy()
    }
    private fun updateConnection() {
        wifiReady = LocalWifiAdb.connected
        try {
            val binder = Shizuku.pingBinder()
            shizukuReady = binder && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            connectionDescription = when {
                !binder -> "Serviço não detectado pelo aplicativo"
                shizukuReady -> "Conectado e autorizado"
                else -> "Conectado, mas sem autorização"
            }
        } catch (_: Exception) {
            shizukuReady = false
            connectionDescription = "Não foi possível verificar a conexão"
        }
    }
    private fun refreshStatus() {
        fun g(key: String): Boolean? = try {
            when (Settings.Global.getInt(contentResolver, key)) { 0 -> false; 1 -> true; else -> null }
        } catch (_: Exception) { null }
        val a = try { when (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)) { 0 -> false; 1 -> true; else -> null } } catch (_: Exception) { null }
        // Only the default network can be checked here. Null means no reliable result.
        val vpn = try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = if (network == null) null else cm.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) { null }
        status = DeviceStatus(g(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), g(Settings.Global.ADB_ENABLED), g("adb_wifi_enabled"), a, vpn)
        accessibilityServices = try {
            val manager = getSystemService(AccessibilityManager::class.java)
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.mapNotNull { info ->
                val resolved = info.resolveInfo ?: return@mapNotNull null
                val serviceInfo = resolved.serviceInfo ?: return@mapNotNull null
                val pkg = serviceInfo.packageName ?: return@mapNotNull null
                val cls = serviceInfo.name ?: return@mapNotNull null
                val appName = try { serviceInfo.applicationInfo.loadLabel(packageManager)?.toString() ?: pkg } catch (_: Exception) { pkg }
                val label = try { resolved.loadLabel(packageManager)?.toString() ?: cls } catch (_: Exception) { cls }
                AccessService(ComponentName(pkg, cls).flattenToString(), appName, label)
            }?.distinctBy { it.component }?.sortedBy { it.app.lowercase() }
        } catch (_: Exception) { null }
    }
    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: ActivityNotFoundException) { message = "Essa tela não está disponível neste aparelho." } catch (_: Exception) { message = "O Android não permitiu abrir essa tela." }
    }
    private fun launchApp(pkg: String, label: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) message = "$label não foi encontrado. Confira a instalação pela loja oficial." else startActivity(intent)
        } catch (_: Exception) { message = "Não foi possível abrir $label." }
    }
    private fun loadShortcuts() {
        val store = getSharedPreferences("quick_shortcuts", MODE_PRIVATE)
        val raw = store.getString("apps", null)
        if (raw == null) {
            // Do not display broken guessed package names on a fresh install.
            shortcuts = listOf(QuickApp("br.gov.caixa.tem", "Caixa Tem"), QuickApp("br.gov.br", "Gov.br"),
                QuickApp("br.com.bb.android", "Banco do Brasil"), QuickApp("com.bradesco", "Bradesco"))
                .filter { try { packageManager.getLaunchIntentForPackage(it.packageName) != null } catch (_: Exception) { false } }
            return
        }
        shortcuts = try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }
                .map { QuickApp(it.getString("package"), it.getString("label")) }
                .distinctBy { it.packageName }
        } catch (_: Exception) { emptyList() }
    }
    private fun saveShortcuts() {
        val array = JSONArray()
        shortcuts.forEach { array.put(JSONObject().put("package", it.packageName).put("label", it.label)) }
        getSharedPreferences("quick_shortcuts", MODE_PRIVATE).edit().putString("apps", array.toString()).apply()
    }
    private fun addShortcut(app: QuickApp) {
        if (shortcuts.none { it.packageName == app.packageName }) {
            shortcuts = shortcuts + app
            saveShortcuts()
        }
        closePicker()
    }
    private fun closePicker() {
        pickerOpen = false; loadingApps = false; pickerGeneration++
    }
    private fun removeShortcut(app: QuickApp) {
        shortcuts = shortcuts.filterNot { it.packageName == app.packageName }
        saveShortcuts()
    }
    private fun loadInstalledApps() {
        pickerOpen = true; loadingApps = true; loadError = false; installedApps = emptyList()
        val generation = ++pickerGeneration
        thread(name = "quick-app-picker") {
            try {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val results = packageManager.queryIntentActivities(launcher, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map { QuickApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                    .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
                main.post { if (!isDestroyed && pickerOpen && generation == pickerGeneration) { installedApps = results; loadingApps = false } }
            } catch (_: Exception) {
                main.post { if (!isDestroyed && pickerOpen && generation == pickerGeneration) { loadError = true; loadingApps = false } }
            }
        }
    }
    private fun canonicalComponent(value: String): String? =
        ComponentName.unflattenFromString(value)?.flattenToString()

    private fun accessibilityComponents(value: String): List<String>? {
        if (value.startsWith("ERROR:")) return null
        if (value == "null" || value.isEmpty()) return emptyList()
        val parts = value.split(":")
        return if (parts.any { canonicalComponent(it) == null }) null else parts
    }

    // null means the saved list is corrupt, not an empty list.
    private fun savedAccessibility(): Set<String>? {
        if (!prefs.contains("access_snapshot")) return emptySet()
        return try {
            val array = JSONArray(prefs.getString("access_snapshot", null))
            val values = (0 until array.length()).map { canonicalComponent(array.getString(it)) }
            if (values.any { it == null }) null else values.filterNotNull().toSet()
        } catch (_: Exception) { null }
    }

    private fun changeAccessibility(shell: ShellSettings, selected: List<AccessService>): String {
        if (selected.isEmpty()) return "Acessibilidade: nenhum dos serviços selecionados foi identificado. Confira-os manualmente nas configurações."
        val targets = selected.map { it.component }.toSet()
        val raw = shell.readAccessibility()
        val before = accessibilityComponents(raw)
            ?: return "Acessibilidade: lista indisponível; não foi solicitada alteração. Confira manualmente."
        if (targets.any { target -> before.none { canonicalComponent(it) == target } })
            return "Acessibilidade: a lista mudou; nenhuma alteração foi solicitada. Atualize e confira manualmente."
        if (shell.readAccessibility() != raw) return "Acessibilidade: a lista mudou durante a verificação; nada foi alterado. Confira manualmente."
        val saved = savedAccessibility()
            ?: return "Acessibilidade: registro de restauração inválido; nenhuma alteração foi solicitada. Confira manualmente."
        // Persist before writing: a crash or lost binder must not lose the restoration list.
        if (!prefs.edit().putString("access_snapshot", JSONArray((saved + targets).toList()).toString()).commit())
            return "Acessibilidade: não foi possível salvar os serviços para restauração; nada foi alterado."
        val remaining = before.filterNot { canonicalComponent(it) in targets }.joinToString(":")
        val written = shell.writeAccessibility(remaining)
        if (written.startsWith("ERROR:")) return "Acessibilidade: alteração não confirmada; a lista para restauração foi mantida. Confira nas configurações. Detalhe: ${written.take(180)}"
        val after = accessibilityComponents(shell.readAccessibility())
        if (after == null || after.mapNotNull { canonicalComponent(it) }.toSet() != remaining.split(":").filter { it.isNotEmpty() }.mapNotNull { canonicalComponent(it) }.toSet())
            return "Acessibilidade: resultado não confirmado; a lista para restauração foi mantida. Confira nas configurações."
        val found = selected.mapNotNull { accessTargetGroup(it) }.toSet()
        val missing = targetGroups.filterNot { it in found }
        return "Acessibilidade: serviços removidos da lista e registrados para tentativa de restauração. Confira no Android." +
            if (missing.isNotEmpty()) " Não identificados: ${missing.joinToString()}; confira-os manualmente." else ""
    }

    private fun restoreAccessibility(shell: ShellSettings): String {
        val saved = savedAccessibility()
            ?: return "Acessibilidade: registro inválido. Reative os serviços manualmente nas configurações."
        if (saved.isEmpty()) return "Acessibilidade: não há serviços salvos para restaurar. Na versão 2.3 eles não eram salvos; reative-os manualmente."
        val raw = shell.readAccessibility()
        val before = accessibilityComponents(raw)
            ?: return "Acessibilidade: não foi possível ler a lista. O registro foi mantido; confira nas configurações."
        if (shell.readAccessibility() != raw)
            return "Acessibilidade: a lista mudou; nada foi alterado. O registro foi mantido para nova tentativa."
        val present = before.mapNotNull { canonicalComponent(it) }.toSet()
        val merged = (before + (saved - present).sorted()).joinToString(":")
        if (merged != raw && !(before.isEmpty() && saved.isEmpty())) {
            val result = shell.writeAccessibility(merged)
            if (result.startsWith("ERROR:")) return "Acessibilidade: restauração não confirmada; registro mantido. Confira no Android. Detalhe: ${result.take(180)}"
        }
        val after = accessibilityComponents(shell.readAccessibility())
        val afterSet = after?.mapNotNull { canonicalComponent(it) }?.toSet()
        if (afterSet == null || !afterSet.containsAll(present + saved))
            return "Acessibilidade: lista não confirmada; registro mantido. Confira os serviços no Android."
        // A settings entry alone does not prove that Android actually activated the service.
        val enabled = try {
            getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.mapNotNull { it.resolveInfo?.serviceInfo }
                ?.map { ComponentName(it.packageName, it.name).flattenToString() }?.toSet()
        } catch (_: Exception) { null }
        if (enabled == null || !enabled.containsAll(saved))
            return "Acessibilidade: os valores foram solicitados, mas nem todos os serviços foram confirmados ativos. Registro mantido; confira e reative manualmente se necessário."
        if (!prefs.edit().remove("access_snapshot").commit())
            return "Acessibilidade: serviços confirmados ativos, mas não foi possível limpar o registro. Confira antes de repetir."
        return "Acessibilidade: serviços salvos confirmados ativos. Confira também nas configurações do Android."
    }
    private fun checkConnection(): Boolean = if (LocalWifiAdb.connected) true else checkShizuku()
    private fun createPairNotice() {
        updateNotificationStatus()
        if (!notificationsEnabled) {
            wifiMessage = "Permita as notificações e toque novamente em Iniciar pareamento."
            requestNotifications()
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, PairingService::class.java).setAction(PairingNotification.START))
            wifiMessage = "Busca iniciada. Abra a tela de código no Android e informe apenas o código na notificação quando o serviço for encontrado."
        } catch (_: Exception) { wifiMessage = "Não foi possível criar a notificação. Confira as permissões de notificação." }
    }
    private fun pairWifi(host: String, port: String, code: String) {
        if (busy) return
        if (pairingInFlight) { wifiMessage = "A tentativa anterior ainda está em andamento; confira no Android antes de tentar novamente."; return }
        pairingInFlight = true; pairingWaiting = true
        busy = true; wifiMessage = "Tentando parear… mantenha a tela de código aberta."
        main.postDelayed({ if (pairingWaiting && !isDestroyed) { pairingWaiting = false; busy = false; wifiMessage = "O pareamento demorou. A tentativa ainda pode estar em andamento; confira no Android." } }, 30000)
        thread(isDaemon = true, name = "local-adb-pair") {
            val result = try { LocalWifiAdb.pairLocal(host.trim(), port.toIntOrNull() ?: 0, code); "Pareamento concluído. Agora informe a porta de conexão exibida na tela principal de Depuração sem fio." }
                catch (e: Throwable) { if (e is VirtualMachineError || e is ThreadDeath) throw e; "Pareamento não confirmado. " + pairingFailure(e).message + "\n" + safeErrorDetail(e) }
            main.post { pairingInFlight = false; if (!isDestroyed) { if (pairingWaiting) busy = false; pairingWaiting = false; wifiMessage = result; updateConnection() } }
        }
    }
    private fun discoverConnection() {
        if (busy) { wifiMessage = "Aguarde a operação em andamento antes de conectar."; return }
        busy = true; wifiMessage = "Procurando a porta de conexão do próprio celular…"
        connectDiscovery?.stop()
        connectDiscovery = LocalConnectDiscovery(applicationContext) { host, port, reason ->
            connectDiscovery = null
            if (!isDestroyed) {
                busy = false
                if (host != null && port != null) connectWifi(host, port.toString())
                else wifiMessage = reason ?: "C01: Não foi possível encontrar a porta de conexão. Confira o Wi-Fi e a depuração sem fio ou informe manualmente a porta mostrada na tela principal de Depuração sem fio."
            }
        }.also { it.start() }
    }
    private fun connectWifi(host: String, port: String) {
        if (busy) return
        busy = true; wifiMessage = "Conectando…"
        executor.execute {
            val result = try {
                val address = host.trim(); val number = port.toIntOrNull() ?: 0
                LocalWifiAdb.validateEndpoint(address, number)
                try { LocalWifiAdb.connect("127.0.0.1", number) }
                catch (e: java.net.ConnectException) { if (address == "127.0.0.1") throw e; LocalWifiAdb.connect(address, number) }
                "Conexão ADB local estabelecida. O Shizuku não precisa permanecer aberto enquanto esta conexão estiver ativa."
            }
                catch (e: Throwable) { if (e is VirtualMachineError || e is ThreadDeath) throw e; "Conexão não confirmada. " + pairingFailure(e).message + " Confira também a porta de conexão (não a de pareamento).\n" + safeErrorDetail(e) }
            main.post { if (!isDestroyed) { busy = false; wifiMessage = result; updateConnection() } }
        }
    }
    private fun checkShizuku(): Boolean {
        try {
            if (!Shizuku.pingBinder()) { message = "Shizuku-Next não está conectado. Inicie o serviço no Shizuku-Next, autorize Poco Control e tente de novo. Se necessário, altere os serviços nas configurações do Android."; return false }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) message = "Autorize o POCO CONTROL no Shizuku e tente novamente."
                else { Shizuku.requestPermission(31); message = "Autorize o Shizuku e tente a ação novamente." }
                return false
            }
            return true
        } catch (_: Exception) { message = "Não foi possível acessar o Shizuku. Confira as configurações do Android."; return false }
    }
    private fun disableSelectedAccessibility(selected: List<AccessService>) {
        if (busy || selected.isEmpty() || !checkConnection()) return
        busy = true
        message = "Tentando desativar somente os serviços escolhidos..."
        executor.execute {
            val result = try { changeAccessibility(ShellSettings(), selected) }
                catch (_: Exception) { "Acessibilidade: conexão interrompida; resultado incerto. Confira cada serviço nas configurações." }
            main.post { if (!isDestroyed) { busy = false; message = result; refreshStatus(); updateConnection() } }
        }
    }
    private fun runOperation(mode: String, accessTargets: List<AccessService> = emptyList()) {
        if (busy || !checkConnection()) return
        if (mode == "normal" && !prefs.contains("snapshot") && !prefs.contains("access_snapshot")) { message = "Não há configuração anterior salva. Na versão 2.3 os serviços de acessibilidade não eram salvos; reative-os nas configurações do Android."; return }
        if (mode == "wifi") lastTarget = listOf("1", "0", "1")
        else if (mode == "poco") lastTarget = listOf("0", "0", "0")
        else if (mode == "normal") lastTarget = if (prefs.contains("snapshot")) (prefs.getString("snapshot", "") ?: "").split(",") else null
        busy = true
        message = if (mode == "teste") "Testando a leitura, sem alterar configurações…" else "Solicitando alterações ao Android…"
        val preferShizuku = mode == "poco" && shizukuReady
        executor.execute {
            val result = try {
                val shell = ShellSettings(preferShizuku)
                if (mode == "teste") {
                    val values = shell.readAll().split(",").map { it.trim() }
                    if (values.size == 3 && values.all { it in setOf("0", "1", "null") })
                        "Teste de leitura concluído: a conexão respondeu. Nenhuma configuração foi alterada. A permissão de escrita ainda não foi testada."
                    else "A conexão respondeu, mas não foi possível ler todas as opções. Nada foi alterado."
                } else if (mode == "wifi" || mode == "poco") {
                    val before = shell.readAll().split(",").map { it.trim() }
                    if (before.size != 3 || before.any { it !in setOf("0", "1", "null") }) {
                        "Não foi possível ler as opções. Nenhuma alteração foi solicitada."
                    } else if (!ModePolicy.wifiPreflight(mode, before)) {
                        "Ative manualmente as opções de desenvolvedor e a depuração sem fio no Android antes de usar este modo. Nenhuma alteração foi solicitada."
                    } else if (!prefs.contains("snapshot") && !prefs.edit().putString("snapshot", before.joinToString(",")).commit()) {
                        "Não foi possível salvar os valores anteriores. Nenhuma alteração foi solicitada."
                    } else {
                        val accessibilityResult = try { changeAccessibility(shell, accessTargets) }
                            catch (_: Exception) { "Acessibilidade: conexão interrompida; resultado incerto. Confira cada serviço nas configurações." }
                        val settingsResult = try { executeMode(shell, mode) }
                            catch (_: Exception) { "Depuração: conexão interrompida ou operação incompleta. Confira as opções no Android." }
                        accessibilityResult + "\n\n" + settingsResult
                    }
                } else {
                    // Restore services first: restoring a disabled ADB state can stop Shizuku.
                    val accessResult = try { restoreAccessibility(shell) }
                        catch (_: Exception) { "Acessibilidade: conexão interrompida; registro mantido. Confira nas configurações." }
                    val settingsResult = if (prefs.contains("snapshot")) try { executeMode(shell, "normal") }
                        catch (_: Exception) { "Desenvolvedor e depuração: conexão interrompida; registro mantido. Confira no Android." }
                    else "Desenvolvedor e depuração: nenhum estado anterior salvo."
                    accessResult + "\n\n" + settingsResult
                }
            } catch (e: Exception) {
                if (mode == "teste") "Falha no teste de leitura. Confira a conexão Wi-Fi ou o Shizuku autorizado. Nada foi alterado."
                else "A operação foi interrompida; algumas opções podem ter mudado. Confira o estado no Android e reinicie o Shizuku Next se necessário."
            }
            main.post { if (!isDestroyed) { busy = false; message = result; refreshStatus(); updateConnection() } }
        }
    }
    private fun executeMode(service: ShellSettings, mode: String): String {
        val changing = mode == "wifi" || mode == "poco"
        val valid = setOf("0", "1", "null")
        fun parsed(s: String) = s.split(",").map { it.trim() }
        val before = parsed(service.readAll())
        if (before.size != 3 || before.any { it !in valid }) return "Não foi possível ler as três opções. Nenhuma alteração foi solicitada."
        if (changing && !prefs.contains("snapshot") && !prefs.edit().putString("snapshot", before.joinToString(",")).commit()) return "Não foi possível salvar o estado anterior. Nenhuma alteração foi solicitada."
        val saved = parsed(prefs.getString("snapshot", "") ?: "")
        if (saved.size != 3 || saved.any { it !in valid }) return "O estado anterior não está disponível. Confira as opções manualmente."
        if (!ModePolicy.wifiPreflight(mode, before)) return "As opções de desenvolvedor e a depuração sem fio precisam permanecer ativas. Confira no Android."
        val target = ModePolicy.target(mode, saved)
        if (mode == "poco") {
            // One fixed script avoids starting another remote process after wireless stops.
            val outcome = try { service.disableAllOrdered() } catch (_: Exception) { "ERROR:conexão interrompida" }
            // Read locally, since the remote transport may already have stopped.
            Thread.sleep(2200)
            fun state(key: String): String? = try {
                when (Settings.Global.getInt(contentResolver, key)) { 0 -> "0"; 1 -> "1"; else -> null }
            } catch (_: Exception) { null }
            val current = listOf(state("development_settings_enabled"), state("adb_enabled"), state("adb_wifi_enabled"))
            if (current == listOf("0", "0", "0"))
                return "USB, depuração sem fio e modo desenvolvedor estão desativados na última leitura. Confira novamente no Android; o sistema pode alterar os valores depois. O estado anterior continua salvo."
            val states = listOf("depuração USB" to current[1], "depuração sem fio" to current[2], "modo desenvolvedor" to current[0]).joinToString("; ") { (name, value) ->
                "$name: " + when (value) { "0" -> "desativado"; "1" -> "ativo"; else -> "não foi possível verificar" }
            }
            val reason = if (outcome != "OK") "A operação foi interrompida ou uma etapa não foi confirmada. " else "O Android não manteve todos os valores solicitados. "
            return reason + states + ". Confira nas configurações do Android; se necessário, conclua manualmente. O estado anterior continua salvo."
        }
        // Restoration uses saved values and may re-enable wireless only on request.
        val order = ModePolicy.order(mode)
        val names = listOf("modo desenvolvedor", "depuração USB", "depuração sem fio")
        for (i in order) {
            if (before[i] == target[i]) continue
            val result = try { service.writeOne(i, target[i]) } catch (_: Exception) {
                return "A conexão caiu durante ${names[i]}; o resultado é incerto. Confira as três opções no Android e reinicie o Shizuku se necessário. O estado anterior continua salvo."
            }
            if (result != "OK") return "Não foi possível confirmar ${names[i]}. Algumas opções podem ter mudado. Confira as três opções no Android; o estado anterior continua salvo. Detalhe: ${result.take(120)}"
        }
        val after = parsed(service.readAll())
        if (after != target) return "O Android não confirmou todos os valores. Confira as opções; o estado anterior continua salvo para nova tentativa."
        if (!changing && !prefs.edit().remove("snapshot").commit()) return "Valores conferidos, mas não foi possível limpar o estado salvo. Verifique antes de repetir."
        return when (mode) {
            "wifi" -> "Depuração USB desativada; desenvolvedor e depuração sem fio permanecem ativos. Isso pode ser detectado por aplicativos e não garante acesso ao banco."
            "poco" -> "As três opções foram confirmadas desativadas. A conexão de depuração sem fio não pode funcionar enquanto estiver desativada. Isso não garante acesso a bancos."
            else -> "Modo normal: os três valores salvos foram restaurados e conferidos. Outras opções não são restauradas."
        }
    }
}

/** Fixed set of Settings commands, executed through the permissioned Shizuku service. */
private class ShellSettings(preferShizuku: Boolean = false) {
    private val useWifi = !preferShizuku && LocalWifiAdb.connected
    private val keys = listOf("development_settings_enabled", "adb_enabled", "adb_wifi_enabled")
    private fun command(vararg args: String): String {
        if (useWifi) return LocalWifiAdb.command(args)
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        val process = method.invoke(null, args, null, null) as ShizukuRemoteProcess
        try {
            process.outputStream.close()
            if (!process.waitForTimeout(10, TimeUnit.SECONDS)) { process.destroy(); return "ERROR:tempo esgotado" }
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) return "ERROR:" + err.take(200)
            return out
        } finally { try { process.destroy() } catch (_: Exception) {} }
    }
    private fun read(i: Int): String = command("/system/bin/settings", "get", "global", keys[i])
    fun readAll(): String = (0..2).joinToString(",") { read(it) }
    fun disableAllOrdered(): String = if (useWifi) LocalWifiAdb.disableAllOrdered()
        else command("/system/bin/sh", "-c", ModePolicy.fullDisableScript)
    fun readAccessibility(): String = command("/system/bin/settings", "--user", "current", "get", "secure", "enabled_accessibility_services")
    fun writeAccessibility(value: String): String = command("/system/bin/settings", "--user", "current", "put", "secure", "enabled_accessibility_services", value)
    fun writeOne(i: Int, value: String): String {
        if (i !in 0..2 || value !in setOf("0", "1", "null")) return "ERROR:invalid"
        val result = if (value == "null") command("/system/bin/settings", "delete", "global", keys[i])
                     else command("/system/bin/settings", "put", "global", keys[i], value)
        if (result.startsWith("ERROR:")) return result
        return if (read(i) == value) "OK" else "ERROR:resultado divergente"
    }
}

@Composable
private fun PocoScreen(status: DeviceStatus, message: String, ready: Boolean, busy: Boolean, connectionDescription: String, lastTarget: List<String>?, accessibilityServices: List<AccessService>?,
    selectedTab: Int, onTab: (Int) -> Unit, availableServices: List<AvailableService>?, servicesLoading: Boolean, serviceAction: ServiceAction?,
    notificationsEnabled: Boolean, onRequestNotifications: () -> Unit, onNotificationSettings: () -> Unit, onRefreshServices: () -> Unit,
    onServiceAction: (AvailableService, Boolean) -> Unit, onDismissServiceAction: () -> Unit, onConfirmServiceAction: (ServiceAction) -> Unit,
    appearance: AppAppearance, settingsDialog: Boolean, onShowSettings: () -> Unit, onCloseSettings: () -> Unit, onAppearance: (AppAppearance) -> Unit,
    onBank: () -> Unit, onPoco: () -> Unit, onNormal: () -> Unit, onRefresh: () -> Unit, onTest: () -> Unit, onWifiSetup: () -> Unit, onSetup: () -> Unit,
    onSettings: (String) -> Unit, onLaunch: (String, String) -> Unit,
    shortcuts: List<QuickApp>, installedApps: List<QuickApp>, pickerOpen: Boolean,
    loadingApps: Boolean, loadError: Boolean, onShowPicker: () -> Unit, onClosePicker: () -> Unit,
    onAddShortcut: (QuickApp) -> Unit, onRemoveShortcut: (QuickApp) -> Unit,
    accessDialog: Boolean, onShowAccess: () -> Unit, onCloseAccess: () -> Unit, onDisableAccess: (List<AccessService>) -> Unit,
    confirmMode: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val dev = listOf(status.developer, status.adb, status.wifiAdb)
    val banner = when { dev.any { it == true } -> "Desenvolvedor ou depuração: ativo"; dev.any { it == null } -> "Verificação incompleta"; else -> "Desenvolvedor e depuração: desativados" }
    val palette = if (appearance == AppAppearance.LIQUID) liquidPalette else materialPalette
    CompositionLocalProvider(LocalAppPalette provides palette) {
    MaterialTheme(colorScheme = darkColorScheme(primary = palette.accent, secondary = BrightGreen, background = palette.background.last(), surface = palette.surface, surfaceVariant = palette.surfaceHigh, surfaceContainerHigh = palette.dialog, onSurface = Color.White, onSurfaceVariant = TextMuted)) {
        Box(Modifier.fillMaxSize()) {
        AppBackdrop(palette)
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val logoShape = RoundedCornerShape(16.dp)
                Box(Modifier.size(50.dp).clip(logoShape).border(1.dp, palette.outline, logoShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_realista), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("POCO CONTROL", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 0.8.sp)
                    Text("Seu painel de controle", color = TextMuted, fontSize = 12.sp, letterSpacing = .2.sp)
                }
                Surface(onClick = onShowSettings, shape = RoundedCornerShape(16.dp), color = palette.surfaceHigh, border = androidx.compose.foundation.BorderStroke(1.dp, palette.outline), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Settings, contentDescription = "Abrir configurações", tint = Color.White, modifier = Modifier.size(22.dp)) }
                }
            }
            AppNavigation(selectedTab, onTab)
            Spacer(Modifier.height(2.dp))
            if (selectedTab == 1) {
                AccessibilityPanel(availableServices, servicesLoading, message, busy, notificationsEnabled,
                    onRequestNotifications, onNotificationSettings, onRefreshServices,
                    { onSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, onServiceAction)
            } else {
            Surface(shape = RoundedCornerShape(24.dp), color = palette.surface, modifier = Modifier.fillMaxWidth().border(1.dp, palette.outline, RoundedCornerShape(24.dp))) {
                Column(Modifier.background(panelBrush(palette)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = palette.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("VISÃO GERAL", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    }
                    Text(banner, color = Color.White, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(palette.accentSoft.copy(alpha = .48f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Box(Modifier.size(7.dp).background(if (dev.any { it == true }) Amber else if (dev.any { it == null }) TextMuted else BrightGreen, RoundedCornerShape(50)))
                        Spacer(Modifier.width(7.dp))
                        Text("ESTADO INFORMADO PELO ANDROID", color = palette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                    }
                    Text("A acessibilidade é verificada separadamente. Confira as opções no Android quando necessário.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Text("MODOS DE ACESSO", color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            ModeCard("Poco Control", "Tenta desativar, nesta ordem: depuração USB, depuração sem fio e modo desenvolvedor, além dos serviços identificados dos quatro aplicativos selecionados. Pode interromper a conexão.", Icons.Default.Security, "DESATIVAR OPÇÕES", !busy, onPoco)
            ModeCard("Modo Wi-Fi", "Mantém desenvolvedor e depuração sem fio ativos; tenta desligar a depuração USB e os serviços identificados dos quatro aplicativos selecionados.", Icons.Default.Wifi, "APLICAR MODO WI-FI", !busy, onBank)
            ModeCard("Modo normal", "Tenta restaurar as opções e os serviços de acessibilidade salvos pelo Poco Control.", Icons.Default.Settings, "RESTAURAR VALORES", !busy, onNormal)
            Surface(shape = RoundedCornerShape(22.dp), color = palette.surfaceHigh, modifier = Modifier.fillMaxWidth().border(1.dp, palette.outline, RoundedCornerShape(22.dp))) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (ready) "● Conexão disponível · teste antes de usar" else "○ Nenhuma conexão autorizada", color = if (ready) BrightGreen else Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp)); Text(message, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                    if (busy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = BrightBlue) }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onTest, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Testar conexão sem alterar") }
                    OutlinedButton(onClick = onWifiSetup, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Configurar conexão Wi-Fi local") }
                    TextButton(onClick = onSetup) { Text("Configuração inicial do Shizuku") }
                }
            }
            Text("VERIFICAÇÃO DE SEGURANÇA", color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            Surface(shape = RoundedCornerShape(22.dp), color = palette.surface, modifier = Modifier.fillMaxWidth().border(1.dp, palette.outline, RoundedCornerShape(22.dp))) {
                Column(Modifier.padding(vertical = 5.dp)) {
                    StatusRow("Depuração USB", status.adb,
                        "Permite comandos de desenvolvimento por USB. Desativá-la pode interromper ferramentas ADB e o Shizuku. Reative somente se precisar.")
                    StatusRow("Depuração sem fio", status.wifiAdb,
                        "Permite comandos de desenvolvimento pela rede. Desativá-la pode interromper a conexão usada para iniciar o Shizuku; reativá-lo pode exigir ações manuais.")
                    StatusRow("Modo desenvolvedor", status.developer,
                        "Reúne opções avançadas do Android. Ao desligá-lo, algumas opções podem ser redefinidas e o Shizuku pode parar. Nem todas as configurações são restauradas depois.")
                    StatusRow("Acessibilidade", status.accessibility,
                        "Serviços de acessibilidade podem apoiar leitores de tela, controle por voz e outras funções. Ativo não significa inseguro. O Poco Control tenta desativar somente os serviços identificados de SD Maid (AppCleaner), Shizuku-Next, TogglDev e Tooler após confirmação.", isAccessibility = true)
                    StatusRow("VPN na rede padrão", status.defaultVpn,
                        "Mostra apenas se o Android identifica uma VPN na rede usada pelo aplicativo. Não verifica todas as redes, perfis de VPN ou aplicativos que alteram localização. Indisponível pode significar ausência de rede ou leitura sem resultado.")
                }
            }
            Text("Toque no botão de informação para ver explicações offline. São textos fixos, sem IA generativa.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Atualizar verificação") }
            Text("DIAGNÓSTICO PARA A CAIXA", color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            Surface(shape = RoundedCornerShape(22.dp), color = palette.surface, modifier = Modifier.fillMaxWidth().border(1.dp, palette.outline, RoundedCornerShape(22.dp))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Shizuku: $connectionDescription", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Não detectado não confirma que o serviço está parado. A conexão pode cair ao desligar a depuração.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    if (lastTarget != null && lastTarget.size == 3) {
                        Text("Meta da última tentativa nesta sessão", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val current = listOf(status.developer, status.adb, status.wifiAdb)
                        val names = listOf("Desenvolvedor", "USB", "Sem fio")
                        listOf(1, 2, 0).forEach { i ->
                            val target = lastTarget[i]
                            val comparison = when {
                                target !in listOf("0", "1") -> "não comparável"
                                current[i] == null -> "leitura indisponível"
                                current[i] == (target == "1") -> "corresponde à meta"
                                else -> "não corresponde à meta"
                            }
                            val wanted = when (target) { "0" -> "desativado"; "1" -> "ativo"; "null" -> "valor ausente"; else -> "desconhecida" }
                            Text("${names[i]}: meta $wanted · $comparison", color = if (comparison == "corresponde à meta") BrightGreen else Amber, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        Text("A comparação usa leituras atuais do Android. Confirme também nas configurações oficiais; uma leitura indisponível não é sucesso.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    } else {
                        Text("Depois de uma tentativa de modo, esta seção compara a meta com as leituras atuais enquanto o app estiver aberto.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    Text("Se o erro continuar: confira as opções do desenvolvedor nas configurações, reinicie o celular, verifique VPN e a certificação do Play Protect. Atualize o app da CAIXA pela loja oficial e consulte o suporte.", color = Color.White, fontSize = 13.sp, lineHeight = 20.sp)
                    Text("Não verificamos root, bootloader, integridade do dispositivo, todas as VPNs ou os critérios internos da CAIXA. Este diagnóstico não garante acesso e não substitui o suporte do banco.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Não restaure o aparelho sem antes fazer backup: a restauração apaga os dados.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Text("FERRAMENTAS", color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            ActionButton("Abrir opções do desenvolvedor", Icons.Default.Tune) { onSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            ActionButton("Gerenciar acessibilidade", Icons.Default.Accessibility) { onSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            ActionButton("Abrir configurações de VPN", Icons.Default.Settings) { onSettings(Settings.ACTION_VPN_SETTINGS) }
            ActionButton("Abrir Shizuku Next", Icons.Default.OpenInNew) { onLaunch("moe.shizuku.privileged.api", "Shizuku Next") }
            Text("Certificação: Play Store → foto do perfil → Configurações → Sobre → Certificação do Play Protect.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Surface(shape = RoundedCornerShape(22.dp), color = palette.surface, modifier = Modifier.fillMaxWidth().border(1.dp, palette.outline, RoundedCornerShape(22.dp))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Serviços de acessibilidade", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    when {
                        accessibilityServices == null -> Text("O Android não informou a lista. Abra as configurações para conferir.", color = Amber, fontSize = 13.sp, lineHeight = 19.sp)
                        accessibilityServices.isEmpty() -> Text("O Android não retornou nomes de serviços ativos. Confira nas configurações; esta lista pode ser incompleta.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        else -> accessibilityServices.forEach { Text("• ${it.app} — ${it.service}", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) }
                    }
                    Text("O Poco Control tenta desativar somente os serviços identificados de SD Maid (AppCleaner), Shizuku-Next, TogglDev e Tooler. A opção abaixo permite escolher separadamente. Confira sempre nas configurações.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    OutlinedButton(onClick = onShowAccess, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Escolher serviços para desativar") }
                    Text("O modo normal tenta restaurar os serviços salvos nesta versão. Se não conseguir, reative-os nas configurações do Android.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Não desligue serviços essenciais, como leitores de tela, sem uma alternativa para usar o aparelho.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Text("ACESSOS RÁPIDOS", color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            Text("Escolha seus bancos e outros aplicativos instalados. Os atalhos ficam salvos neste aparelho.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            shortcuts.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { app -> ShortcutTile(app, Modifier.weight(1f), onOpen = { onLaunch(app.packageName, app.label) }, onRemove = { onRemoveShortcut(app) }) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (shortcuts.isEmpty()) Text("Nenhum atalho. Adicione seus aplicativos abaixo.", color = TextMuted, fontSize = 13.sp)
            OutlinedButton(onClick = onShowPicker, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Adicionar banco ou aplicativo")
            }
            Text("POCO CONTROL 3.8", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Não afiliado à Xiaomi, CAIXA ou Gov.br", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            }
        }
        }
        if (serviceAction != null) ServiceActionConfirmation(serviceAction, onDismissServiceAction, { onConfirmServiceAction(serviceAction) })
        if (settingsDialog) AppearanceSettings(appearance, onCloseSettings, onAppearance)
        if (accessDialog) {
            AccessibilityPicker(accessibilityServices, onCloseAccess, onDisableAccess)
        }
        if (pickerOpen) {
            AppPicker(installedApps, shortcuts, loadingApps, loadError, onClosePicker, onAddShortcut)
        }
        if (confirmMode != null) {
            val bank = confirmMode == "wifi" || confirmMode == "poco"
            val full = confirmMode == "poco"
            val selectedServices = if (bank) targetAccessServices(accessibilityServices) else emptyList()
            val foundGroups = selectedServices.mapNotNull { accessTargetGroup(it) }.toSet()
            val missingGroups = targetGroups.filterNot { it in foundGroups }
            AlertDialog(onDismissRequest = onDismiss,
                title = { Text(if (full) "Desativar opções?" else if (bank) "Aplicar modo Wi-Fi?" else "Restaurar valores?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bank) {
                            Text(if (full) "O app tentará desativar os serviços identificados abaixo e, em seguida, nesta ordem: 1. depuração USB; 2. depuração sem fio; 3. modo desenvolvedor. O Shizuku autorizado é preferível: a conexão Wi-Fi pode cair antes da última etapa. Confira os estados no Android." else "O app tentará desativar a depuração USB e os serviços identificados abaixo. As opções de desenvolvedor e a depuração sem fio devem permanecer ativas.", fontSize = 14.sp, lineHeight = 20.sp)
                            if (selectedServices.isEmpty()) Text("Nenhum serviço selecionado foi identificado; confira-os manualmente.", color = Amber, fontSize = 13.sp)
                            else selectedServices.forEach { Text("• ${it.app} — ${it.service}\n${it.component}", color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp) }
                            if (missingGroups.isNotEmpty()) Text("Não identificados: ${missingGroups.joinToString()}. Isso não confirma que estejam desativados; confira manualmente.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                            if (!ready) Text("Nenhuma conexão está disponível. Conecte o Wi-Fi local ou autorize o Shizuku antes de continuar.", color = Amber, fontSize = 13.sp, lineHeight = 19.sp)
                            Text("Outros serviços não são selecionados. A conexão pode parar durante a operação, deixando alterações parciais. Desenvolvedor e depuração sem fio, quando ativos, podem ser detectados por apps e reduzem a segurança. O modo normal tenta reativar os serviços salvos, mas pode ser necessário reativá-los manualmente. Outras opções de desenvolvedor também podem mudar e não serão restauradas. Sem garantia de acesso aos bancos.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                        } else Text("O app tentará restaurar os três valores e os serviços de acessibilidade salvos antes da desativação. Isso pode reativar a depuração sem fio se ela estava ativa no estado salvo. É necessária uma conexão Wi-Fi local ativa ou o Shizuku autorizado. Restaurar valores pode desligar a depuração e interromper a conexão. Na versão 2.3 os serviços não eram salvos: reative-os manualmente. O Android pode exigir reativação manual. A depuração pode reduzir a segurança.")
                    }
                },
                confirmButton = { TextButton(onClick = { onConfirm(confirmMode) }) { Text(if (full) "Desativar opções" else if (bank) "Aplicar modo Wi-Fi" else "Restaurar", textAlign = androidx.compose.ui.text.style.TextAlign.Center) } },
                dismissButton = { Row {
                    if (bank) TextButton(onClick = { onDismiss(); onSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Abrir acessibilidade") }
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                } })
        }
    }
    }
}

@Composable
private fun AccessibilityPicker(services: List<AccessService>?, onClose: () -> Unit, onDisable: (List<AccessService>) -> Unit) {
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val matches = targetAccessServices(services)
    val selected = matches.filter { it.component in chosen }
    AlertDialog(onDismissRequest = onClose, title = { Text("Desativar serviços escolhidos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selecione somente SD Maid (AppCleaner), Shizuku-Next, TogglDev e Tooler que deseja desativar. Confira o nome e o serviço de cada item.", fontSize = 13.sp, lineHeight = 19.sp)
                if (matches.isEmpty()) Text("Nenhum desses serviços foi identificado como ativo. Confira ou desative-os nas configurações do Android.", color = Amber, fontSize = 13.sp)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
                    items(matches, key = { it.component }) { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = item.component in chosen, onCheckedChange = { on -> chosen = if (on) chosen + item.component else chosen - item.component })
                            Column(Modifier.weight(1f)) { Text(item.app, fontSize = 14.sp); Text(item.service, color = TextMuted, fontSize = 12.sp); Text(item.component, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp) }
                        }
                    }
                }
                Text("Ao confirmar, o app tentará alterar a lista real do Android. Outros serviços devem permanecer ativos. O Shizuku pode parar e o resultado pode ser parcial. O modo normal tenta restaurar os serviços salvos; se necessário, reative-os nas configurações do Android.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onDisable(selected) }, enabled = selected.isNotEmpty()) { Text("Tentar desativar") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } })
}

@Composable
private fun ModeCard(title: String, description: String, icon: ImageVector, button: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(if (palette.liquid) 24.dp else 20.dp)
    Column(Modifier.fillMaxWidth().background(palette.surfaceHigh, shape).background(panelBrush(palette), shape)
        .border(1.dp, palette.outline, shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(palette.accent.copy(alpha = if (palette.liquid) .5f else .22f), Color.Transparent))))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(palette.accentSoft, RoundedCornerShape(if (palette.liquid) 15.dp else 12.dp))
                .border(1.dp, palette.accent.copy(alpha = .25f), RoundedCornerShape(if (palette.liquid) 15.dp else 12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(description, color = TextMuted, fontSize = 14.sp, lineHeight = 21.sp)
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = RoundedCornerShape(if (palette.liquid) 16.dp else 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.action, contentColor = Color.White,
                disabledContainerColor = Color(0xFF415367), disabledContentColor = Color(0xFFB7C4D3))) {
            Text(button, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = .3.sp)
        }
    }
}
@Composable
private fun StatusRow(label: String, active: Boolean?, explanation: String, isAccessibility: Boolean = false) {
    var showExplanation by remember { mutableStateOf(false) }
    val color = when { active == false -> BrightGreen; active == true && isAccessibility -> BrightBlue; else -> Amber }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (active == false) Icons.Default.CheckCircle else if (isAccessibility && active == true) Icons.Default.Accessibility else Icons.Default.Warning, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp)); Text(label, modifier = Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
        Text(when (active) { true -> "ATIVO"; false -> "DESATIVADO"; null -> "INDISPONÍVEL" }, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = { showExplanation = true }) { Icon(Icons.Default.Info, contentDescription = "Entender $label", tint = BrightBlue) }
    }
    if (showExplanation) {
        AlertDialog(onDismissRequest = { showExplanation = false }, title = { Text(label) },
            text = { Text(explanation + "\n\nEstado informado pelo Android: " + when (active) { true -> "ativo"; false -> "desativado"; null -> "indisponível" } + ".") },
            confirmButton = { TextButton(onClick = { showExplanation = false }) { Text("Entendi") } })
    }
}
@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label) }
}
@Composable
private fun ShortcutTile(app: QuickApp, modifier: Modifier = Modifier, onOpen: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalAppPalette.current
    Surface(modifier = modifier.heightIn(min = 58.dp), shape = RoundedCornerShape(16.dp), color = palette.surfaceHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.outline)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 58.dp),
                contentPadding = PaddingValues(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)) {
                Text(app.label, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remover ${app.label}", tint = Color(0xFFC5DAF7), modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun AppPicker(apps: List<QuickApp>, shortcuts: List<QuickApp>, loading: Boolean, error: Boolean,
                      onClose: () -> Unit, onAdd: (QuickApp) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(apps, query) { apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) } }
    val shortcutPackages = remember(shortcuts) { shortcuts.map { it.packageName }.toSet() }
    AlertDialog(onDismissRequest = onClose, title = { Text("Adicionar acesso rápido") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selecione um banco ou outro aplicativo instalado. A lista é consultada apenas no aparelho.", fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Pesquisar aplicativo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    loading -> Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    error -> Text("Não foi possível carregar os aplicativos. Feche e tente novamente.")
                    matches.isEmpty() -> Text("Nenhum aplicativo encontrado. Verifique se está instalado.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(matches, key = { it.packageName }) { app ->
                            val exists = app.packageName in shortcutPackages
                            TextButton(onClick = { onAdd(app) }, enabled = !exists, modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = if (exists) TextMuted else Color.White, fontSize = 14.sp)
                                    Text(app.packageName, color = TextMuted, fontSize = 11.sp, maxLines = 1)
                                }
                                if (exists) Text("Adicionado", color = TextMuted, fontSize = 11.sp)
                                else Icon(Icons.Default.Add, contentDescription = "Adicionar ${app.label}")
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } })
}
