package com.example.xgestalkble.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComputerTranslationEndpointTest {
    @Test fun missingAddressHasAnActionableSetupMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) { ComputerTranslationEndpoint.validate(" ") }
        assertEquals("Copy the computer address from the translator's pairing details.", error.message)
    }
    @Test fun permitsPrivateComputerAddresses() {
        listOf("http://192.168.1.5:8765", "http://10.0.2.2:8765", "http://172.16.0.3:8765", "https://127.0.0.1:8765").forEach {
            assertEquals(it, ComputerTranslationEndpoint.validate(it + "/"))
        }
    }
    @Test fun pairingKeyCannotBeSentToPublicHostOrRedirectPath() {
        listOf("http://8.8.8.8:8765", "http://example.com", "file:///secret", "http://192.168.1.5/translate",
            "http://user:password@192.168.1.5", "http://192.168.1.5?token=secret", "http://192.168.1.5:99999", "http://172.32.0.1").forEach {
            assertThrows(IllegalArgumentException::class.java) { ComputerTranslationEndpoint.validate(it) }
        }
    }
}
