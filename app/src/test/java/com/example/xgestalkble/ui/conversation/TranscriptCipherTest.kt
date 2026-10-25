package com.example.xgestalkble.ui.conversation

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class TranscriptCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun unicodeTranscriptRoundTripsAndEachWriteHasANewNonce() {
        val key = key()
        val original = "Private conversation · സുഖമാണോ · नमस्ते".toByteArray()
        val first = TranscriptCipher.encrypt(original, key, "conv_1")
        val second = TranscriptCipher.encrypt(original, key, "conv_1")
        assertTrue(TranscriptCipher.isEncrypted(first))
        assertFalse(first.contentEquals(second))
        assertArrayEquals(original, TranscriptCipher.decrypt(first, key, "conv_1"))
        assertFalse(first.toString(Charsets.UTF_8).contains("Private conversation"))
    }

    @Test fun tamperingWrongKeyAndSubstitutingAnotherConversationAreRejected() {
        val key = key()
        val encrypted = TranscriptCipher.encrypt("secret".toByteArray(), key, "conv_1")
        val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { TranscriptCipher.decrypt(tampered, key, "conv_1") }
        assertThrows(Exception::class.java) { TranscriptCipher.decrypt(encrypted, key(), "conv_1") }
        assertThrows(Exception::class.java) { TranscriptCipher.decrypt(encrypted, key, "conv_2") }
        assertThrows(Exception::class.java) { TranscriptCipher.decrypt(encrypted.copyOf(20), key, "conv_1") }
    }
}
