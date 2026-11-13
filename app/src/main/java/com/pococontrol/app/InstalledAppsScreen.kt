package com.pococontrol.app

import android.content.Context
import android.util.LruCache
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.accessibilityservice.AccessibilityService
import android.service.notification.NotificationListenerService
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean, val enabled: Boolean)
internal data class ServiceCapabilities(val accessibility: Boolean?, val notificationListener: Boolean?)

internal object AppInventory {
    @Suppress("DEPRECATION")
    fun scan(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33)
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        else pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        return apps.map { info ->
            val name = try { info.loadLabel(pm).toString().ifBlank { info.packageName } } catch (_: Exception) { info.packageName }
            val isSystem = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            InstalledApp(info.packageName, name, isSystem, info.enabled)
        }.distinctBy { it.packageName }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }

    @Suppress("DEPRECATION")
    fun capabilities(context: Context, pkg: String): ServiceCapabilities {
        fun declares(action: String): Boolean? = try {
            val pm = context.packageManager
            val intent = Intent(action).setPackage(pkg)
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS
            val matches = if (android.os.Build.VERSION.SDK_INT >= 33)
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            else pm.queryIntentServices(intent, flags)
            matches.isNotEmpty()
        } catch (_: Exception) { null }
        return ServiceCapabilities(declares(AccessibilityService.SERVICE_INTERFACE), declares(NotificationListenerService.SERVICE_INTERFACE))
    }
}

// Bounded cache avoids repeatedly decoding icons while scrolling long app lists.
internal object InstalledIconCache {
    private val images = LruCache<String, ImageBitmap>(64)
    fun get(pkg: String): ImageBitmap? = images.get(pkg)
    fun put(pkg: String, image: ImageBitmap) { images.put(pkg, image) }
    fun clear() { images.evictAll() }
}

@Composable
private fun InstalledIcon(pkg: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                InstalledIconCache.get(pkg) ?: context.packageManager.getApplicationIcon(pkg)
                    .toBitmap(width = 80, height = 80).asImageBitmap().also { InstalledIconCache.put(pkg, it) }
            } catch (_: Exception) { null }
        }
    }
    val p = LocalAppPalette.current
    Box(Modifier.size(44.dp).background(p.accentSoft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.size(40.dp))
        else Icon(Icons.Default.Apps, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
    }
}

