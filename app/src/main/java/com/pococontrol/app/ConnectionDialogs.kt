package com.pococontrol.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionSetupDialog(onClose: () -> Unit, status: String, onShizuku: () -> Unit, onOpenShizuku: () -> Unit, onWifi: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, containerColor = Color(0xFF11161D),
        titleContentColor = Color(0xFFF4F8FF), textContentColor = Color(0xFFCAD7E6),
        shape = RoundedCornerShape(26.dp),
        icon = { Icon(Icons.Default.Hub, contentDescription = null, tint = LocalAppPalette.current.accent) },
        title = { Text("Configurar conexões", fontWeight = FontWeight.Bold) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Primeiro, inicie o Shizuku no Android e autorize o POCO CONTROL. A autorização do Shizuku não cria uma conexão Wi-Fi independente.")
            OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abrir Shizuku Next") }
            OutlinedButton(onClick = onShizuku, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Solicitar autorização Shizuku") }
            Text(status)
            Text("Para usar o app sem o Shizuku, configure também o pareamento local de depuração sem fio.")
            OutlinedButton(onClick = onWifi, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Configurar Wi-Fi local") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Concluir") } })
}

@Composable
fun WifiConnectionDialog(connected: Boolean, message: String, busy: Boolean, onClose: () -> Unit,
                         onAndroidSettings: () -> Unit, onPair: (String, String, String) -> Unit, onPairNotice: () -> Unit,
                         onConnect: (String, String) -> Unit, onAutoConnect: () -> Unit, onDisconnect: () -> Unit) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, containerColor = Color(0xFF11161D),
        titleContentColor = Color(0xFFF4F8FF), textContentColor = Color(0xFFCAD7E6),
        shape = RoundedCornerShape(26.dp),
        icon = { Icon(Icons.Default.Wifi, contentDescription = null, tint = LocalAppPalette.current.accent) },
        title = { Text("Pareamento por Wi-Fi", fontWeight = FontWeight.Bold) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Uma notificação do POCO CONTROL ajudará a concluir o pareamento. Android 11 ou superior; é necessário estar conectado ao Wi-Fi.")
            Text("1. Inicie a busca e permita as notificações, se solicitado. O app identificará a tela de código pelo serviço de pareamento do Android.")
            Button(onClick = onPairNotice, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = LocalAppPalette.current.action, contentColor = Color.White)) { Text("Iniciar pareamento") }
            Text("2. Abra as opções do desenvolvedor, ative Depuração sem fio e toque no nome da opção. Escolha ‘Parear dispositivo com código’.")
            OutlinedButton(onClick = onAndroidSettings, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Opções do desenvolvedor") }
            Text("3. Mantenha a tela do código aberta. Quando a notificação disser ‘Serviço de pareamento encontrado’, abra-a e informe os seis dígitos.")
            Text("4. Quando aparecer ‘Pareamento concluído’, toque em Começar na notificação para conectar. Você também pode voltar aqui e iniciar a conexão. A conexão usa outra porta, exibida na tela principal de Depuração sem fio.")
            Button(onClick = onAutoConnect, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = LocalAppPalette.current.action, contentColor = Color.White)) { Text("Iniciar conexão") }
            if (connected) {
                Text("● Conexão local ativa. Ela é independente da autorização do Shizuku.")
                OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Desconectar") }
            }
            Text(message)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showManual) "Ocultar opções manuais" else "Pareamento e conexão manuais")
            }
            if (showManual) {
                Text("Se a busca não encontrar a porta, informe o IP deste celular e os dados exibidos pelo Android. O código não é salvo pelo app.")
                OutlinedTextField(host, { host = it }, label = { Text("IP deste celular") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit).take(5) }, label = { Text("Porta de pareamento") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(pairCode, { pairCode = it.filter(Char::isDigit).take(6) }, label = { Text("Código de pareamento") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
                Button(onClick = { val code = pairCode; pairCode = ""; onPair(host, pairPort, code) }, enabled = !busy && pairCode.length == 6) { Text("Parear manualmente") }
                Text("A porta de conexão é diferente da porta de pareamento e aparece na tela principal de Depuração sem fio.")
                OutlinedTextField(connectPort, { connectPort = it.filter(Char::isDigit).take(5) }, label = { Text("Porta de conexão") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(onClick = { onConnect(host, connectPort) }, enabled = !busy && connectPort.isNotBlank()) { Text("Conectar manualmente") }
            }
            Text("Se a busca não aparecer, permita a execução em segundo plano. A porta pode mudar após reiniciar ou trocar de rede. Sem depuração sem fio, a conexão deixa de funcionar; ela também apresenta riscos de segurança.")
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } })
}
