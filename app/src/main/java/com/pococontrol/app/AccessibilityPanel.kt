package com.pococontrol.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit

internal data class AvailableService(val component: String, val app: String, val service: String, val enabled: Boolean?)
internal data class ServiceAction(val item: AvailableService, val enable: Boolean)

internal object AccessibilityDiscovery {
    private const val CHANNEL = "accessibility_discovery"
    private const val PREFS = "accessibility_discovery"
    private const val BASELINE = "known_components"
    private const val PENDING = "pending_components"
    private const val WORK = "scan_accessibility_services"

    fun scan(context: Context): List<AvailableService> {
        val manager = context.getSystemService(AccessibilityManager::class.java)
            ?: throw IllegalStateException("AccessibilityManager indisponível")
        // A null value means no configured services; an exception is an unknown state.
        val enabled: Set<String>? = try {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (raw.isNullOrBlank()) emptySet() else {
                val parts = raw.split(":")
                val parsed = parts.map { ComponentName.unflattenFromString(it)?.flattenToString() }
                if (parsed.any { it == null }) null else parsed.filterNotNull().toSet()
            }
        } catch (_: Exception) { null }
        val pm = context.packageManager
        return manager.installedAccessibilityServiceList.mapNotNull { info ->
            val resolved = info.resolveInfo ?: return@mapNotNull null
            val si = resolved.serviceInfo ?: return@mapNotNull null
            val component = ComponentName(si.packageName, si.name).flattenToString()
            val app = try { si.applicationInfo.loadLabel(pm).toString() } catch (_: Exception) { si.packageName }
            val service = try { resolved.loadLabel(pm).toString() } catch (_: Exception) { si.name }
            AvailableService(component, app, service, enabled?.contains(component))
        }.distinctBy { it.component }.sortedWith(compareBy({ it.app.lowercase() }, { it.service.lowercase() }))
    }

    // The first successful scan is a baseline, not a stream of false "new app" alerts.
    @Synchronized fun recordAndNotify(context: Context, items: List<AvailableService>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = items.map { it.component }.toSet()
        if (!prefs.contains(BASELINE)) {
            prefs.edit().putStringSet(BASELINE, current).putStringSet(PENDING, emptySet()).commit()
            return
        }
        val previous = prefs.getStringSet(BASELINE, emptySet())?.toSet() ?: emptySet()
        val oldPending = prefs.getStringSet(PENDING, emptySet())?.toSet() ?: emptySet()
        // Alert only for a newly discovered service that is not confirmed configured.
        // A missing permission is not proof that the app requires accessibility.
        val eligible = items.filter { it.enabled != true }.map { it.component }.toSet()
        val pending = (oldPending + (current - previous)).intersect(eligible)
        if (!prefs.edit().putStringSet(BASELINE, current).putStringSet(PENDING, pending).commit()) return
        if (pending.isEmpty()) return
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Novos serviços de acessibilidade", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisa sobre novos serviços de acessibilidade disponíveis para revisão."
            })
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val pendingItems = items.filter { it.component in pending }
            val names = pendingItems.distinctBy { it.component.substringBefore("/") }.map { it.app }
            val intent = Intent(context, MainActivity::class.java).putExtra("open_accessibility_tab", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val launch = PendingIntent.getActivity(context, 25, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val summary = if (names.size == 1) "${names.first()}: novo serviço para revisar; ative apenas se desejar." else "${names.size} aplicativos têm novos serviços para revisar."
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Novo serviço de acessibilidade")
                .setContentText(summary).setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setContentIntent(launch).setAutoCancel(true).build()
            nm.notify(2507, notification)
            prefs.edit().putStringSet(PENDING, emptySet()).commit()
        } catch (_: Exception) { /* Manter pendências para nova tentativa. */ }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AccessibilityScanWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class AccessibilityScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val items = AccessibilityDiscovery.scan(applicationContext)
        AccessibilityDiscovery.recordAndNotify(applicationContext, items)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}

