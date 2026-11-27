package com.vibhavtiwari.smsorganizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibhavtiwari.smsorganizer.core.CleanupReason
import java.text.DateFormat
import java.util.Date

@Composable
internal fun InboxHygieneScreen(hygiene: InboxHygiene, tab: InboxHygieneTab, loadMore: (() -> Unit)? = null) {
    val entries = hygiene.entries(tab)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var picked by remember(tab) { mutableStateOf(emptySet<String>()) }
    var hideDialog by remember(tab) { mutableStateOf(false) }
    var viewing by remember(tab) { mutableStateOf<String?>(null) }
    val selected = entries.orEmpty().filter { it.key in picked }.map { it.key }.toSet()

    Column(Modifier.fillMaxSize()) {
        Text(
            when (tab) {
                InboxHygieneTab.SPAM -> "Suspected spam is kept here, not deleted. Choose Not spam to return a message to the inbox."
                InboxHygieneTab.CLEANUP -> "Review OTPs at least 7 days old and promotions at least 30 days old. Hiding a message only affects this app."
                InboxHygieneTab.HIDDEN -> "Messages you hid from the inbox. Restore them at any time. The original messages have not been deleted."
            },
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        hygiene.error?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }
        if (entries == null) {
            if (hygiene.error == null) CircularProgressIndicator(Modifier.padding(16.dp))
            return@Column
        }
        if (tab == InboxHygieneTab.CLEANUP && selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !hygiene.busy, onClick = { picked = emptySet() }) { Text("Clear selection") }
                TextButton(enabled = !hygiene.busy, onClick = { hideDialog = true }) { Text("Hide selected (${selected.size})") }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (entries.isEmpty()) item {
                Text(
                    when (tab) {
                        InboxHygieneTab.SPAM -> "No suspected spam in loaded messages."
                        InboxHygieneTab.CLEANUP -> "No cleanup suggestions in loaded messages."
                        InboxHygieneTab.HIDDEN -> "No hidden items in loaded messages."
                    },
                    Modifier.padding(16.dp),
                )
            }
            items(entries, key = { it.key }) { entry ->
                val message = entry.message
                val checked = entry.key in selected
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (tab == InboxHygieneTab.CLEANUP) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { mark ->
                                picked = if (mark) picked + entry.key else picked - entry.key
                            },
                            enabled = !hygiene.busy,
                        )
                    }
                    Column(Modifier.weight(1f).padding(4.dp)) {
                        Text(message.address, style = MaterialTheme.typography.titleSmall)
                        Text(dateFormat.format(Date(message.sentAtMillis)), style = MaterialTheme.typography.bodySmall)
                        val subtitle = when (tab) {
                            InboxHygieneTab.SPAM -> if (hygiene.isManualSpam(entry)) "Marked as spam" else "Suspected spam"
                            InboxHygieneTab.CLEANUP -> when (entry.cleanup) {
                                CleanupReason.OLD_OTP -> "Old OTP"
                                CleanupReason.OLD_PROMOTION -> "Old promotion"
                                null -> null
                            }
                            InboxHygieneTab.HIDDEN -> null
                        }
                        subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        if (entry.cleanup != CleanupReason.OLD_OTP) {
                            Text(message.body, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            if (tab != InboxHygieneTab.CLEANUP && message.body.isNotBlank()) {
                                TextButton(onClick = { viewing = entry.key }) { Text("Read full message") }
                            }
                        }
                    }
                    TextButton(enabled = !hygiene.busy, onClick = { hygiene.restore(entry) }) {
                        Text(when (tab) {
                            InboxHygieneTab.SPAM -> "Not spam"
                            InboxHygieneTab.CLEANUP -> "Keep"
                            InboxHygieneTab.HIDDEN -> "Restore"
                        })
                    }
                }
                HorizontalDivider()
            }
            if (loadMore != null) item { TextButton(onClick = loadMore) { Text("Load older messages") } }
        }
    }
    entries?.firstOrNull { it.key == viewing }?.let { entry ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(entry.message.address) },
            text = { Text(entry.message.body, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
        )
    }
    if (hideDialog && selected.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { hideDialog = false },
            title = { Text("Hide ${selected.size} selected messages?") },
            text = { Text("Nothing will be deleted. You can bring these messages back from Hidden messages.") },
            dismissButton = { TextButton(onClick = { hideDialog = false }) { Text("Cancel") } },
            confirmButton = { TextButton(enabled = !hygiene.busy, onClick = {
                hygiene.hide(selected)
                hideDialog = false
            }) { Text("Hide messages") } },
        )
    }
}

@Composable
internal fun ReportSpamButton(hygiene: InboxHygiene, message: InboxMessage, onReported: () -> Unit = {}) {
    if (!message.incoming || !hygiene.ready) return
    var confirm by remember(message.id) { mutableStateOf(false) }
    TextButton(enabled = !hygiene.busy, onClick = { confirm = true }) { Text("Move to spam") }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Move this message to spam?") },
        text = { Text("Only this message will move. You can return it from Spam. This will not block or report the sender.") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = !hygiene.busy, onClick = {
                confirm = false
                hygiene.report(message, onReported)
            }) { Text("Move to spam") }
        },
    )
}
