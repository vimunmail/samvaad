package com.example.xgestalkble.ui.conversation

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateListOf
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Date
import com.example.xgestalkble.utils.LanguageConfig

data class GuestJoinRequest(val uid: String, val name: String)
data class QrChatState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val roomId: String? = null,
    val inviteUrl: String? = null,
    val inviteExpiresAt: Long = 0,
    val expiresAt: Long = 0,
    val status: String = "local",
    val requests: List<GuestJoinRequest> = emptyList(),
    val incoming: List<ChatMessage> = emptyList(),
    val pending: Int = 0,
    val failed: List<ChatMessage> = emptyList(),
    val error: String? = null,
    val delivery: Map<String, String> = emptyMap(),
    val guestTyping: Boolean = false,
    val guestCaption: String = "",
    val guestLanguage: String = "en",
    val alertsEnabled: Boolean = true,
    val confirmSigns: Boolean = true,
    val pendingGesture: ChatMessage? = null,
    val group: Boolean = false,
    val guestUids: List<String> = emptyList(),
    val members: List<GuestJoinRequest> = emptyList()
)

/** Separate cloud session state survives recomposition and activity rotation. */
class QrConversationViewModel(application: Application) : AndroidViewModel(application) {
    val localMessages = mutableStateListOf<ChatMessage>()
    private val mutableState = MutableStateFlow(QrChatState())
    val state = mutableState.asStateFlow()
    private var auth: FirebaseAuth? = null
    private var db: FirebaseFirestore? = null
    private var guestUrl = ""
    private val listeners = mutableListOf<ListenerRegistration>()
    private val sending = mutableSetOf<String>()
    private var expiryJob: Job? = null
    private var operationJob: Job? = null
    private var generation = 0
    private val preferences = application.getSharedPreferences("qr-chat-accessibility", Context.MODE_PRIVATE)
    private var foreground = false
    private var typingUntil = 0L
    private var localCaption = ""
    private var lastPresence = ""
    private var partnerUpdatedAt = 0L
    private var presenceJob: Job? = null
    private val acknowledged = mutableMapOf<String, Boolean>()
    private val acknowledging = mutableSetOf<String>()
    private var seenIncoming: Set<String>? = null
    private val seenRequests = mutableSetOf<String>()

    init {
        mutableState.update { it.copy(alertsEnabled = preferences.getBoolean("vibrate", true), confirmSigns = preferences.getBoolean("confirmSigns", true)) }
        runCatching {
            val config = JSONObject(application.assets.open("firebase-config.json").bufferedReader().use { it.readText() })
            if (!config.optBoolean("enabled")) return@runCatching
            guestUrl = config.getString("guestChatUrl").trimEnd('/')
            require(guestUrl.startsWith("https://")) { "Guest chat needs an HTTPS hosting URL." }
            val options = FirebaseOptions.Builder()
                .setApiKey(config.getString("apiKey"))
                .setApplicationId(config.optString("androidAppId").ifBlank { config.getString("appId") })
                .setProjectId(config.getString("projectId"))
                .setGcmSenderId(config.getString("messagingSenderId"))
                .build()
            // Public Firebase client configuration; access is enforced by Firestore rules.
            val firebase = FirebaseApp.getApps(application).firstOrNull { it.name == "guest-chat" }
                ?: FirebaseApp.initializeApp(application, options, "guest-chat")
            auth = FirebaseAuth.getInstance(firebase)
            db = FirebaseFirestore.getInstance(firebase).apply {
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            }
            mutableState.update { it.copy(configured = true) }
        }.onFailure { error -> mutableState.update { it.copy(error = "Firebase setup: ${error.message}") } }
    }

