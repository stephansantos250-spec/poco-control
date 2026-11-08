package com.pococontrol.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    private var message by mutableStateOf("Conecte o Shizuku para usar os modos automáticos.")
    private var shizukuReady by mutableStateOf(false)
    private var connectionDescription by mutableStateOf("Verificação pendente")
    private var accessibilityServices by mutableStateOf<List<AccessService>?>(null)
    private var accessDialog by mutableStateOf(false)
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
    private val prefs by lazy { getSharedPreferences("mode_snapshot", MODE_PRIVATE) }
    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { if (!isDestroyed) updateConnection() } }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { if (!isDestroyed) updateConnection() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grant ->
        updateConnection()
        message = if (grant == PackageManager.PERMISSION_GRANTED) "Permissão concedida. Toque no modo desejado novamente." else "Permissão negada. Autorize o POCO CONTROL no Shizuku para usar os modos automáticos."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshStatus(); updateConnection(); loadShortcuts()
        setContent {
            PocoScreen(status, message, shizukuReady, busy, connectionDescription, lastTarget, accessibilityServices,
                onBank = { refreshStatus(); confirmMode = "banco" }, onNormal = { confirmMode = "normal" },
                onRefresh = { refreshStatus(); updateConnection() },
                onTest = { runOperation("teste") },
                onSettings = { openSettings(it) }, onLaunch = { pkg, label -> launchApp(pkg, label) },
                shortcuts = shortcuts, installedApps = installedApps, pickerOpen = pickerOpen,
                loadingApps = loadingApps, loadError = loadError,
                onShowPicker = { loadInstalledApps() }, onClosePicker = { closePicker() },
                onAddShortcut = { addShortcut(it) }, onRemoveShortcut = { removeShortcut(it) },
                accessDialog = accessDialog, onShowAccess = { refreshStatus(); accessDialog = true },
                onCloseAccess = { accessDialog = false }, onDisableAccess = { selected -> accessDialog = false; disableSelectedAccessibility(selected) },
                confirmMode = confirmMode, onDismiss = { confirmMode = null },
                onConfirm = { mode -> confirmMode = null; runOperation(mode, if (mode == "banco") targetAccessServices(accessibilityServices) else emptyList()) })
        }
    }
    override fun onResume() { super.onResume(); refreshStatus(); updateConnection() }
    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        executor.shutdown()
        super.onDestroy()
    }
    private fun updateConnection() {
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
    private fun changeAccessibility(shell: ShellSettings, selected: List<AccessService>): String {
        if (selected.isEmpty()) return "Acessibilidade: nenhum dos serviços selecionados foi identificado. Confira-os manualmente nas configurações."
        val targets = selected.map { it.component }.toSet()
        val raw = shell.readAccessibility()
        fun components(value: String): List<String>? {
            if (value.startsWith("ERROR:")) return null
            if (value == "null" || value.isEmpty()) return emptyList()
            val parts = value.split(":")
            return if (parts.any { ComponentName.unflattenFromString(it) == null }) null else parts
        }
        fun canonical(value: String): String? = ComponentName.unflattenFromString(value)?.flattenToString()
        val before = components(raw)
        if (before == null) return "Acessibilidade: lista indisponível; não foi solicitada alteração. Confira manualmente."
        if (targets.any { target -> before.none { canonical(it) == target } })
            return "Acessibilidade: a lista mudou; nenhuma alteração foi solicitada. Atualize e confira manualmente."
        if (shell.readAccessibility() != raw) return "Acessibilidade: a lista mudou durante a verificação; nada foi alterado. Confira manualmente."
        val remaining = before.filterNot { canonical(it) in targets }.joinToString(":")
        val written = shell.writeAccessibility(remaining)
        if (written.startsWith("ERROR:")) return "Acessibilidade: o Android não confirmou a alteração. O resultado pode ser parcial ou incerto; confira nas configurações. Detalhe: ${written.take(180)}"
        val after = components(shell.readAccessibility())
        if (after == null || after.mapNotNull { canonical(it) }.toSet() != remaining.split(":").filter { it.isNotEmpty() }.mapNotNull { canonical(it) }.toSet())
            return "Acessibilidade: resultado não confirmado. Confira cada serviço nas configurações."
        val found = selected.mapNotNull { accessTargetGroup(it) }.toSet()
        val missing = targetGroups.filterNot { it in found }
        return "Acessibilidade: os serviços identificados foram removidos da lista de configurações; confira a tela do Android." +
            if (missing.isNotEmpty()) " Não identificados: ${missing.joinToString()}; confira-os manualmente." else ""
    }
    private fun checkShizuku(): Boolean {
        try {
            if (!Shizuku.pingBinder()) { message = "Shizuku-Next não está conectado. Inicie o serviço no Shizuku-Next, autorize Poco Control e tente de novo. Se necessário, desative os serviços nas configurações do Android."; return false }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) message = "Autorize o POCO CONTROL no Shizuku e tente novamente."
                else { Shizuku.requestPermission(31); message = "Autorize o Shizuku e tente a ação novamente." }
                return false
            }
            return true
        } catch (_: Exception) { message = "Não foi possível acessar o Shizuku. Confira as configurações do Android."; return false }
    }
    private fun disableSelectedAccessibility(selected: List<AccessService>) {
        if (busy || selected.isEmpty() || !checkShizuku()) return
        busy = true
        message = "Tentando desativar somente os serviços escolhidos..."
        executor.execute {
            val result = try { changeAccessibility(ShellSettings(), selected) }
                catch (_: Exception) { "Acessibilidade: conexão interrompida; resultado incerto. Confira cada serviço nas configurações." }
            main.post { if (!isDestroyed) { busy = false; message = result; refreshStatus(); updateConnection() } }
        }
    }
    private fun runOperation(mode: String, accessTargets: List<AccessService> = emptyList()) {
        if (busy || !checkShizuku()) return
        if (mode == "normal" && !prefs.contains("snapshot")) { message = "Não há configuração anterior salva. Use as configurações oficiais para escolher o estado desejado."; return }
        if (mode == "banco") lastTarget = listOf("0", "0", "0")
        else if (mode == "normal") lastTarget = (prefs.getString("snapshot", "") ?: "").split(",")
        busy = true
        message = if (mode == "teste") "Testando a leitura, sem alterar configurações…" else "Solicitando alterações ao Android…"
        executor.execute {
            val result = try {
                val shell = ShellSettings()
                if (mode == "teste") {
                    val values = shell.readAll().split(",").map { it.trim() }
                    if (values.size == 3 && values.all { it in setOf("0", "1", "null") })
                        "Teste de leitura concluído: Shizuku Next respondeu. Nenhuma configuração foi alterada. A permissão de escrita ainda não foi testada."
                    else "O Shizuku respondeu, mas não foi possível ler todas as opções. Nada foi alterado."
                } else if (mode == "banco") {
                    val accessibilityResult = try { changeAccessibility(shell, accessTargets) }
                        catch (_: Exception) { "Acessibilidade: conexão interrompida; resultado incerto. Confira cada serviço nas configurações." }
                    val settingsResult = try { executeMode(shell, true) }
                        catch (_: Exception) { "Desenvolvedor e depuração: conexão interrompida ou operação incompleta. Confira as três opções no Android." }
                    accessibilityResult + "\n\n" + settingsResult
                } else executeMode(shell, false)
            } catch (e: Exception) {
                if (mode == "teste") "Falha no teste de leitura. Confirme Shizuku Next ativo e autorizado. Nada foi alterado."
                else "A operação foi interrompida; algumas opções podem ter mudado. Confira o estado no Android e reinicie o Shizuku Next se necessário."
            }
            main.post { if (!isDestroyed) { busy = false; message = result; refreshStatus(); updateConnection() } }
        }
    }
    private fun executeMode(service: ShellSettings, bank: Boolean): String {
        val valid = setOf("0", "1", "null")
        fun parsed(s: String) = s.split(",").map { it.trim() }
        val before = parsed(service.readAll())
        if (before.size != 3 || before.any { it !in valid }) return "Não foi possível ler as três opções. Nenhuma alteração foi solicitada."
        if (bank && !prefs.contains("snapshot") && !prefs.edit().putString("snapshot", before.joinToString(",")).commit()) return "Não foi possível salvar o estado anterior. Nenhuma alteração foi solicitada."
        val saved = parsed(prefs.getString("snapshot", "") ?: "")
        if (saved.size != 3 || saved.any { it !in valid }) return "O estado anterior não está disponível. Confira as opções manualmente."
        val target = if (bank) listOf("0", "0", "0") else saved
        // Try the developer setting before disabling ADB: otherwise the shell may die first.
        val order = if (bank) listOf(0, 2, 1) else listOf(0, 1, 2)
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
        if (!bank && !prefs.edit().remove("snapshot").commit()) return "Valores conferidos, mas não foi possível limpar o estado salvo. Verifique antes de repetir."
        return if (bank) "Desenvolvedor e depuração: os três valores foram confirmados como desativados. Isso não garante acesso ao banco." else "Modo normal: os três valores salvos foram restaurados e conferidos. Outras opções não são restauradas."
    }
}

