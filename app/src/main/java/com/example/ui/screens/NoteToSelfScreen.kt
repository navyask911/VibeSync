package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import com.example.util.NoteToSelfCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class NoteToSelfEntry(
    val messageId: String,
    val text: String,
    val timestamp: Long
)

@Composable
fun NoteToSelfScreen(
    currentUserId: String,
    currentUserName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var decryptedNotes by remember { mutableStateOf<List<NoteToSelfEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()

    if (currentUserId.isBlank()) {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Note to Self", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Your local profile ID is not ready yet. Sign in and reopen this screen.")
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    val database = remember(context) { DatingDatabase.getDatabase(context) }
    val noteMatchId = remember(currentUserId) { NoteToSelfCryptoManager.matchId(currentUserId) }
    val storedNotesFlow = remember(database, noteMatchId) {
        database.chatMessageDao().getMessagesForMatch(noteMatchId)
    }
    val storedNotes by storedNotesFlow.collectAsState(initial = emptyList())

    LaunchedEffect(storedNotes, currentUserId) {
        decryptedNotes = withContext(Dispatchers.IO) {
            storedNotes.map { stored ->
                val plaintext = runCatching {
                    NoteToSelfCryptoManager.decrypt(context, currentUserId, stored.text)
                }.getOrElse {
                    "[Unable to decrypt this note on this device]"
                }
                NoteToSelfEntry(stored.messageId, plaintext, stored.timestamp)
            }
        }
    }

    fun saveNote() {
        val plaintext = draft.trim()
        if (plaintext.isBlank() || saving) return
        saving = true
        scope.launch {
            try {
                val ciphertext = withContext(Dispatchers.IO) {
                    NoteToSelfCryptoManager.encrypt(context, currentUserId, plaintext)
                }
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().upsertMessage(
                        ChatMessageEntity(
                            messageId = "note_" + UUID.randomUUID().toString(),
                            matchId = noteMatchId,
                            senderId = currentUserId,
                            text = ciphertext,
                            timestamp = now,
                            isDelivered = true,
                            isRead = true,
                            isEncrypted = true,
                            encryptionProtocol = "Google Tink Hybrid · local-only Note to Self",
                            deliveryState = "LOCAL_ONLY",
                            outboxOwnerProfileId = currentUserId,
                            senderDisplayName = currentUserName,
                            recipientId = currentUserId
                        )
                    )
                }
                draft = ""
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    "The note could not be encrypted and saved securely.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                saving = false
            }
        }
    }

    IncognitoKeyboard {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("← Back") }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Note to Self", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(
                            "Encrypted on this device · Never sent",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("📌", fontSize = 20.sp)
                }

                if (decryptedNotes.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🔐", fontSize = 34.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Your private notes", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Notes are encrypted with your device's Tink key and saved only in the local Room database.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(decryptedNotes, key = { it.messageId }) { note ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(note.text, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                                            .format(Date(note.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Write a private note") },
                        maxLines = 4,
                        enabled = !saving
                    )
                    Button(
                        onClick = { saveNote() },
                        enabled = draft.isNotBlank() && !saving,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
            }
        }
    }
}
