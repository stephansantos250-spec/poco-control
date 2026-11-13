package com.pococontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLException

class PairingErrorsTest {
    @Test fun categorizesWrappedFailuresWithoutLeakingText() {
        val f = pairingFailure(IOException("secret-code", ConnectException("secret-host")))
        assertEquals("P01", f.code)
        assertFalse(f.message.contains("secret"))
        assertEquals("P03", pairingFailure(SSLException("hidden")).code)
        assertEquals("P07", pairingFailure(IOException("hidden")).code)
    }
    @Test fun unknownFailureDoesNotExposeMessage() {
        val f = pairingFailure(RuntimeException("private-code-and-ip"))
        assertEquals("P99", f.code)
        assertFalse(f.message.contains("private"))
    }
    @Test fun loopbackIsTriedBeforeLocalInterface() {
        val attempts = mutableListOf<String>()
        pairOnLocalFirst("192.168.1.5") { ip ->
            attempts.add(ip)
            if (ip == "127.0.0.1") throw ConnectException()
        }
        assertEquals(listOf("127.0.0.1", "192.168.1.5"), attempts)
    }
    @Test fun doesNotRetryAuthenticationErrors() {
        val attempts = mutableListOf<String>()
        try { pairOnLocalFirst("192.168.1.5") { ip -> attempts.add(ip); throw IOException() } }
        catch (_: IOException) { }
        assertEquals(listOf("127.0.0.1"), attempts)
    }
    @Test fun detailIncludesTypesButNotSensitiveExceptionText() {
        val err = RuntimeException("codigo=123456", IOException("ip=192.168.1.1"))
        val detail = safeErrorDetail(err)
        assertEquals("Tipo: RuntimeException / Causa: IOException", detail)
        assertFalse(detail.contains("123456")); assertFalse(detail.contains("192.168"))
    }
    @Test fun leadingZeroIsAccepted() { assertTrue(isPairingCode("059206")) }
}
