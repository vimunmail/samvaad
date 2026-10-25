package com.example.xgestalkble.ui.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.ComputerGestureTranslation
import com.example.xgestalkble.utils.GestureTranslator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ComputerGestureTranslationTest {
    @Test fun missingPairingIsReportedBeforeConnectingAndSigningDoesNotRaiseAnError() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ComputerGestureTranslation.settings(context)
        val oldAddress = prefs.getString("address", null)
        val oldKey = prefs.getString("key", null)
        GestureTranslator.ensureLoaded(context)
        val oldProvider = GestureTranslator.useComputer
        val oldPhone = GestureTranslator.usePhone
        try {
            prefs.edit().remove("address").remove("key").commit()
            GestureTranslator.useComputer = true
            GestureTranslator.usePhone = false
            assertFalse(ComputerGestureTranslation.isConfigured(context))
            try { ComputerGestureTranslation.test(context); fail("Missing setup should stop before HTTP") }
            catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("Connect the computer translator first")) }
            repeat(3) { GestureTranslator.speak("A previously unseen regression test sign", ChatLanguage.MALAYALAM) }
            assertTrue(GestureTranslator.status.value.contains("Set up the computer translator"))
            assertNull(GestureTranslator.error.value)
            assertEquals("നമസ്കാരം", GestureTranslator.savedTranslation("HELLO", ChatLanguage.MALAYALAM))
        } finally {
            GestureTranslator.useComputer = oldProvider
            GestureTranslator.usePhone = oldPhone
            prefs.edit().putString("address", oldAddress).putString("key", oldKey).commit()
        }
    }

    @Test fun phonePairsAndSendsCompleteEnglishPhraseForMalayalam() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ComputerGestureTranslation.settings(context)
        val oldAddress = prefs.getString("address", null)
        val oldKey = prefs.getString("key", null)
        val key = "test-pairing-key-for-computer-1234"
        val executor = Executors.newSingleThreadExecutor()
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        try {
            val served = executor.submit<Boolean> {
                repeat(3) { index ->
                    listener.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val start = reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: error("Missing HTTP headers")
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        assertEquals("Bearer $key", headers["authorization"])
                        val response = if (index == 0) {
                            assertTrue(start.startsWith("GET /health "))
                            """{"provider":"indictrans2","ready":true}"""
                        } else {
                            assertTrue(start.startsWith("POST /translate "))
                            val chars = CharArray(headers.getValue("content-length").toInt())
                            var count = 0
                            while (count < chars.size) { val next = reader.read(chars, count, chars.size - count); check(next > 0); count += next }
                            val payload = JSONObject(String(chars))
                            assertEquals("ml", payload.getString("target"))
                            assertEquals(if (index == 1) "I do not want water." else "HELLO", payload.getJSONArray("texts").getString(0))
                            if (index == 1) """{"provider":"indictrans2","target":"ml","translations":["എനിക്ക് വെള്ളം വേണ്ട"]}"""
                            else """{"provider":"indictrans2","target":"ml","translations":["കമ്പ്യൂട്ടർ പരിശോധന"]}"""
                        }.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().write(response)
                    }
                }
                true
            }
            ComputerGestureTranslation.save(context, "http://127.0.0.1:${listener.localPort}", key)
            ComputerGestureTranslation.test(context)
            assertEquals(listOf("എനിക്ക് വെള്ളം വേണ്ട"), ComputerGestureTranslation.translate(context, listOf("I do not want water."), ChatLanguage.MALAYALAM))
            GestureTranslator.ensureLoaded(context)
            assertEquals("നമസ്കാരം", GestureTranslator.translateText("HELLO", ChatLanguage.MALAYALAM))
            assertEquals("കമ്പ്യൂട്ടർ പരിശോധന", GestureTranslator.translateText("HELLO", ChatLanguage.MALAYALAM, testComputer = true))
            assertTrue(served.get(10, TimeUnit.SECONDS))
        } finally {
            listener.close()
            executor.shutdownNow()
            prefs.edit().putString("address", oldAddress).putString("key", oldKey).commit()
        }
    }
}
