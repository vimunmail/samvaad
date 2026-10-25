package com.example.xgestalkble.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ComputerGestureTranslation {
    private val mutex = Mutex()
    fun settings(context: Context) = context.getSharedPreferences("ComputerTranslation", Context.MODE_PRIVATE)
    fun isConfigured(context: Context): Boolean {
        val prefs = settings(context)
        return runCatching { ComputerTranslationEndpoint.validate(prefs.getString("address", "").orEmpty()) }.isSuccess &&
            Regex("[A-Za-z0-9_-]{24,200}").matches(prefs.getString("key", "").orEmpty())
    }
    fun save(context: Context, address: String, key: String) {
        val validated = ComputerTranslationEndpoint.validate(address)
        require(Regex("[A-Za-z0-9_-]{24,200}").matches(key.trim())) { "Copy the complete pairing key shown on the computer." }
        settings(context).edit().putString("address", validated).putString("key", key.trim()).apply()
    }

    private suspend fun request(context: Context, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val prefs = settings(context)
        check(isConfigured(context)) { "Connect the computer translator first. Open Computer / model setup, then save its address and pairing key." }
        val address = ComputerTranslationEndpoint.validate(prefs.getString("address", "").orEmpty())
        val key = prefs.getString("key", "").orEmpty()
        check(key.isNotBlank()) { "Connect your computer in Model translations first." }
        val connection = URL(address + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 180_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            when (connection.responseCode) {
                401 -> error("Pairing key does not match. Copy the key from the computer again.")
                503 -> error("The computer translator is not ready. Finish its model setup, then retry.")
                429 -> error("The computer is translating another phrase. Please retry shortly.")
                200 -> Unit
                else -> error("The computer could not complete this translation. Check its translator window.")
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 131_072) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 131_073 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 131_072) { "Invalid response from computer translator." }
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Cannot reach the computer. Start its translator and connect both devices to the same Wi-Fi.", error)
        } finally { connection.disconnect() }
    }

    suspend fun test(context: Context) {
        val result = request(context, "/health", null)
        check(result.optString("provider") == "indictrans2" && result.optBoolean("ready")) { "Finish IndicTrans2 model setup on the computer." }
    }

    suspend fun translate(context: Context, texts: List<String>, language: ChatLanguage): List<String> = mutex.withLock {
        val result = request(context, "/translate", JSONObject().put("texts", JSONArray(texts)).put("target", language.code))
        check(result.optString("provider") == "indictrans2" && result.optString("target") == language.code) { "The computer returned the wrong translation language." }
        val values = result.getJSONArray("translations")
        check(values.length() == texts.size) { "The computer returned incomplete translations." }
        (0 until values.length()).map { values.getString(it).also { text ->
            check(text.isNotBlank() && !Regex("<ctrl\\d+>|<unk>", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "The computer returned an invalid translation." }
        } }
    }
}
