package com.pococontrol.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class AppAppearance(val label: String) {
    LIQUID("Liquid Glass"), MATERIAL("Material 4")
}

internal data class AppPalette(
    val background: List<Color>, val surface: Color, val surfaceHigh: Color,
    val outline: Color, val accent: Color, val accentSoft: Color, val action: Color, val dialog: Color, val liquid: Boolean
)

internal val LocalAppPalette = staticCompositionLocalOf { liquidPalette }
internal val liquidPalette = AppPalette(
    listOf(Color(0xFF102C49), Color(0xFF091B2D), Color(0xFF050D17)),
    Color(0xF0183048), Color(0xF01C3853), Color(0x806CA3C6),
    Color(0xFFBDE8FF), Color(0xFF2D5B7C), Color(0xFF1265C5), Color(0xFF101921), true
)
internal val materialPalette = AppPalette(
    listOf(Color(0xFF101721), Color(0xFF0B111A), Color(0xFF0B111A)),
    Color(0xFF192534), Color(0xFF202F42), Color(0xFF465B70),
    Color(0xFFBCDFFF), Color(0xFF315577), Color(0xFF1764BD), Color(0xFF111820), false
)

// Layered gradients give a glass-like impression without expensive real-time blur.
@Composable
internal fun AppBackdrop(p: AppPalette) {
    val vertical = remember(p) { Brush.verticalGradient(p.background) }
    val glow = remember(p) { Brush.radialGradient(listOf(Color(0x4266C4FC), Color(0x1267B5EC), Color.Transparent), radius = 1000f) }
    val shine = remember(p) { Brush.linearGradient(listOf(Color(0x123CB2EF), Color.Transparent, Color(0x0A6DB6ED))) }
    Box(Modifier.fillMaxSize().background(vertical))
    if (p.liquid) {
        Box(Modifier.fillMaxSize().background(glow))
        Box(Modifier.fillMaxSize().background(shine))
    }
}

@Composable
internal fun panelBrush(p: AppPalette): Brush = remember(p) {
    if (p.liquid) Brush.linearGradient(listOf(Color(0x467AB9D9), Color(0x1D4F82A4), Color(0x07132237)))
    else Brush.verticalGradient(listOf(Color(0x116A92B4), Color.Transparent))
}

@Composable
internal fun AppNavigation(selected: Int, onTab: (Int) -> Unit) {
    val p = LocalAppPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Principal", "Acessibilidade", "Apps instalados").forEachIndexed { index, label ->
            val icon = when (index) { 0 -> Icons.Default.Home; 1 -> Icons.Default.Accessibility; else -> Icons.Default.Apps }
            FilterChip(selected = selected == index, onClick = { onTab(index) }, modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp)) },
                label = { Text(label, fontSize = 12.sp, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Medium) },
                shape = RoundedCornerShape(if (p.liquid) 14.dp else 12.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = p.surface.copy(alpha = .65f), labelColor = Color(0xFFDCEBFA),
                    selectedContainerColor = p.accentSoft, selectedLabelColor = Color.White),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == index,
                    borderColor = p.outline, selectedBorderColor = p.accent.copy(alpha = .65f)))
        }
    }
}

@Composable
private fun ThemeSwatch(option: AppAppearance) {
    val p = if (option == AppAppearance.LIQUID) liquidPalette else materialPalette
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(width = 64.dp, height = 54.dp).background(Brush.verticalGradient(p.background), shape).border(1.dp, p.outline, shape).padding(7.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(16.dp).background(p.surfaceHigh, RoundedCornerShape(5.dp)).background(panelBrush(p), RoundedCornerShape(5.dp)))
            Box(Modifier.fillMaxWidth(.7f).height(8.dp).background(p.action, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
internal fun AppearanceSettings(appearance: AppAppearance, onClose: () -> Unit, onChange: (AppAppearance) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val p = LocalAppPalette.current
    if (!choosing) {
        AlertDialog(
            onDismissRequest = onClose,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            title = { Text("Configurações") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Personalize a aparência do aplicativo.", color = Color(0xFFCAD4E2), fontSize = 14.sp)
                    Surface(
                        onClick = { choosing = true }, shape = RoundedCornerShape(20.dp),
                        color = p.surfaceHigh, border = BorderStroke(1.dp, p.outline), modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, null, tint = p.accent)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Temas", fontWeight = FontWeight.SemiBold)
                                Text(appearance.label, color = Color(0xFFCAD4E2), fontSize = 13.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Escolher tema", tint = p.accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Escolher tema") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Selecione a aparência. A escolha fica salva neste aparelho.", color = Color(0xFFCAD4E2), fontSize = 14.sp, lineHeight = 20.sp)
                    AppAppearance.entries.forEach { option ->
                        val selected = option == appearance
                        Surface(
                            onClick = { onChange(option); choosing = false },
                            shape = RoundedCornerShape(18.dp), color = if (selected) p.accentSoft else p.surfaceHigh,
                            border = BorderStroke(1.dp, if (selected) p.accent else p.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ThemeSwatch(option)
                                Spacer(Modifier.width(8.dp))
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text(if (option == AppAppearance.LIQUID) "Vidro azul com luz e reflexos suaves" else "Superfícies escuras e azul discreto",
                                        color = Color(0xFFD1D8E2), fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                    Text("Material 4 é um tema visual inspirado no Material, não uma versão oficial do sistema.", color = Color(0xFFCAD4E2), fontSize = 12.sp, lineHeight = 17.sp)
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Voltar") } }
        )
    }
}
