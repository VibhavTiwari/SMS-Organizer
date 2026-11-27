package com.vibhavtiwari.smsorganizer.reminders

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vibhavtiwari.smsorganizer.InboxMessage
import com.vibhavtiwari.smsorganizer.core.MessageReminderCandidate
import com.vibhavtiwari.smsorganizer.core.MessageReminderKind
import com.vibhavtiwari.smsorganizer.core.extractMessageReminder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun MessageReminderKind.title() = when (this) {
    MessageReminderKind.BILL_DUE -> "Bill due"
    MessageReminderKind.TRAIN_DEPARTURE -> "Train departure"
}

private fun formatTime(time: LocalDateTime, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a z", Locale.getDefault()).format(time.atZone(zone))

private fun formatEvent(kind: MessageReminderKind, time: LocalDateTime, zone: ZoneId): String =
    if (kind == MessageReminderKind.BILL_DUE)
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.getDefault()).format(time.toLocalDate())
    else formatTime(time, zone)

private fun upcomingCandidate(message: InboxMessage, zone: ZoneId): MessageReminderCandidate? {
    if (!message.incoming) return null
    return extractMessageReminder(message.body, LocalDateTime.now(zone), zone)
}

private fun reminderKey(message: InboxMessage): String {
    // Opaque identifier. The message body and sender are not persisted in reminder records.
    return MessageDigest.getInstance("SHA-256")
        .digest("${message.sentAtMillis}\u0000${message.address}\u0000${message.body}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

@Composable
internal fun ReminderAction(message: InboxMessage) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val possible = remember(message, zone) { upcomingCandidate(message, zone) } ?: return
    val service = remember(context) { ReminderService(context) }
    val scope = rememberCoroutineScope()
    var reviewing by remember(message) { mutableStateOf(false) }
    var saving by remember(message) { mutableStateOf(false) }
    var status by remember(message) { mutableStateOf<String?>(null) }
    var offerPermission by remember(message) { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        offerPermission = false
        status = if (granted) null else "Notifications are off. Turn them on to set a reminder."
    }

    TextButton(onClick = {
        status = null
        reviewing = true
    }) { Text("Review ${possible.kind.title().lowercase(Locale.ROOT)} reminder") }

    if (reviewing) AlertDialog(
        onDismissRequest = { if (!saving) reviewing = false },
        title = { Text("${possible.kind.title()} reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${possible.kind.title()}: ${formatEvent(possible.kind, possible.eventAt, possible.zone)}")
                Text("Notify me: ${formatTime(possible.remindAt, possible.zone)}")
                Text("Check the date and time against the original message.")
                if (!service.exactEnabled()) Text("Android may deliver this reminder late to save battery.")
                status?.let { Text(it) }
                if (offerPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Allow notifications")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { service.confirm(reminderKey(message), possible) }.getOrDefault(ReminderConfirmation.FAILED)
                    }
                    saving = false
                    when (result) {
                        ReminderConfirmation.SAVED -> {
                            status = "Reminder set. Manage it from More → Reminders."
                            reviewing = false
                        }
                        ReminderConfirmation.ALREADY_SET -> {
                            status = "This reminder is already set."
                            reviewing = false
                        }
                        ReminderConfirmation.NOTIFICATIONS_OFF -> {
                            offerPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            status = "Notifications are off. Enable Bill and train reminders in Android notification settings."
                        }
                        ReminderConfirmation.EXPIRED -> status = "This reminder time has passed."
                        ReminderConfirmation.FAILED -> status = "Could not set this reminder. Try again."
                    }
                }
            }) { Text(if (saving) "Setting…" else "Set reminder") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = { reviewing = false }) { Text("Back") }
        },
    )
    if (!reviewing) status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun RemindersScreen() {
    val context = LocalContext.current
    val service = remember(context) { ReminderService(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ConfirmedReminder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service) {
        entries = withContext(Dispatchers.IO) { service.upcoming() }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (entries?.isEmpty() == true) item {
            Text("No upcoming reminders. Open a supported bill or train message to set one.", Modifier.padding(16.dp))
        }
        error?.let { message -> item { Text(message, Modifier.padding(16.dp)) } }
        items(entries.orEmpty(), key = { it.key }) { item ->
            val zone = ZoneId.of(item.zone)
            val event = Instant.ofEpochMilli(item.eventAt).atZone(zone).toLocalDateTime()
            val alarm = Instant.ofEpochMilli(item.remindAt).atZone(zone).toLocalDateTime()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(item.kind.title(), style = MaterialTheme.typography.titleMedium)
                Text(formatEvent(item.kind, event, zone))
                Text("Notify me: ${formatTime(alarm, zone)}")
                TextButton(enabled = cancelling == null, onClick = {
                    cancelling = item.key
                    scope.launch {
                        val (removed, updated) = withContext(Dispatchers.IO) { service.cancel(item.key) to service.upcoming() }
                        entries = updated
                        error = if (removed) null else "Could not cancel this reminder. Try again."
                        cancelling = null
                    }
                }) { Text(if (cancelling == item.key) "Cancelling…" else "Cancel reminder") }
            }
            HorizontalDivider()
        }
    }
}
