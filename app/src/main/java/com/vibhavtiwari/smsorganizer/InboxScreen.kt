package com.vibhavtiwari.smsorganizer

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibhavtiwari.smsorganizer.core.Category
import com.vibhavtiwari.smsorganizer.otp.OtpNotificationScreen
import com.vibhavtiwari.smsorganizer.telephony.CarrierSmsSender
import com.vibhavtiwari.smsorganizer.telephony.ScheduledSmsService

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
    schedules: ScheduledSmsService,
    sender: CarrierSmsSender? = null,
    onSendStateChanged: () -> Unit = {},
    loadMore: (() -> Unit)? = null,
) {
    var activeName by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf("inbox") }
    var address by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var sendPending by remember { mutableStateOf(false) }
    val backToInbox = {
        if (screen == "scheduled") onSendStateChanged()
        screen = "inbox"
    }
    BackHandler(enabled = screen != "inbox") {
        if (!sendPending) backToInbox()
    }
    val active = tabLabels.firstOrNull { it.first?.name == activeName }?.first
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when (screen) {
                    "thread" -> address
                    "compose" -> "New message"
                    "scheduled" -> "Scheduled"
                    "notifications" -> "Notifications"
                    else -> "SMS Organizer"
                }) },
                actions = {
                    if (screen == "inbox") {
                        TextButton(onClick = { menuOpen = true }) { Text("More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Scheduled messages") }, onClick = { menuOpen = false; screen = "scheduled" })
                            DropdownMenuItem(text = { Text("Notifications") }, onClick = { menuOpen = false; screen = "notifications" })
                            if (sender == null) DropdownMenuItem(text = { Text("Open export") }, enabled = !importing,
                                onClick = { menuOpen = false; onOpenArchive() })
                        }
                    }
                },
                navigationIcon = {
                    if (screen != "inbox") TextButton(enabled = !sendPending, onClick = backToInbox) { Text("Back") }
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
                "scheduled" -> ScheduledSmsScreen(schedules, sender)
                "notifications" -> OtpNotificationScreen()
                "compose" -> ComposerScreen(sender, schedules, onSendStateChanged, { screen = "scheduled" }) { sendPending = it }
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
                    if (results.isEmpty() && loadMore == null && (messages.isNotEmpty() || status == null)) {
                        Text("No matching messages", Modifier.padding(16.dp))
                    } else {
                        MessageList(results, { message -> address = message.address; screen = "thread" }, loadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<InboxMessage>, onOpen: ((InboxMessage) -> Unit)?, loadMore: (() -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { message ->
            val row = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable { onOpen(message) } else Modifier)
            Column(row.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(message.address, style = MaterialTheme.typography.titleSmall)
                Text(message.body, maxLines = if (onOpen != null) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
        if (loadMore != null) item { TextButton(onClick = loadMore) { Text("Load older messages") } }
    }
}