/** Fixed set of Settings commands, executed through the permissioned Shizuku service. */
private class ShellSettings {
    private val keys = listOf("development_settings_enabled", "adb_enabled", "adb_wifi_enabled")
    private fun command(vararg args: String): String {
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
fun PocoScreen(status: DeviceStatus, message: String, ready: Boolean, busy: Boolean, connectionDescription: String, lastTarget: List<String>?, accessibilityServices: List<AccessService>?,
    onBank: () -> Unit, onNormal: () -> Unit, onRefresh: () -> Unit, onTest: () -> Unit,
    onSettings: (String) -> Unit, onLaunch: (String, String) -> Unit,
    shortcuts: List<QuickApp>, installedApps: List<QuickApp>, pickerOpen: Boolean,
    loadingApps: Boolean, loadError: Boolean, onShowPicker: () -> Unit, onClosePicker: () -> Unit,
    onAddShortcut: (QuickApp) -> Unit, onRemoveShortcut: (QuickApp) -> Unit,
    accessDialog: Boolean, onShowAccess: () -> Unit, onCloseAccess: () -> Unit, onDisableAccess: (List<AccessService>) -> Unit,
    confirmMode: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val dev = listOf(status.developer, status.adb, status.wifiAdb)
    val banner = when { dev.any { it == true } -> "Desenvolvedor ou depuração: ativo"; dev.any { it == null } -> "Verificação incompleta"; else -> "Desenvolvedor e depuração: desativados" }
    MaterialTheme(colorScheme = darkColorScheme(primary = BrightBlue, secondary = BrightGreen, background = Background, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF092C63), Background, Color(0xFF020B1C))))
            .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(Color(0xFF0B70CE), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Security, null, tint = Color.White, modifier = Modifier.size(31.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column { Row { Text("POCO ", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp); Text("CONTROL", color = Color(0xFFFFD942), fontWeight = FontWeight.Black, fontSize = 24.sp) }; Text("Segurança e acesso", color = TextMuted, fontSize = 14.sp) }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF083B31), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF26C97C), RoundedCornerShape(18.dp))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, tint = BrightGreen, modifier = Modifier.size(34.dp)); Spacer(Modifier.width(12.dp))
                    Column { Text(banner, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Acessibilidade é verificada separadamente. Cada aplicativo pode aplicar suas próprias verificações.", color = TextMuted, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }
            Text("MODOS DE ACESSO", color = Color(0xFF9AD8FF), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            ModeCard("Poco Control", "Tenta desativar desenvolvedor, depuração e os serviços identificados dos quatro aplicativos selecionados.", Icons.Default.Security, listOf(Color(0xFF07883D), Color(0xFF055C37)), "DESATIVAR OPÇÕES", !busy, onBank)
            ModeCard("Modo normal", "Tenta restaurar os três valores salvos pelo Poco Control.", Icons.Default.Settings, listOf(Color(0xFF086ADF), Color(0xFF084497)), "RESTAURAR VALORES", !busy, onNormal)
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0E3157), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (ready) "● Shizuku autorizado · teste a conexão" else "○ Shizuku não conectado ou sem permissão", color = if (ready) BrightGreen else Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp)); Text(message, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                    if (busy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = BrightBlue) }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onTest, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Testar conexão sem alterar") }
                }
            }
            Text("VERIFICAÇÃO DE SEGURANÇA", color = Color(0xFF9AD8FF), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 5.dp)) {
                    StatusRow("Modo desenvolvedor", status.developer,
                        "Reúne opções avançadas do Android. Ao desligá-lo, algumas opções podem ser redefinidas e o Shizuku pode parar. Nem todas as configurações são restauradas depois.")
                    StatusRow("Depuração USB", status.adb,
                        "Permite comandos de desenvolvimento por USB. Desativá-la pode interromper ferramentas ADB e o Shizuku. Reative somente se precisar.")
                    StatusRow("Depuração sem fio", status.wifiAdb,
                        "Permite comandos de desenvolvimento pela rede. Desativá-la pode interromper a conexão usada para iniciar o Shizuku; reativá-lo pode exigir ações manuais.")
                    StatusRow("Acessibilidade", status.accessibility,
                        "Serviços de acessibilidade podem apoiar leitores de tela, controle por voz e outras funções. Ativo não significa inseguro. O Poco Control tenta desativar somente os serviços identificados de SD Maid (AppCleaner), Shizuku-Next, TogglDev e Tooler após confirmação.", isAccessibility = true)
                    StatusRow("VPN na rede padrão", status.defaultVpn,
                        "Mostra apenas se o Android identifica uma VPN na rede usada pelo aplicativo. Não verifica todas as redes, perfis de VPN ou aplicativos que alteram localização. Indisponível pode significar ausência de rede ou leitura sem resultado.")
                }
            }
            Text("Toque no botão de informação para ver explicações offline. São textos fixos, sem IA generativa.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Atualizar verificação") }
            Text("DIAGNÓSTICO PARA A CAIXA", color = Color(0xFF9AD8FF), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Shizuku: $connectionDescription", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Não detectado não confirma que o serviço está parado. A conexão pode cair ao desligar a depuração.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    if (lastTarget != null && lastTarget.size == 3) {
                        Text("Meta da última tentativa nesta sessão", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val current = listOf(status.developer, status.adb, status.wifiAdb)
                        val names = listOf("Desenvolvedor", "USB", "Sem fio")
                        names.indices.forEach { i ->
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
            Text("FERRAMENTAS", color = Color(0xFF9AD8FF), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            ActionButton("Abrir opções do desenvolvedor", Icons.Default.Tune) { onSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            ActionButton("Gerenciar acessibilidade", Icons.Default.Accessibility) { onSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            ActionButton("Abrir configurações de VPN", Icons.Default.Settings) { onSettings(Settings.ACTION_VPN_SETTINGS) }
            ActionButton("Abrir Shizuku Next", Icons.Default.OpenInNew) { onLaunch("moe.shizuku.privileged.api", "Shizuku Next") }
            Text("Certificação: Play Store → foto do perfil → Configurações → Sobre → Certificação do Play Protect.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Serviços de acessibilidade", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    when {
                        accessibilityServices == null -> Text("O Android não informou a lista. Abra as configurações para conferir.", color = Amber, fontSize = 13.sp, lineHeight = 19.sp)
                        accessibilityServices.isEmpty() -> Text("O Android não retornou nomes de serviços ativos. Confira nas configurações; esta lista pode ser incompleta.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        else -> accessibilityServices.forEach { Text("• ${it.app} — ${it.service}", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) }
                    }
                    Text("O Poco Control tenta desativar somente os serviços identificados de SD Maid (AppCleaner), Shizuku-Next, TogglDev e Tooler. A opção abaixo permite escolher separadamente. Confira sempre nas configurações.", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    OutlinedButton(onClick = onShowAccess, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Escolher serviços para desativar") }
                    Text("Para reativar, use as configurações do Android; o modo normal não restaura serviços de acessibilidade.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Não desligue serviços essenciais, como leitores de tela, sem uma alternativa para usar o aparelho.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Text("ACESSOS RÁPIDOS", color = Color(0xFF9AD8FF), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
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
            Text("Versão experimental • Não afiliado à Xiaomi, CAIXA ou Gov.br", color = Color(0xFF9DAEC7), fontSize = 12.sp)
        }
        if (accessDialog) {
            AccessibilityPicker(accessibilityServices, onCloseAccess, onDisableAccess)
        }
        if (pickerOpen) {
            AppPicker(installedApps, shortcuts, loadingApps, loadError, onClosePicker, onAddShortcut)
        }
        if (confirmMode != null) {
            val bank = confirmMode == "banco"
            val selectedServices = if (bank) targetAccessServices(accessibilityServices) else emptyList()
            val foundGroups = selectedServices.mapNotNull { accessTargetGroup(it) }.toSet()
            val missingGroups = targetGroups.filterNot { it in foundGroups }
            AlertDialog(onDismissRequest = onDismiss,
                title = { Text(if (bank) "Ativar Poco Control?" else "Restaurar valores?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bank) {
                            Text("O app tentará desativar os serviços identificados abaixo e depois as opções de desenvolvedor, depuração USB e sem fio. Apenas serviços identificados dos quatro aplicativos selecionados serão incluídos:", fontSize = 14.sp, lineHeight = 20.sp)
                            if (selectedServices.isEmpty()) Text("Nenhum serviço selecionado foi identificado; confira-os manualmente.", color = Amber, fontSize = 13.sp)
                            else selectedServices.forEach { Text("• ${it.app} — ${it.service}\n${it.component}", color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp) }
                            if (missingGroups.isNotEmpty()) Text("Não identificados: ${missingGroups.joinToString()}. Isso não confirma que estejam desativados; confira manualmente.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                            if (!ready) Text("Shizuku-Next está desconectado ou sem autorização. Nenhuma alteração automática será feita até que ele esteja ativo e autorize o Poco Control. Você também pode desativar os serviços manualmente.", color = Amber, fontSize = 13.sp, lineHeight = 19.sp)
                            Text("Outros serviços não são selecionados. O Shizuku pode parar durante a operação, deixando alterações parciais. O modo normal não reativa serviços de acessibilidade; reative-os manualmente se precisar. Outras opções de desenvolvedor também podem mudar e não serão restauradas. Sem garantia de acesso aos bancos.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
                        } else Text("O app tentará restaurar somente os três valores salvos antes do Poco Control. O Shizuku precisa estar ativo e autorizado. A depuração pode reduzir a segurança. Serviços de acessibilidade não são restaurados; reative-os nas configurações do Android.")
                    }
                },
                confirmButton = { TextButton(onClick = { onConfirm(confirmMode) }) { Text(if (bank) "Tentar desativar" else "Restaurar") } },
                dismissButton = { Row {
                    if (bank) TextButton(onClick = { onDismiss(); onSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Abrir acessibilidade") }
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                } })
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
                Text("Ao confirmar, o app tentará alterar a lista real do Android. Outros serviços devem permanecer ativos. O Shizuku pode parar e o resultado pode ser parcial. Para reativar, use as configurações do Android; o modo normal não restaura estes serviços.", color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onDisable(selected) }, enabled = selected.isNotEmpty()) { Text("Tentar desativar") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } })
}

@Composable
private fun ModeCard(title: String, description: String, icon: ImageVector, colors: List<Color>, button: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(colors), RoundedCornerShape(18.dp)).border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Text(description, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B315A))) { Text(button, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
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
    Surface(modifier = modifier.heightIn(min = 58.dp), shape = RoundedCornerShape(13.dp), color = Color(0xFF164E8E)) {
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
    val matches = apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
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
                            val exists = shortcuts.any { it.packageName == app.packageName }
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
