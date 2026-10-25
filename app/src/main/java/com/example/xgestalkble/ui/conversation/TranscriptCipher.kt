package com.example.xgestalkble.ui.conversation

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated encryption; keys are supplied by Android Keystore. */
internal object TranscriptCipher {
    private val header = "GESTALK-ENC-1\n".toByteArray(UTF_8)
    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= header.size && bytes.copyOfRange(0, header.size).contentEquals(header)

    fun encrypt(bytes: ByteArray, key: SecretKey, id: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // The provider generates a new random IV for every write.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(id.toByteArray(UTF_8))
        return header + cipher.iv + cipher.doFinal(bytes)
    }

    fun decrypt(bytes: ByteArray, key: SecretKey, id: String): ByteArray {
        require(isEncrypted(bytes) && bytes.size >= header.size + 12 + 16) { "Invalid encrypted transcript" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(id.toByteArray(UTF_8))
        return cipher.doFinal(bytes.copyOfRange(header.size + 12, bytes.size))
    }
}