@Composable
internal fun AccessibilityPanel(items: List<AvailableService>?, loading: Boolean, message: String,
    busy: Boolean, notificationsEnabled: Boolean, onRequestNotifications: () -> Unit,
    onNotificationSettings: () -> Unit, onRefresh: () -> Unit, onSystemSettings: () -> Unit,
    onAction: (AvailableService, Boolean) -> Unit) {
    val p = LocalAppPalette.current
    var query by remember { mutableStateOf("") }
    val matches = remember(items, query) { items?.filter { it.app.contains(query, true) || it.service.contains(query, true) || it.component.contains(query, true) } }
    val groups = remember(matches) {
        matches?.groupBy { it.component.substringBefore("/") }?.entries?.sortedWith(
            compareBy({ entry -> if (entry.value.any { it.enabled == false }) 0 else if (entry.value.any { it.enabled == null }) 1 else 2 },
                { entry -> entry.value.first().app.lowercase() })
        )
    }
    Text("ACESSIBILIDADE", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
    Text("Apps e serviços", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("O Android informa os aplicativos deste perfil que oferecem serviços de acessibilidade. Serviços não ativados aparecem primeiro.", color = Color(0xFFC5DAF7), fontSize = 14.sp, lineHeight = 20.sp)
    if (items != null) {
        val appCount = items.map { it.component.substringBefore("/") }.distinct().size
        val offCount = items.count { it.enabled == false }
        val unknownCount = items.count { it.enabled == null }
        Text("$appCount apps encontrados · $offCount serviços não ativados" + if (unknownCount > 0) " · $unknownCount sem estado" else "", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = p.surfaceHigh, border = BorderStroke(1.dp, p.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(panelBrush(p)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Avisos de novos serviços", color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (notificationsEnabled) "Avisos permitidos. Novos serviços são buscados ao abrir o app e periodicamente; o aviso pode demorar." else "Permita notificações para receber avisos sobre novos serviços. A verificação em segundo plano pode demorar.", color = Color(0xFFC5DAF7), fontSize = 13.sp, lineHeight = 19.sp)
            if (!notificationsEnabled) OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) { Text("Permitir notificações") }
            TextButton(onClick = onNotificationSettings) { Text("Configurações de notificações") }
        }
    }
    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Buscar aplicativo ou serviço") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(18.dp))
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(message, color = Color(0xFFC5DAF7), fontSize = 13.sp, lineHeight = 19.sp)
    OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Escanear agora") }
    OutlinedButton(onClick = onSystemSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Abrir acessibilidade do Android") }
    when {
        matches == null && !loading -> Text("Não foi possível carregar a lista. Atualize ou abra as configurações do Android.", color = Color(0xFFFFD47B))
        matches?.isEmpty() == true && !loading -> Text(if (query.isBlank()) "Nenhum serviço disponível foi informado pelo Android." else "Nenhum serviço encontrado.", color = Color(0xFFC5DAF7))
        else -> groups?.forEach { (packageName, services) ->
            Surface(shape = RoundedCornerShape(22.dp), color = p.surfaceHigh, border = BorderStroke(1.dp, p.outline), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.background(panelBrush(p)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(services.first().app, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(packageName, color = Color(0xFFACC5DE), fontSize = 11.sp, lineHeight = 15.sp)
                    services.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = p.outline, modifier = Modifier.padding(vertical = 4.dp))
                        Text(item.service, color = Color(0xFFE2EFFB), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.component, color = Color(0xFFACC5DE), fontSize = 11.sp, lineHeight = 15.sp)
                        Text(when (item.enabled) { true -> "Ativado na lista do Android"; false -> "Não ativado na lista do Android"; null -> "Estado não disponível" },
                            color = if (item.enabled == false) Color(0xFFFFD47B) else p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onAction(item, true) }, enabled = !busy && item.enabled != true, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = p.action, contentColor = Color.White)) { Text("Ativar") }
                            OutlinedButton(onClick = { onAction(item, false) }, enabled = !busy && item.enabled != false, modifier = Modifier.weight(1f)) { Text("Desativar") }
                        }
                    }
                }
            }
        }
    }
    Text("Não ativado não significa que o app precise de acessibilidade. Ative apenas serviços confiáveis: eles podem ler a tela e controlar o aparelho. O Android pode omitir serviços, restringir alterações ou exigir ativação manual.", color = Color(0xFFC5DAF7), fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
internal fun ServiceActionConfirmation(action: ServiceAction, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(if (action.enable) "Ativar serviço?" else "Desativar serviço?") },
        text = { Text("${action.item.app} — ${action.item.service}\n${action.item.component}\n\n" +
            if (action.enable) "Serviços de acessibilidade podem ler a tela e controlar o aparelho. Ative apenas se confiar no aplicativo. O Android pode exigir confirmação nas configurações."
            else "Desativar pode interromper funções do aplicativo ou uma ferramenta de acessibilidade. Se o serviço for essencial, mantenha uma alternativa para usar o aparelho. O Shizuku pode parar.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (action.enable) "Tentar ativar" else "Tentar desativar") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } })
}
