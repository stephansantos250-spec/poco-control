package com.pococontrol.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import kotlinx.coroutines.runBlocking
import java.net.NetworkInterface
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ADB is restricted to an address assigned to this device; no arbitrary command UI. */
object LocalWifiAdb {
    @Volatile private var client: Kadb? = null
    val connected: Boolean get() = client?.connectionCheck() == true
    @Volatile private var initialized = false
    @Synchronized fun init(context: Context) { if (!initialized) { KadbCert.configure(EncryptedKeyStore(context.applicationContext)); initialized = true } }
    private fun validate(host: String, port: Int) {
        require(port in 1..65535) { "Porta inválida" }
        val parts = host.split('.')
        require(parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: 256) in 0..255 && it == (it.toIntOrNull() ?: -1).toString() }) { "Informe o IPv4 numérico do próprio celular" }
        val addresses = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress }
        require(host == "127.0.0.1" || addresses.contains(host)) { "Use um endereço IP do próprio celular" }
    }
    internal fun validateEndpoint(host: String, port: Int) = validate(host, port)
    private val pairing = java.util.concurrent.atomic.AtomicBoolean(false)
    fun pair(host: String, port: Int, code: String) {
        validate(host, port); require(isPairingCode(code)) { "O código deve ter 6 dígitos" }
        check(pairing.compareAndSet(false, true)) { "Já existe um pareamento em andamento" }
        try { runBlocking { Kadb.pair(host, port, code, "POCO CONTROL") } } finally { pairing.set(false) }
    }
    // A porta anunciada deve ser do próprio aparelho. Shizuku conecta pelo loopback;
    // só tentamos o IP do aparelho se a conexão TCP for recusada antes do protocolo.
    fun pairLocal(host: String, port: Int, code: String) {
        validate(host, port)
        pairOnLocalFirst(host) { address -> pair(address, port, code) }
    }
    fun connect(host: String, port: Int) {
        validate(host, port)
        val next = Kadb.create(host, port, connectTimeout = 10000, socketTimeout = 10000)
        try {
            require(next.supportsFeature("shell_v2")) { "O Android não oferece shell v2" }
            val probe = next.shell("/system/bin/id -u")
            require(probe.exitCode == 0 && probe.output.trim() == "2000") { "Não foi possível confirmar a identidade shell" }
            val old = client; client = next; old?.close()
        } catch (e: Exception) { next.close(); throw e }
    }
    fun command(args: Array<out String>): String {
        val current = client ?: throw IllegalStateException("Wi-Fi desconectado")
        require(args.firstOrNull() == "/system/bin/settings")
        val safe = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return try {
            val result = current.shell(safe)
            if (result.exitCode != 0) "ERROR:" + result.errorOutput.trim().take(200) else result.output.trim()
        } catch (e: Exception) { client = null; try { current.close() } catch (_: Exception) {}; throw e }
    }
    // The only non-settings command supported here is this fixed, app-owned script.
    internal fun disableAllOrdered(): String {
        val current = client ?: throw IllegalStateException("Wi-Fi desconectado")
        return try {
            val result = current.shell(ModePolicy.fullDisableScript)
            if (result.exitCode != 0) "ERROR:etapa não confirmada" else result.output.trim()
        } catch (e: Exception) { client = null; try { current.close() } catch (_: Exception) {}; throw e }
    }
    fun disconnect() { val previous = client; client = null; try { previous?.close() } catch (_: Exception) {} }
}

private class EncryptedKeyStore(context: Context) : KadbPrivateKeyStore {
    private val prefs = context.getSharedPreferences("local_adb_identity", Context.MODE_PRIVATE)
    private val alias = "poco_control_local_adb_identity"
    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Chave de armazenamento indisponível; refaça o pareamento" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    override fun readPrivateKeyPem(): ByteArray? {
        val encoded = prefs.getString("identity", null) ?: return null
        val all = Base64.decode(encoded, Base64.NO_WRAP); require(all.size > 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            doFinal(all.copyOfRange(12, all.size))
        }
    }
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(privateKeyPem), Base64.NO_WRAP)
        check(prefs.edit().putString("identity", encoded).commit())
    }
    override fun clear() { check(prefs.edit().remove("identity").commit()) }
}
