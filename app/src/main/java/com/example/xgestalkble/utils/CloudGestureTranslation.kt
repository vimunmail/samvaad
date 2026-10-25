package com.example.xgestalkble.utils

import android.content.Context
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object CloudGestureTranslation {
    private val requestMutex = Mutex()
    private var lastRequest = 0L
    private fun firebase(context: Context): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == "guest-chat" }?.let { return it }
        val config = JSONObject(context.assets.open("firebase-config.json").bufferedReader().use { it.readText() })
        check(config.optBoolean("enabled")) { "Connect Google Translation in Model translations first." }
        val options = FirebaseOptions.Builder().setApiKey(config.getString("apiKey"))
            .setApplicationId(config.optString("androidAppId").ifBlank { config.getString("appId") })
            .setProjectId(config.getString("projectId")).setGcmSenderId(config.getString("messagingSenderId")).build()
        return FirebaseApp.initializeApp(context, options, "guest-chat")
    }

    suspend fun translate(context: Context, texts: List<String>, language: ChatLanguage): List<String> {
        try {
            val app = firebase(context.applicationContext)
            val auth = FirebaseAuth.getInstance(app)
            if (auth.currentUser == null) auth.signInAnonymously().await()
            // Pace batches and live requests under the server's 20-per-minute phone limit.
            requestMutex.withLock {
                val remaining = 3100L - (SystemClock.elapsedRealtime() - lastRequest)
                if (remaining > 0) delay(remaining)
                lastRequest = SystemClock.elapsedRealtime()
            }
            val result = FirebaseFunctions.getInstance(app, "asia-south1")
                .getHttpsCallable("translateGestureLabels").withTimeout(55, TimeUnit.SECONDS)
                .call(mapOf("texts" to texts, "target" to language.code)).await().data as? Map<*, *>
                ?: error("Incomplete response from Google Translation.")
            check(result["target"] == language.code) { "Translation returned the wrong language." }
            val translated = (result["translations"] as? List<*>)?.map { it as? String ?: error("Invalid translation.") }
                ?: error("Missing translated text.")
            check(translated.size == texts.size && translated.all { it.isNotBlank() }) { "Incomplete translated phrases." }
            return translated
        } catch (error: FirebaseFunctionsException) {
            val message = when (error.code) {
                FirebaseFunctionsException.Code.NOT_FOUND -> "Google Translation setup is not finished. Enable billing and deploy the translation service."
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Translation limit reached. Saved translations still work; retry later."
                else -> "Could not reach Google Translation. Check internet and service setup, then retry."
            }
            throw IllegalStateException(message, error)
        }
    }
}
