package com.example.xgestalkble.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun QrConversationPanel(viewModel: QrConversationViewModel, state: QrChatState) {
    var showInvitation by remember { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.configured && state.roomId == null) viewModel.createSession() }
    LaunchedEffect(state.requests, state.status) {
        if (state.requests.isNotEmpty() || state.status == "active") showInvitation = false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (state.status) {
                "active" -> "${state.guestUids.size} guest${if (state.guestUids.size == 1) "" else "s"} connected"
                "waiting" -> "Waiting for a guest to scan"
                "invite-expired" -> "QR invitation expired"
                "expired" -> "Shared chat expired"
                "closed" -> "Shared chat ended"
                else -> "Connect another phone"
            }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { showInvitation = true }) { Text("QR") }
            TextButton(onClick = { showPeople = true }) { Text(if (state.requests.isNotEmpty()) "${state.requests.size} waiting" else "People") }
            TextButton(onClick = { showOptions = true }) { Text("Options") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.pending > 0) Text("${state.pending} message(s) sending…", style = MaterialTheme.typography.labelSmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.failed.isNotEmpty()) TextButton(onClick = viewModel::retryFailed) { Text("Retry ${state.failed.size} unsent message(s)") }
        if (state.guestTyping) Text("Guest is typing…", style = MaterialTheme.typography.labelMedium)
        if (state.guestCaption.isNotBlank()) Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Guest is speaking… · live words may change", style = MaterialTheme.typography.labelSmall)
                Text(state.guestCaption, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (showPeople) AlertDialog(onDismissRequest = { showPeople = false }, title = { Text("Group people · ${state.guestUids.size}/8 guests") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Approved guests can read the shared conversation, including earlier messages. Approve only people you want in this group.", style = MaterialTheme.typography.bodySmall)
            state.requests.forEach { request ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("${request.name} wants to join", fontWeight = FontWeight.Bold)
                    Text("Approve only the person you are speaking with.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { viewModel.reject(request) }, enabled = !state.busy) { Text("Decline") }
                        Button(onClick = { viewModel.approve(request); showInvitation = false }, enabled = !state.busy && state.guestUids.size < 8) { Text("Allow guest") }
                    }
                }
            }
            state.members.filter { it.uid in state.guestUids }.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(member.name, Modifier.weight(1f))
                    if (state.group) TextButton(onClick = { viewModel.removeGuest(member) }) { Text("Remove") }
                }
            }
            if (state.guestUids.isEmpty() && state.requests.isEmpty()) Text("No guests yet. Show the QR to invite people.")
        } }, confirmButton = { TextButton(onClick = { showPeople = false }) { Text("Done") } })
    if (showOptions) AlertDialog(onDismissRequest = { showOptions = false }, title = { Text("Chat options") },
        text = { Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Review signs before sending", Modifier.weight(1f))
                Switch(checked = state.confirmSigns, onCheckedChange = viewModel::setConfirmSigns)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibrate for messages & guest joins", Modifier.weight(1f))
                Switch(checked = state.alertsEnabled, onCheckedChange = viewModel::setAlerts)
            }
            Text("Use the language button above to choose your text and voice language. Known phrases use bundled translations; other messages keep their original wording.", style = MaterialTheme.typography.bodySmall)
            Text("Read means the conversation was open on the receiving phone.", style = MaterialTheme.typography.bodySmall)
            if (state.roomId != null) TextButton(onClick = { viewModel.endSession(); showOptions = false }) { Text("End group chat for everyone") }
        } }, confirmButton = { TextButton(onClick = { showOptions = false }) { Text("Done") } })
    if (showInvitation) {
        val context = LocalContext.current
        val qr by produceState<Bitmap?>(null, state.inviteUrl) {
            value = state.inviteUrl?.let { url -> withContext(Dispatchers.Default) {
                val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640,
                    mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
                val pixels = IntArray(640 * 640) { index -> if (matrix[index % 640, index / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888)
            } }
        }
        AlertDialog(onDismissRequest = { showInvitation = false },
            title = { Text("Scan to chat") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        !state.configured -> Text("Firebase needs to be configured for this app before a QR can connect two phones. You can still use the local conversation.")
                        state.busy && state.roomId == null -> { CircularProgressIndicator(); Text("Creating a private invitation…") }
                        state.status in listOf("expired", "closed") -> Text("This conversation has ended. Create a new QR to start another conversation.")
                        state.guestUids.size >= 8 -> Text("The group is full (8 guests). Remove a guest from People before inviting someone else.")
                        System.currentTimeMillis() >= state.inviteExpiresAt && state.inviteUrl != null -> Text("This QR invitation expired. Refresh it to invite more people to the same conversation.")
                        qr != null -> {
                            Text("Up to 8 guests can scan this QR with their phone cameras. You approve each person in People. No guest app installation needed.")
                            Spacer(Modifier.height(12.dp))
                            Image(qr!!.asImageBitmap(), "QR invitation to this conversation", Modifier.size(240.dp))
                            Text("QR lasts 10 minutes; group lasts 2 hours. Everyone needs internet. Guests tap Enable glove voice to hear new signs in their chosen language.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Samvaad invitation", state.inviteUrl))
                            }) { Text("Copy invitation link") }
                        }
                    }
                    if (state.group && state.roomId != null && state.status !in listOf("expired", "closed") && state.guestUids.size < 8) {
                        TextButton(onClick = viewModel::refreshInvitation, enabled = !state.busy) { Text("Refresh invitation QR") }
                    }
                    if (state.configured && (state.roomId == null || state.status in listOf("expired", "closed"))) {
                        Button(onClick = { viewModel.endSession(); viewModel.createSession() }, enabled = !state.busy) { Text("Create new QR") }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { showInvitation = false }) { Text("Continue to chat") } })
    }
}
