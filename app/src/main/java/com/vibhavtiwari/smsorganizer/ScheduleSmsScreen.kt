package com.vibhavtiwari.smsorganizer

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vibhavtiwari.smsorganizer.core.isValidSmsSchedule
import com.vibhavtiwari.smsorganizer.core.smsDestination
import com.vibhavtiwari.smsorganizer.telephony.CarrierSmsSender
import com.vibhavtiwari.smsorganizer.telephony.ScheduledSms
import com.vibhavtiwari.smsorganizer.telephony.ScheduledSmsItem
import com.vibhavtiwari.smsorganizer.telephony.ScheduledSmsService
import com.vibhavtiwari.smsorganizer.telephony.ScheduledState
import com.vibhavtiwari.smsorganizer.telephony.SmsLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ScheduleSmsDialog(
    service: ScheduledSmsService,
    sender: CarrierSmsSender?,
    initialNumber: String,
    initialBody: String,
    initialSim: Int?,
    editing: ScheduledSms? = null,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val zone = remember { ZoneId.systemDefault() }
    var chosen by remember {
        mutableStateOf(editing?.let { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime() }
            ?: LocalDateTime.now(zone).plusMinutes(15).withSecond(0).withNano(0))
    }
    var number by remember { mutableStateOf(initialNumber) }
    var body by remember { mutableStateOf(initialBody) }
    var sim by remember { mutableStateOf(initialSim) }
    var lines by remember { mutableStateOf(emptyList<SmsLine>()) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var alarmAccess by remember { mutableStateOf(service.canSetAlarms()) }
    val settings = remember { service.permissionSettings() }
    val offsets = zone.rules.getValidOffsets(chosen)
    val at = offsets.singleOrNull()?.let { chosen.toInstant(it).toEpochMilli() }
    val active = lines.find { it.subscriptionId == sim }
    var resumeVersion by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeVersion++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sender, resumeVersion) {
        alarmAccess = service.canSetAlarms()
        lines = withContext(Dispatchers.IO) { sender?.activeLines().orEmpty() }
        if (sim == null && editing == null) sim = lines.singleOrNull()?.subscriptionId
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(if (editing == null) "Schedule send" else "Edit scheduled SMS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(number, { number = it }, Modifier.fillMaxWidth(), enabled = !saving,
                    label = { Text("Recipient") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true)
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), enabled = !saving, label = { Text("Message") })
                lines.forEach { line ->
                    FilterChip(selected = sim == line.subscriptionId, onClick = { sim = line.subscriptionId },
                        enabled = !saving, label = { Text(line.label) })
                }
                if (lines.isEmpty()) Text("An active SIM and default texting access are required.")
                TextButton(enabled = !saving, onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        chosen = LocalDateTime.of(java.time.LocalDate.of(year, month + 1, day), chosen.toLocalTime())
                    }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).show()
                }) { Text(chosen.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
                TextButton(enabled = !saving, onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        chosen = chosen.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    }, chosen.hour, chosen.minute, DateFormat.is24HourFormat(context)).show()
                }) {
                    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
                    Text("${chosen.format(DateTimeFormatter.ofPattern(pattern))} (${zone.id})")
                }
                if (at == null) Text("This time doesn’t exist or occurs twice due to a clock change. Choose another.")
                else if (!isValidSmsSchedule(at, System.currentTimeMillis())) Text("Choose a future time within 366 days.")
                if (!alarmAccess) {
                    Text("Allow Alarms & reminders in Android settings.")
                    if (settings != null) TextButton(enabled = !saving, onClick = {
                        if (runCatching { context.startActivity(settings) }.isFailure)
                            feedback = "Open Android settings and find Alarms & reminders for SMS Organizer."
                    }) { Text("Open alarm settings") }
                }
                Text("If your phone can’t send on time, it may send up to 15 minutes late. After that it won’t send.")
                feedback?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && alarmAccess && active != null && smsDestination(number) != null &&
                    body.isNotBlank() && at != null && isValidSmsSchedule(at, System.currentTimeMillis()),
                onClick = {
                    val target = active ?: return@Button
                    val time = at ?: return@Button
                    saving = true
                    val destination = number
                    val text = body
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { service.save(destination, text, target.subscriptionId, time, editing?.id) }
                        saving = false
                        if (error == null) onSaved() else feedback = error
                    }
                },
            ) { Text(if (editing == null) "Schedule SMS" else "Save changes") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text("Back") } },
    )
}

@Composable
internal fun ScheduledSmsScreen(service: ScheduledSmsService, sender: CarrierSmsSender?) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ScheduledSmsItem>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<ScheduledSms?>(null) }
    var confirmation by remember { mutableStateOf<ScheduledSms?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(service, version) {
        while (true) {
            val rows = withContext(Dispatchers.IO) { runCatching { service.items() }.getOrNull() }
            loadError = rows == null
            list = rows
            delay(3_000)
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            if (loadError) Text("Couldn’t read scheduled messages.", Modifier.padding(16.dp))
            else if (list == null) Text("Loading…", Modifier.padding(16.dp))
            else if (list.orEmpty().isEmpty()) Text("No scheduled messages. Start from Compose.", Modifier.padding(16.dp))
            error?.let { Text(it, Modifier.padding(16.dp)) }
        }
        items(list.orEmpty(), key = { it.message.id }) { item ->
            val row = item.message
            val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(row.at), ZoneId.systemDefault())
            val pending = row.state == ScheduledState.PENDING
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.number, style = MaterialTheme.typography.titleSmall)
                if (row.body.isNotEmpty()) Text(row.body, maxLines = 3)
                Text("${date.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))} · ${row.simLabel}")
                Text(item.status)
                if (item.manageable) Row {
                    if (pending && sender != null) TextButton(enabled = !removing, onClick = { editing = row }) { Text("Edit") }
                    TextButton(enabled = !removing, onClick = {
                        if (pending) confirmation = row else {
                            removing = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { service.remove(row) }.getOrDefault(false) }
                                error = if (ok) null else "Couldn’t remove this entry."
                                removing = false
                                version++
                            }
                        }
                    }) { Text(if (pending) "Cancel send" else "Clear") }
                }
            }
            HorizontalDivider()
        }
    }
    editing?.let { row ->
        ScheduleSmsDialog(service, sender, row.number, row.body, row.sim, row, { editing = null }) {
            editing = null
            version++
        }
    }
    confirmation?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!removing) confirmation = null },
            title = { Text("Cancel scheduled SMS?") },
            text = { Text("The scheduled message to ${row.number} will be removed.") },
            confirmButton = {
                TextButton(enabled = !removing, onClick = {
                    removing = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { service.remove(row) }.getOrDefault(false) }
                        error = if (ok) null else "Couldn’t cancel. Check its current send status."
                        removing = false
                        confirmation = null
                        version++
                    }
                }) { Text("Cancel send") }
            },
            dismissButton = { TextButton(enabled = !removing, onClick = { confirmation = null }) { Text("Keep scheduled") } },
        )
    }
}
