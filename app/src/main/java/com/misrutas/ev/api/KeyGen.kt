package com.misrutas.ev.api

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** Genera el par de claves EC prime256v1 que Tesla exige publicar en tu dominio. */
object KeyGen {
    fun generate(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        return pem("PUBLIC KEY", kp.public.encoded) to pem("PRIVATE KEY", kp.private.encoded)
    }

    private fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    /** Compara dos PEM ignorando saltos de línea y espacios. */
    fun samePem(a: String, b: String) = a.filterNot { it.isWhitespace() } == b.filterNot { it.isWhitespace() }
}
