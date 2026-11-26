package com.vibhavtiwari.smsorganizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibhavtiwari.smsorganizer.core.Category

internal data class InboxMessage(
    val id: Long,
    val address: String,
    val body: String,
    val sentAtMillis: Long,
    val category: Category,
)

private val tabLabels = listOf(
    null to "All",
    Category.PERSONAL to "Personal",
    Category.TRANSACTIONS to "Transactions",
    Category.OTP to "OTPs",
    Category.PROMOTIONS to "Promotions",
    Category.BILLS to "Bills",
    Category.TRAVEL to "Travel",
    Category.OTHER to "Other",
)

internal fun inboxResults(messages: List<InboxMessage>, category: Category?, query: String): List<InboxMessage> {
    val needle = query.trim()
    return messages.asSequence()
        .filter { category == null || it.category == category }
        .filter { needle.isEmpty() || it.address.contains(needle, ignoreCase = true) || it.body.contains(needle, ignoreCase = true) }
        .sortedByDescending { it.sentAtMillis }
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxScreen(
    messages: List<InboxMessage>,
    status: String?,
    onOpenArchive: () -> Unit,
    importing: Boolean,
) {
    var activeName by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf("inbox") }
    var address by remember { mutableStateOf("") }
    val active = tabLabels.firstOrNull { it.first?.name == activeName }?.first
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when (screen) {
                    "thread" -> address
                    "compose" -> "New message"
                    else -> "SMS Organizer"
                }) },
                actions = {
                    if (screen == "inbox") TextButton(enabled = !importing, onClick = onOpenArchive) { Text("Open export") }
                },
                navigationIcon = {
                    if (screen != "inbox") TextButton(onClick = { screen = "inbox" }) { Text("Back") }
                },
            )
        },
        floatingActionButton = {
            if (screen == "inbox") {
                ExtendedFloatingActionButton(onClick = { screen = "compose" }, text = { Text("Compose") }, icon = { Text("+") })
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            when (screen) {
                "thread" -> MessageList(inboxResults(messages.filter { it.address == address }, null, ""), null)
                "compose" -> DraftFields()
                else -> {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text("Search messages") }, singleLine = true,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((category, label) in tabLabels) {
                            FilterChip(selected = active == category, onClick = { activeName = category?.name.orEmpty() }, label = { Text(label) })
                        }
                    }
                    if (status != null) Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    val results = remember(messages, active, query) { inboxResults(messages, active, query) }
                    if (results.isEmpty() && (messages.isNotEmpty() || status == null)) {
                        Text("No matching messages", Modifier.padding(16.dp))
                    } else {
                        MessageList(results) { message -> address = message.address; screen = "thread" }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<InboxMessage>, onOpen: ((InboxMessage) -> Unit)?) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { message ->
            val row = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable { onOpen(message) } else Modifier)
            Column(row.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(message.address, style = MaterialTheme.typography.titleSmall)
                Text(message.body, maxLines = if (onOpen != null) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DraftFields() {
    // Don't persist draft contents until the platform storage path has been implemented and reviewed.
    var recipient by androidx.compose.runtime.remember { mutableStateOf("") }
    var body by androidx.compose.runtime.remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), label = { Text("Recipient") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Message") }, minLines = 3)
        Button(onClick = {}, enabled = false) { Text("Send") }
        Text("SMS sending is not enabled in this development build.")
    }
}
