package com.vibhavtiwari.smsorganizer.otp

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun OtpNotificationScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val notifications = remember(context) { OtpNotifications(context) }
    var visible by remember(notifications) { mutableStateOf(notifications.showCode()) }
    var enabled by remember(notifications) { mutableStateOf(notifications.canNotify()) }
    var requestingOptIn by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = notifications.canNotify()
    }

    DisposableEffect(owner, notifications) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications.createChannel()
                enabled = notifications.canNotify()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show verification codes on the lock screen", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            Switch(checked = visible, onCheckedChange = {
                if (it) requestingOptIn = true else {
                    notifications.setShowCode(false)
                    visible = false
                }
            }, modifier = Modifier.semantics { contentDescription = "Show verification codes on the lock screen" })
        }
        Text("When off, notifications never include the code. When on, anyone who can see your lock screen can read it.")
        Text("Your phone’s notification settings still control what appears. This works only for new SMS when SMS Organizer is your default texting app.")
        if (!enabled) Text("Notifications are currently off or blocked.")
        if (!notifications.permissionGranted()) {
            TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow notifications")
            }
        }
        TextButton(onClick = {
            notifications.createChannel()
            settingsError = runCatching { context.startActivity(notifications.settingsIntent()) }.isFailure
        }) { Text("Open Android notification settings") }
        if (settingsError) Text("Open Settings → Apps → SMS Organizer → Notifications.")
    }

    if (requestingOptIn) AlertDialog(
        onDismissRequest = { requestingOptIn = false },
        title = { Text("Show codes without unlocking?") },
        text = { Text("Anyone who can see your lock screen may be able to read and use these codes.") },
        confirmButton = {
            TextButton(onClick = {
                notifications.setShowCode(true)
                visible = true
                requestingOptIn = false
                if (!notifications.permissionGranted()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Show codes") }
        },
        dismissButton = {
            TextButton(onClick = { requestingOptIn = false }) { Text("Keep hidden") }
        },
    )
}
