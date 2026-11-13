package com.pococontrol.app

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

internal data class PairingFailure(val code: String, val message: String)

/** Returns only a safe diagnostic category. Never includes exception text or pairing codes. */
internal fun pairingFailure(error: Throwable): PairingFailure {
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    return when {
        chain.any { it is ConnectException } -> PairingFailure("P01", "P01: A porta não respondeu. Gere um novo código e mantenha a janela aberta.")
        chain.any { it is SocketTimeoutException } -> PairingFailure("P02", "P02: A conexão demorou. Gere um novo código e tente novamente.")
        chain.any { it is SSLException || it is LinkageError } -> PairingFailure("P03", "P03: Falha na conexão segura. Pode haver incompatibilidade no aparelho.")
        chain.any { it is SecurityException || it is java.security.GeneralSecurityException } -> PairingFailure("P04", "P04: Falha de segurança ou na chave local do app. Não apague os dados sem antes verificar as restaurações salvas.")
        chain.any { it is IllegalArgumentException } -> PairingFailure("P05", "P05: Os dados de conexão são inválidos. Gere outro código; no modo manual use o IP deste celular e a porta de pareamento.")
        chain.any { it is IllegalStateException } -> PairingFailure("P06", "P06: Outra tentativa pode estar em andamento. Aguarde e confira o pareamento no Android.")
        chain.any { it is IOException } -> PairingFailure("P07", "P07: A troca de pareamento falhou. O código pode ter expirado, ou houve incompatibilidade. Gere outro código e mantenha a janela aberta.")
        else -> PairingFailure("P99", "P99: Falha não identificada. Gere outro código e tente novamente; informe este número ao suporte.")
    }
}

/** Retry on the local interface only if loopback could not make a TCP connection. */
internal inline fun pairOnLocalFirst(host: String, attempt: (String) -> Unit) {
    try { attempt("127.0.0.1") }
    catch (e: ConnectException) {
        if (host == "127.0.0.1") throw e
        attempt(host)
    }
}

/** Não inclui mensagens de exceção, código, IP, porta ou dados pessoais. */
internal fun safeErrorDetail(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    fun safeName(t: Throwable): String = t.javaClass.simpleName.take(48)
        .filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "Erro" }
    return "Tipo: " + safeName(chain.first()) + " / Causa: " + safeName(chain.last())
}
