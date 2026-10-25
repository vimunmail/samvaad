package com.example.xgestalkble.ui.conversation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * One saved conversation: a titled list of chat messages with a start/end time.
 * Persisted as an encrypted file under `files/conversations/<id>.json` so the
 * user keeps a durable record of what was said (doctor visits, school, offices).
 */
data class SavedConversation(
    val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    val messages: List<ChatMessage>
) {
    val signCount: Int get() = messages.count { it.source == "sign" }
    val speechCount: Int get() = messages.count { it.source == "speech" }
}

/**
 * Local, offline transcripts, encrypted with a non-exportable Android Keystore key.
 * Plaintext is produced only in memory and for exports explicitly requested by the user.
 */
object ConversationStore {
    private const val KEY_ALIAS = "gestalk.saved-conversations.v1"
    private val safeId = Regex("[A-Za-z0-9_-]{1,96}")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun write(file: File, conversation: SavedConversation) {
        val encrypted = TranscriptCipher.encrypt(toJson(conversation).toString().toByteArray(Charsets.UTF_8), key(), conversation.id)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(encrypted); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun read(file: File): SavedConversation {
        require(safeId.matches(file.nameWithoutExtension))
        val bytes = AtomicFile(file).readFully()
        val encrypted = TranscriptCipher.isEncrypted(bytes)
        val plaintext = if (encrypted) TranscriptCipher.decrypt(bytes, key(), file.nameWithoutExtension) else bytes
        val conversation = fromJson(JSONObject(plaintext.toString(Charsets.UTF_8)))
        require(conversation.id == file.nameWithoutExtension)
        // Migrate older internal plaintext atomically; leave it intact if encryption fails.
        if (!encrypted) write(file, conversation)
        return conversation
    }

    // SECURITY: transcripts are private (doctor visits, personal talks), so they
    // live in INTERNAL storage — unreadable by other apps and USB file browsing.
    // They used to sit in app-external storage, which any app with the storage
    // permission could read on Android 8–10; migrate old files in silently.
    private fun dir(context: Context): File {
        val internal = File(context.filesDir, "conversations").apply { mkdirs() }
        runCatching {
            val legacy = File(context.getExternalFilesDir(null), "conversations")
            if (legacy.isDirectory) {
                legacy.listFiles { f -> f.isFile && f.extension == "json" && safeId.matches(f.nameWithoutExtension) }?.forEach { f ->
                    val dest = File(internal, f.name)
                    if (!dest.exists()) {
                        val conversation = fromJson(JSONObject(f.readText()))
                        require(conversation.id == f.nameWithoutExtension)
                        write(dest, conversation)
                    } else read(dest) // Verify the destination before removing a legacy copy.
                    check(f.delete())
                }
                legacy.delete()
            }
        }
        return internal
    }

    /** Physical file backing a saved conversation (File Manager size/date UI). */
    @Synchronized
    fun fileFor(context: Context, id: String): File {
        require(safeId.matches(id)) { "Invalid conversation identifier" }
        return File(dir(context), "$id.json")
    }

    /** Persist [messages] as a new (or replacement) saved conversation; returns it. */
    @Synchronized
    fun save(
        context: Context,
        messages: List<ChatMessage>,
        title: String,
        id: String = "conv_${System.currentTimeMillis()}"
    ): SavedConversation {
        val conv = SavedConversation(
            id = id,
            title = title.ifBlank { defaultTitle(messages) },
            startedAt = messages.firstOrNull()?.timestamp ?: System.currentTimeMillis(),
            endedAt = messages.lastOrNull()?.timestamp ?: System.currentTimeMillis(),
            messages = messages
        )
        write(fileFor(context, id), conv)
        return conv
    }

    /** All saved conversations, newest first. Corrupt files are skipped. */
    @Synchronized
    fun list(context: Context): List<SavedConversation> =
        dir(context).listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) == "json" }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    /** Conversations whose title or any message text contains [query] (case-insensitive). */
    fun search(context: Context, query: String): List<SavedConversation> {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return list(context)
        return list(context).filter { conv ->
            conv.title.lowercase(Locale.US).contains(q) ||
                conv.messages.any { m ->
                    m.text.lowercase(Locale.US).contains(q) ||
                        (m.translation?.lowercase(Locale.US)?.contains(q) == true)
                }
        }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        AtomicFile(fileFor(context, id)).delete()
    }

    @Synchronized
    fun rename(context: Context, id: String, title: String) {
        val f = fileFor(context, id)
        if (!f.exists()) return
        val conv = runCatching { read(f) }.getOrNull() ?: return
        write(f, conv.copy(title = title.ifBlank { conv.title }))
    }

    /** A readable default name: first sign/phrase, else the date. */
    private fun defaultTitle(messages: List<ChatMessage>): String {
        val firstText = messages.firstOrNull { it.text.isNotBlank() }?.text
        return firstText?.take(28) ?: "Conversation"
    }

    // ── JSON (org.json, matching the rest of the app) ──

    private fun toJson(conv: SavedConversation): JSONObject {
        val arr = JSONArray()
        conv.messages.forEach { m ->
            arr.put(
                JSONObject()
                    .put("text", m.text)
                    .put("fromGlove", m.fromGlove)
                    .put("translation", m.translation ?: JSONObject.NULL)
                    .put("imageAsset", m.imageAsset ?: JSONObject.NULL)
                    .put("timestamp", m.timestamp)
                    .put("id", m.id)
                    .put("source", m.source)
                    .put("senderName", m.senderName ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("id", conv.id)
            .put("title", conv.title)
            .put("startedAt", conv.startedAt)
            .put("endedAt", conv.endedAt)
            .put("messages", arr)
    }

    private fun fromJson(o: JSONObject): SavedConversation {
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            ChatMessage(
                text = m.optString("text"),
                fromGlove = m.optBoolean("fromGlove"),
                translation = m.optString("translation").takeIf { it.isNotEmpty() && !m.isNull("translation") },
                imageAsset = m.optString("imageAsset").takeIf { it.isNotEmpty() && !m.isNull("imageAsset") },
                timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                id = m.optString("id").ifBlank { "saved_${o.optString("id")}_$i" },
                source = m.optString("source").ifBlank { if (m.optBoolean("fromGlove")) "sign" else "speech" },
                senderName = m.optString("senderName").takeIf { it.isNotBlank() && !m.isNull("senderName") }
            )
        }
        return SavedConversation(
            id = o.optString("id"),
            title = o.optString("title"),
            startedAt = o.optLong("startedAt"),
            endedAt = o.optLong("endedAt"),
            messages = messages
        )
    }
}