@Composable
internal fun InstalledAppsScreen(appearance: AppAppearance, apps: List<InstalledApp>?, loading: Boolean, message: String,
    onTab: (Int) -> Unit, onRefresh: () -> Unit, onOpenAppInfo: (String) -> Unit,
    onOpenAppNotifications: (String) -> Unit, onOpenAccessibility: () -> Unit, onOpenNotificationAccess: () -> Unit) {
    val palette = if (appearance == AppAppearance.LIQUID) liquidPalette else materialPalette
    var category by remember { mutableStateOf("Usuário") }
    var query by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    val filtered = remember(apps, category, query) {
        apps?.filter { app ->
            (when (category) { "Usuário" -> !app.isSystem; "Sistema" -> app.isSystem; else -> !app.enabled }) &&
                (app.label.contains(query, true) || app.packageName.contains(query, true))
        }
    }
    val selected = selectedPkg?.let { pkg -> apps?.firstOrNull { it.packageName == pkg } }
    BackHandler(enabled = selectedPkg != null) { selectedPkg = null }
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = darkColorScheme(primary = palette.accent, secondary = Color(0xFF4DE48D), background = palette.background.last(), surface = palette.surface,
            surfaceVariant = palette.surfaceHigh, surfaceContainerHigh = palette.dialog, onSurface = Color.White, onSurfaceVariant = Color(0xFFC5DAF7))) {
            Box(Modifier.fillMaxSize()) {
                AppBackdrop(palette)
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp)) {
                    if (selectedPkg == null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Apps, null, tint = palette.accent, modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Apps instalados", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar aplicativos", tint = palette.accent) }
                        }
                        AppNavigation(2, onTab)
                        Spacer(Modifier.height(5.dp))
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Pesquisar apps") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(18.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Usuário", "Sistema", "Desativados").forEach { option ->
                                val label = when (option) { "Usuário" -> "Apps do usuário"; "Sistema" -> "Apps do sistema"; else -> "Apps desativados" }
                                FilterChip(selected = category == option, onClick = { category = option }, label = { Text(label) })
                            }
                        }
                        Text("Lista local deste perfil. Apps desativados também podem aparecer em Usuário ou Sistema.", color = Color(0xFFC5DAF7), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 6.dp))
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank()) Text(message, color = Color(0xFFFFD47B), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 4.dp))
                        when {
                            filtered == null && !loading -> Text("Não foi possível carregar os aplicativos. Toque em atualizar.", color = Color(0xFFFFD47B))
                            filtered?.isEmpty() == true && !loading -> Text("Nenhum aplicativo encontrado nesta categoria.", color = Color(0xFFC5DAF7), modifier = Modifier.padding(vertical = 12.dp))
                            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), modifier = Modifier.weight(1f)) {
                                items(filtered ?: emptyList(), key = { it.packageName }) { app ->
                                    Surface(onClick = { selectedPkg = app.packageName }, shape = RoundedCornerShape(16.dp), color = palette.surfaceHigh,
                                        border = BorderStroke(1.dp, palette.outline), modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.background(panelBrush(palette)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            InstalledIcon(app.packageName)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Text(app.packageName, color = Color(0xFFC5DAF7), fontSize = 11.sp, maxLines = 2, lineHeight = 15.sp)
                                                if (!app.enabled) Text("Desativado", color = Color(0xFFFFD47B), fontSize = 11.sp)
                                            }
                                            Icon(Icons.Default.ChevronRight, contentDescription = "Detalhes de ${app.label}", tint = palette.accent)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedPkg = null }) { Icon(Icons.Default.ArrowBack, contentDescription = "Voltar à lista", tint = palette.accent) }
                            Text("Detalhes do app", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                        if (selected == null) {
                            Text(if (loading) "Atualizando…" else "App não encontrado. Ele pode ter sido removido ou estar em outro perfil.", color = Color(0xFFC5DAF7), modifier = Modifier.padding(12.dp))
                        } else {
                            AppPermissionDetails(selected, message, onOpenAppInfo, onOpenAppNotifications, onOpenAccessibility, onOpenNotificationAccess)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPermissionDetails(app: InstalledApp, message: String, onOpenAppInfo: (String) -> Unit,
    onOpenAppNotifications: (String) -> Unit, onOpenAccessibility: () -> Unit, onOpenNotificationAccess: () -> Unit) {
    val p = LocalAppPalette.current
    val context = LocalContext.current
    val capabilities by produceState<ServiceCapabilities?>(initialValue = null, key1 = app.packageName) {
        value = withContext(Dispatchers.IO) { AppInventory.capabilities(context, app.packageName) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = p.surfaceHigh, border = BorderStroke(1.dp, p.outline), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    InstalledIcon(app.packageName); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(app.packageName, color = Color(0xFFC5DAF7), fontSize = 12.sp, lineHeight = 17.sp)
                        Text((if (app.isSystem) "App do sistema" else "App do usuário") + if (app.enabled) " · ativado" else " · desativado", color = p.accent, fontSize = 12.sp)
                    }
                }
            }
        }
        if (message.isNotBlank()) item { Text(message, color = Color(0xFFFFD47B), fontSize = 13.sp, lineHeight = 19.sp) }
        item {
            PermissionCard("Configurações restritas", "Android 13+: abra as informações do app, toque no menu ⋮ e escolha ‘Permitir configurações restritas’, se a opção aparecer. Faça isso somente se confiar no app. A opção não concede as permissões por si só.", "Abrir informações do app", Icons.Default.Security) { onOpenAppInfo(app.packageName) }
        }
        item {
            val status = when (capabilities?.accessibility) { true -> "Serviço declarado identificado."; false -> "Nenhum serviço identificado nesta consulta."; null -> "Verificação indisponível ou em andamento." }
            PermissionCard("Acessibilidade", "$status Abra a lista do Android e escolha o serviço do app. Serviços podem ler a tela e controlar o aparelho.", "Abrir acessibilidade", Icons.Default.Accessibility) { onOpenAccessibility() }
        }
        item {
            val status = when (capabilities?.notificationListener) { true -> "Serviço de acesso às notificações identificado."; false -> "Nenhum serviço de acesso identificado nesta consulta."; null -> "Verificação indisponível ou em andamento." }
            PermissionCard("Acesso às notificações", "$status Esta permissão permite ao app ler notificações. Abra a lista do Android e escolha o app, se disponível.", "Abrir acesso às notificações", Icons.Default.Notifications) { onOpenNotificationAccess() }
        }
        item { PermissionCard("Notificações do app", "Controle se este app pode enviar notificações. Esta opção é diferente de permitir que ele leia notificações de outros apps.", "Abrir notificações do app", Icons.Default.NotificationsActive) { onOpenAppNotifications(app.packageName) } }
        item { PermissionCard("Outras permissões", "Câmera, arquivos e outras permissões são controladas nas informações do app. A disponibilidade das opções depende do Android e do fabricante.", "Abrir informações do app", Icons.Default.Settings) { onOpenAppInfo(app.packageName) } }
        item { Text("O POCO CONTROL não libera configurações restritas automaticamente nem confirma seu estado. As opções podem mudar conforme o aparelho.", color = Color(0xFFC5DAF7), fontSize = 12.sp, lineHeight = 18.sp) }
    }
}

@Composable
private fun PermissionCard(title: String, description: String, button: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val p = LocalAppPalette.current
    Surface(shape = RoundedCornerShape(20.dp), color = p.surface, border = BorderStroke(1.dp, p.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = p.accent, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(10.dp)); Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Text(description, color = Color(0xFFC5DAF7), fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(button) }
        }
    }
}
