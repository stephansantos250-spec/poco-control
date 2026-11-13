package com.pococontrol.app

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey

class KadbRsaCompatibilityTest {
    @Test fun derivesPublicKeyWhenPrivateKeyDoesNotExposeCrtInterface() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val wrapped = object : PrivateKey {
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "PKCS#8"
            override fun getEncoded() = pair.private.encoded
        }
        val clazz = Class.forName("com.flyfishxu.kadb.cert.CertUtils")
        val instance = clazz.getField("INSTANCE").get(null)
        val method = clazz.declaredMethods.single { it.name.startsWith("derivePublicKey") }
        val actual = method.invoke(instance, wrapped) as PublicKey
        assertArrayEquals(pair.public.encoded, actual.encoded)
    }
}