    fun createSession() {
        val firestore = db ?: return
        val authentication = auth ?: return
        if (state.value.busy || state.value.roomId != null) return
        val currentGeneration = ++generation
        mutableState.update { it.copy(busy = true, error = null) }
        operationJob = viewModelScope.launch {
            try {
                val uid = authentication.currentUser?.uid ?: authentication.signInAnonymously().await().user!!.uid
                val now = System.currentTimeMillis()
                val room = firestore.collection("rooms").document()
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val invitationExpiry = now + 10 * 60_000
                val roomExpiry = now + 2 * 60 * 60_000
                firestore.runBatch { batch ->
                    batch.set(room, mapOf("hostUid" to uid, "guestUid" to null, "guestUids" to emptyList<String>(), "status" to "waiting",
                        "createdAt" to FieldValue.serverTimestamp(), "expiresAt" to Timestamp(Date(roomExpiry))))
                    batch.set(firestore.collection("invites").document(token),
                        mapOf("roomId" to room.id, "expiresAt" to Timestamp(Date(invitationExpiry))))
                }.await()
                if (currentGeneration != generation) {
                    room.update("status", "closed")
                    return@launch
                }
                mutableState.value = QrChatState(configured = true, roomId = room.id,
                    inviteUrl = "$guestUrl/#invite=$token", inviteExpiresAt = invitationExpiry,
                    expiresAt = roomExpiry, status = "waiting", group = true, alertsEnabled = state.value.alertsEnabled,
                    confirmSigns = state.value.confirmSigns, pendingGesture = state.value.pendingGesture)
                lastPresence = ""
                presenceJob = viewModelScope.launch {
                    while (currentGeneration == generation) { publishPresence(); delay(1_500) }
                }
                listeners += room.addSnapshotListener { snapshot, error ->
                    if (currentGeneration != generation) return@addSnapshotListener
                    if (error != null) report(error)
                    else snapshot?.getString("status")?.let { status ->
                        if (status == "active" && state.value.status != "active") vibrate()
                        val guests = (snapshot.get("guestUids") as? List<*>)?.filterIsInstance<String>()
                            ?: listOfNotNull(snapshot.getString("guestUid"))
                        mutableState.update { it.copy(status = status, guestUids = guests,
                            members = it.members.filter { member -> member.uid in guests }) }
                        if (status == "active") acknowledgeIncoming()
                    }
                }
                listeners += room.collection("requests").addSnapshotListener { snapshot, error ->
                    if (currentGeneration != generation) return@addSnapshotListener
                    if (error != null) report(error)
                    else {
                        val pendingRequests = snapshot?.documents.orEmpty().filter { it.getString("status") == "pending" }
                        if (pendingRequests.any { it.id !in seenRequests }) vibrate()
                        seenRequests.addAll(pendingRequests.map { it.id })
                        mutableState.update { current -> current.copy(requests =
                        if (current.status !in listOf("waiting", "active", "invite-expired")) emptyList() else snapshot?.documents.orEmpty()
                            .filter { it.getString("status") == "pending" }
                            .map { GuestJoinRequest(it.id, it.getString("name") ?: "Guest") },
                            members = snapshot?.documents.orEmpty().filter { it.getString("status") == "approved" }
                                .map { GuestJoinRequest(it.id, it.getString("name") ?: "Guest") }) }
                    }
                }
                // Last 200 messages bound listener reads. Existing local transcript is kept separately.
                listeners += room.collection("messages").orderBy("createdAt").limitToLast(200)
                    .addSnapshotListener { snapshot, error ->
                        if (currentGeneration != generation) return@addSnapshotListener
                        if (error != null) {
                            if (state.value.status != "closed" && System.currentTimeMillis() < roomExpiry) report(error)
                        } else {
                            val incoming = snapshot?.documents.orEmpty()
                            .filter { it.getString("senderUid") != uid }
                            .map { document -> ChatMessage(
                                text = document.getString("text") ?: "", fromGlove = false,
                                imageAsset = document.getString("imageAsset")?.takeIf { it.isNotBlank() },
                                timestamp = document.getTimestamp("createdAt")?.toDate()?.time
                                    ?: document.getLong("clientTime") ?: System.currentTimeMillis(),
                                id = document.id, source = "guest", senderName = document.getString("senderName")) }
                            if (seenIncoming != null && incoming.any { it.id !in seenIncoming!! }) vibrate()
                            seenIncoming = incoming.map { it.id }.toSet()
                            mutableState.update { it.copy(incoming = incoming) }
                            acknowledgeIncoming()
                        }
                    }
                listeners += room.collection("participants").addSnapshotListener { snapshot, error ->
                    if (currentGeneration != generation) return@addSnapshotListener
                    if (error != null) { if (state.value.status == "active") report(error); return@addSnapshotListener }
                    val guests = snapshot?.documents.orEmpty().filter { it.id != uid && it.id in state.value.guestUids }
                    val guest = guests.filter { System.currentTimeMillis() - (it.getTimestamp("updatedAt")?.toDate()?.time ?: 0L) < 8_000 }
                        .firstOrNull { !it.getString("caption").isNullOrBlank() || it.getBoolean("typing") == true }
                    partnerUpdatedAt = guest?.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                    val fresh = System.currentTimeMillis() - partnerUpdatedAt < 8_000
                    mutableState.update { it.copy(guestTyping = fresh && guest?.getBoolean("typing") == true,
                        guestCaption = if (fresh) guest?.getString("caption") ?: "" else "",
                        guestLanguage = guest?.getString("language") ?: "en") }
                }
                listeners += room.collection("receipts").orderBy("deliveredAt").limitToLast(1800).addSnapshotListener { snapshot, error ->
                    if (currentGeneration != generation) return@addSnapshotListener
                    if (error != null) { if (state.value.status == "active") report(error); return@addSnapshotListener }
                    val updates = snapshot?.documents.orEmpty().filter { it.getString("receiverUid") != uid }
                        .groupBy { it.getString("messageId") ?: it.id }.mapValues { (_, receipts) ->
                        val people = receipts.groupBy { it.getString("receiverUid") }
                        val readCount = people.values.count { rows -> rows.any { it.getTimestamp("readAt") != null } }
                        if (state.value.group) if (readCount > 0) "Read by $readCount" else "Delivered to ${people.size}"
                        else if (readCount > 0) "Read" else "Delivered"
                    }
                    mutableState.update { it.copy(delivery = it.delivery + updates) }
                }
                expiryJob = viewModelScope.launch {
                    while (currentGeneration == generation) {
                        delay(1_000)
                        if (System.currentTimeMillis() - partnerUpdatedAt >= 8_000) mutableState.update { it.copy(guestTyping = false, guestCaption = "") }
                        if (System.currentTimeMillis() >= roomExpiry) {
                            mutableState.update { it.copy(status = "expired", error = "Shared chat expired. Start a new QR session.") }
                            stopListeners()
                            break
                        }
                        if (state.value.status == "waiting" && System.currentTimeMillis() >= state.value.inviteExpiresAt) {
                            mutableState.update { it.copy(status = "invite-expired", requests = emptyList()) }
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (currentGeneration == generation) report(error) }
            finally { if (currentGeneration == generation) mutableState.update { it.copy(busy = false) } }
        }
    }

    fun approve(request: GuestJoinRequest) {
        val firestore = db ?: return
        val roomId = state.value.roomId ?: return
        if (state.value.busy || state.value.status !in listOf("waiting", "active", "invite-expired")) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val room = firestore.collection("rooms").document(roomId)
                firestore.runTransaction { transaction ->
                    val current = transaction.get(room)
                    val join = room.collection("requests").document(request.uid)
                    check(transaction.get(join).getString("status") == "pending") { "This request is no longer pending." }
                    val guests = (current.get("guestUids") as? List<*>)?.filterIsInstance<String>()
                    if (guests != null) {
                        check(guests.size < 8 && request.uid !in guests) { "The group is full (8 guests)." }
                        transaction.update(room, mapOf("guestUid" to request.uid, "guestUids" to guests + request.uid, "status" to "active"))
                    } else transaction.update(room, mapOf("guestUid" to request.uid, "status" to "active"))
                    transaction.update(join, "status", "approved")
                }.await()
            } catch (error: Exception) { report(error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun reject(request: GuestJoinRequest) {
        val firestore = db ?: return
        val roomId = state.value.roomId ?: return
        firestore.collection("rooms").document(roomId).collection("requests").document(request.uid)
            .update("status", "rejected").addOnFailureListener { report(it) }
    }

    fun removeGuest(member: GuestJoinRequest) {
        val roomId = state.value.roomId ?: return
        val firestore = db ?: return
        firestore.runBatch { batch ->
            val room = firestore.collection("rooms").document(roomId)
            batch.update(room, "guestUids", FieldValue.arrayRemove(member.uid))
            batch.update(room.collection("requests").document(member.uid), "status", "removed")
        }.addOnFailureListener { report(it) }
    }

    fun refreshInvitation() {
        val roomId = state.value.roomId ?: return
        val firestore = db ?: return
        if (state.value.busy || state.value.status in listOf("closed", "expired") || state.value.guestUids.size >= 8) return
        mutableState.update { it.copy(busy = true, error = null) }
        val currentGeneration = generation
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val expires = minOf(System.currentTimeMillis() + 600_000, state.value.expiresAt)
        val previousToken = state.value.inviteUrl?.substringAfter("#invite=")
        firestore.runBatch { batch ->
            batch.set(firestore.collection("invites").document(token), mapOf("roomId" to roomId, "expiresAt" to Timestamp(Date(expires))))
            if (previousToken != null) batch.update(firestore.collection("invites").document(previousToken), "revoked", true)
        }
            .addOnSuccessListener {
                if (currentGeneration == generation) mutableState.update { it.copy(inviteUrl = "$guestUrl/#invite=$token", inviteExpiresAt = expires,
                    status = if (it.status == "invite-expired") "waiting" else it.status) }
            }.addOnFailureListener { if (currentGeneration == generation) report(it) }
            .addOnCompleteListener { if (currentGeneration == generation) mutableState.update { it.copy(busy = false) } }
    }

    fun send(message: ChatMessage) {
        val firestore = db ?: return
        val roomId = state.value.roomId ?: return
        val uid = auth?.currentUser?.uid ?: return
        if (state.value.status !in listOf("waiting", "active", "invite-expired") || !sending.add(message.id)) return
        val currentGeneration = generation
        mutableState.update { it.copy(pending = it.pending + 1, error = null, delivery = it.delivery + (message.id to "Sending…"), failed = it.failed.filterNot { m -> m.id == message.id }) }
        viewModelScope.launch {
            try {
                val document = firestore.collection("rooms").document(roomId).collection("messages").document(message.id)
                // A transaction makes retrying the same message id safe without editable messages.
                firestore.runTransaction { transaction ->
                    if (!transaction.get(document).exists()) transaction.set(document, mutableMapOf<String, Any>(
                        "senderUid" to uid, "kind" to when (message.source) { "sign" -> "sign"; "speech" -> "speech"; else -> "text" },
                        "text" to message.text.take(2000), "translation" to (message.translation ?: "").take(2000),
                        "imageAsset" to (message.imageAsset ?: ""), "createdAt" to FieldValue.serverTimestamp(),
                        "clientTime" to message.timestamp).apply {
                            if (message.source == "sign" && message.translations.isNotEmpty()) {
                                put("translations", message.translations)
                            }
                        })
                }.await()
                if (currentGeneration == generation) mutableState.update { it.copy(delivery =
                    it.delivery + (message.id to (it.delivery[message.id]?.takeIf { value -> value.startsWith("Delivered") || value.startsWith("Read") } ?: "Sent"))) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentGeneration == generation) {
                    mutableState.update { it.copy(failed = it.failed + message, delivery = it.delivery + (message.id to "Retry")) }
                    report(error)
                }
            } finally {
                sending.remove(message.id)
                if (currentGeneration == generation) mutableState.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
            }
        }
    }

    fun retryFailed() { state.value.failed.toList().forEach(::send) }

    fun reviewGesture(message: ChatMessage) {
        if (state.value.pendingGesture == null) mutableState.update { it.copy(pendingGesture = message) }
    }
    fun clearGesture() { mutableState.update { it.copy(pendingGesture = null) } }
    fun setConfirmSigns(enabled: Boolean) {
        preferences.edit().putBoolean("confirmSigns", enabled).apply()
        mutableState.update { it.copy(confirmSigns = enabled) }
    }
    fun setAlerts(enabled: Boolean) {
        preferences.edit().putBoolean("vibrate", enabled).apply()
        mutableState.update { it.copy(alertsEnabled = enabled) }
        if (enabled) vibrate()
    }
    @Suppress("DEPRECATION")
    private fun vibrate() {
        if (!state.value.alertsEnabled) return
        val application = getApplication<Application>()
        val vibrator = if (Build.VERSION.SDK_INT >= 31) (application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 60, 120), -1))
        else vibrator.vibrate(longArrayOf(0, 120, 60, 120), -1)
    }
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (!visible) { typingUntil = 0; localCaption = "" }
        if (visible) acknowledgeIncoming()
        publishPresence()
    }
    fun updateTyping(text: String) { typingUntil = if (text.isBlank()) 0 else System.currentTimeMillis() + 3_000 }
    fun updateCaption(text: String) { localCaption = text.take(2000) }
    private fun publishPresence() {
        val roomId = state.value.roomId ?: return
        val uid = auth?.currentUser?.uid ?: return
        if (state.value.status !in listOf("waiting", "active", "invite-expired")) return
        val typing = foreground && System.currentTimeMillis() < typingUntil
        val caption = if (foreground) localCaption else ""
        val language = LanguageConfig.voiceLanguage.code
        val fingerprint = "$language|$typing|$caption"
        if (fingerprint == lastPresence && !typing && caption.isBlank()) return
        lastPresence = fingerprint
        val currentGeneration = generation
        db?.collection("rooms")?.document(roomId)?.collection("participants")?.document(uid)?.set(mapOf(
            "language" to language, "typing" to typing, "caption" to caption, "updatedAt" to FieldValue.serverTimestamp()))
            ?.addOnFailureListener { if (currentGeneration == generation && state.value.status == "active") report(it) }
    }
    private fun acknowledgeIncoming() {
        val firestore = db ?: return
        val roomId = state.value.roomId ?: return
        val uid = auth?.currentUser?.uid ?: return
        if (state.value.status != "active") return
        val currentGeneration = generation
        state.value.incoming.forEach { message ->
            if (acknowledged[message.id] == true || (!foreground && acknowledged.containsKey(message.id)) || !acknowledging.add(message.id)) return@forEach
            val read = foreground
            viewModelScope.launch {
                try {
                    val receipt = firestore.collection("rooms").document(roomId).collection("receipts").document("${message.id}_$uid")
                    firestore.runTransaction { transaction ->
                        val existing = transaction.get(receipt)
                        if (!existing.exists()) transaction.set(receipt, mapOf("messageId" to message.id, "receiverUid" to uid,
                            "deliveredAt" to FieldValue.serverTimestamp(), "readAt" to if (read) FieldValue.serverTimestamp() else null))
                        else if (read && existing.getTimestamp("readAt") == null) transaction.update(receipt, "readAt", FieldValue.serverTimestamp())
                    }.await()
                    if (currentGeneration == generation) acknowledged[message.id] = read
                } catch (_: Exception) { /* Delivery status must not interrupt the conversation. */ }
                finally {
                    acknowledging.remove(message.id)
                    if (currentGeneration == generation && !read && foreground && acknowledged.containsKey(message.id)) acknowledgeIncoming()
                }
            }
        }
    }

    fun endSession() {
        val roomId = state.value.roomId
        ++generation
        operationJob?.cancel()
        expiryJob?.cancel()
        presenceJob?.cancel()
        stopListeners()
        // Server rules also enforce a two-hour maximum if the device is offline at exit.
        if (roomId != null) db?.collection("rooms")?.document(roomId)?.update("status", "closed")
        sending.clear()
        acknowledged.clear(); acknowledging.clear(); seenIncoming = null; seenRequests.clear()
        typingUntil = 0; localCaption = ""; lastPresence = ""; partnerUpdatedAt = 0
        mutableState.value = QrChatState(configured = db != null, alertsEnabled = state.value.alertsEnabled,
            confirmSigns = state.value.confirmSigns)
    }

    private fun stopListeners() { listeners.forEach { it.remove() }; listeners.clear(); presenceJob?.cancel() }
    private fun report(error: Exception) {
        mutableState.update { it.copy(error = "Shared chat: ${error.localizedMessage ?: "Connection failed. Check internet and retry."}") }
    }
    override fun onCleared() { endSession(); super.onCleared() }
}
