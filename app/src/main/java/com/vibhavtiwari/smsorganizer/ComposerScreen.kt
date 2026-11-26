package com.vibhavtiwari.smsorganizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vibhavtiwari.smsorganizer.core.SmsSendStatus
import com.vibhavtiwari.smsorganizer.core.smsDestination
import com.vibhavtiwari.smsorganizer.telephony.CarrierSmsSender
import com.vibhavtiwari.smsorganizer.telephony.ScheduledSmsService
import com.vibhavtiwari.smsorganizer.telephony.SmsLine
import com.vibhavtiwari.smsorganizer.telephony.SmsSubmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class ApprovedDraft(val number: String, val body: String, val line: SmsLine)

@Composable
internal fun ComposerScreen(
    sender: CarrierSmsSender?,
    schedules: ScheduledSmsService,
    onSendStateChanged: () -> Unit,
    onScheduled: () -> Unit,
    onBusyChanged: (Boolean) -> Unit,
) {
    var scheduling by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var lines by remember(sender) { mutableStateOf(emptyList<SmsLine>()) }
    var selectedId by remember(sender) { mutableStateOf<Int?>(null) }
    var confirmation by remember { mutableStateOf<ApprovedDraft?>(null) }
    var submitted by remember { mutableStateOf<ApprovedDraft?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var previousAttempt by remember { mutableStateOf<SmsSendStatus?>(null) }
    val chosen = lines.find { it.subscriptionId == selectedId }
    val busy = submitted != null
    val validNumber = smsDestination(number)

    LaunchedEffect(sender) {
        lines = withContext(Dispatchers.IO) { sender?.activeLines().orEmpty() }
        selectedId = lines.singleOrNull()?.subscriptionId
    }

    LaunchedEffect(submitted, sender) {
        val draft = submitted ?: return@LaunchedEffect
        val transport = sender ?: run {
            submitted = null
            onBusyChanged(false)
            feedback = "Live sending is disabled."
            return@LaunchedEffect
        }
        feedback = "Submitting SMS…"
        when (val result = withContext(Dispatchers.IO) {
            transport.sendConfirmed(draft.number, draft.body, draft.line.subscriptionId)
        }) {
            is SmsSubmission.Queued -> {
                onSendStateChanged()
                feedback = "Waiting for carrier…"
                var status: SmsSendStatus? = null
                while (status == null || status == SmsSendStatus.WAITING) {
                    delay(800)
                    status = withContext(Dispatchers.IO) { transport.readStatus(result.attemptId) }
                    if (status == null) break
                }
                previousAttempt = status ?: SmsSendStatus.UNCERTAIN
                if (status == SmsSendStatus.SENT) {
                    message = ""
                }
                feedback = feedbackFor(status ?: SmsSendStatus.UNCERTAIN)
                onSendStateChanged()
            }
            is SmsSubmission.OutcomeUnknown -> {
                previousAttempt = SmsSendStatus.UNCERTAIN
                feedback = feedbackFor(SmsSendStatus.UNCERTAIN)
                onSendStateChanged()
            }
            SmsSubmission.ChooseActiveSim -> {
                lines = withContext(Dispatchers.IO) { transport.activeLines() }
                selectedId = lines.singleOrNull()?.subscriptionId
                feedback = "That SIM is no longer available. Choose an active SIM."
            }
            SmsSubmission.NotAuthorised -> feedback = "SMS access has changed. No message was sent."
            SmsSubmission.InvalidMessage -> feedback = "Check the number and shorten your message."
            SmsSubmission.CouldNotQueue -> feedback = "Could not queue this SMS. No message was sent."
        }
        submitted = null
        onBusyChanged(false)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(number, { number = it; previousAttempt = null }, Modifier.fillMaxWidth(),
            enabled = !busy, label = { Text("Recipient") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        OutlinedTextField(message, { message = it; previousAttempt = null }, Modifier.fillMaxWidth(),
            enabled = !busy, label = { Text("Message") }, minLines = 3)
        if (lines.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    FilterChip(selected = line.subscriptionId == selectedId,
                        onClick = { selectedId = line.subscriptionId }, enabled = !busy, label = { Text(line.label) })
                }
            }
        }
        val risky = previousAttempt in setOf(SmsSendStatus.UNCERTAIN, SmsSendStatus.PARTIALLY_SENT)
        Button(
            onClick = {
                if (validNumber != null && chosen != null) confirmation = ApprovedDraft(validNumber, message, chosen)
            },
            enabled = sender != null && !busy && validNumber != null && chosen != null && message.isNotBlank(),
        ) { Text(if (risky) "Send another SMS" else "Send SMS") }
        TextButton(
            enabled = sender != null && !busy && validNumber != null && chosen != null && message.isNotBlank(),
            onClick = { scheduling = true },
        ) { Text("Schedule send") }
        val note = feedback ?: if (sender == null) "Live sending is disabled in this build."
            else if (lines.isEmpty()) "No active SIM is available." else null
        note?.let { Text(it) }
    }

    if (scheduling) {
        ScheduleSmsDialog(schedules, sender, number, message, selectedId, onClose = { scheduling = false }) {
            scheduling = false
            onScheduled()
        }
    }
    confirmation?.let { draft ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Send SMS?") },
            text = {
                val warning = if (previousAttempt in setOf(SmsSendStatus.UNCERTAIN, SmsSendStatus.PARTIALLY_SENT))
                    " The last attempt may have sent some or all of the message." else ""
                Text("Send to ${draft.number} using ${draft.line.label}.$warning")
            },
            confirmButton = {
                TextButton(onClick = { onBusyChanged(true); submitted = draft; confirmation = null }) { Text("Send SMS") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

private fun feedbackFor(status: SmsSendStatus): String = when (status) {
    SmsSendStatus.WAITING -> "Waiting for carrier…"
    SmsSendStatus.SENT -> "Sent. Delivery to the recipient isn’t confirmed."
    SmsSendStatus.FAILED -> "The carrier reported a send failure."
    SmsSendStatus.PARTIALLY_SENT -> "Part of the SMS was sent. Check before sending another."
    SmsSendStatus.UNCERTAIN -> "Send status unknown. Check before sending another."
}
