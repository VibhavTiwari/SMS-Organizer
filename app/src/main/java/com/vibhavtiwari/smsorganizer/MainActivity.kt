package com.vibhavtiwari.smsorganizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vibhavtiwari.smsorganizer.telephony.ScheduledSmsService
import com.vibhavtiwari.smsorganizer.telephony.SmsPageKey
import com.vibhavtiwari.smsorganizer.telephony.SmsStoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var messages by remember { mutableStateOf(emptyList<InboxMessage>()) }
            var status by remember { mutableStateOf("Open an SMS XML export to view it on this device.") }
            var loading by remember { mutableStateOf(false) }
            var live by remember { mutableStateOf(false) }
            var next by remember { mutableStateOf<SmsPageKey?>(null) }
            var liveLoading by remember { mutableStateOf(false) }
            val deviceSms = remember { LiveSmsSession(this) }
            val schedules = remember { ScheduledSmsService(this) }
            val scope = rememberCoroutineScope()
            val refresh: (Boolean) -> Unit = refresh@{ older ->
                if (liveLoading || !deviceSms.canRead()) return@refresh
                val oldPage = if (older) next ?: return@refresh else null
                liveLoading = true
                scope.launch {
                    try {
                        when (val result = withContext(Dispatchers.IO) { deviceSms.page(oldPage) }) {
                            is SmsStoreResult.Ok -> {
                                val page = result.value.toInboxMessages()
                                if (!deviceSms.canRead()) {
                                    messages = emptyList()
                                    next = null
                                    live = false
                                } else {
                                    messages = if (older) (messages + page).distinctBy { it.id } else page
                                    next = result.value.next
                                    status = ""
                                }
                            }
                            else -> {
                                messages = emptyList()
                                next = null
                                live = false
                                status = "Device inbox access is unavailable. You can still open an export."
                            }
                        }
                    } finally {
                        liveLoading = false
                    }
                }
            }
            DisposableEffect(deviceSms) {
                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        scope.launch(Dispatchers.IO) { runCatching { schedules.restore() } }
                        val allowed = deviceSms.canRead()
                        if (live != allowed) {
                            messages = emptyList()
                            next = null
                        }
                        live = allowed
                        if (live) refresh(false)
                        else status = "Open an SMS XML export to view it on this device."
                    }
                }
                lifecycle.addObserver(lifecycleObserver)
                onDispose { lifecycle.removeObserver(lifecycleObserver) }
            }
            val open = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null && !loading) {
                    loading = true
                    status = "Opening export…"
                    scope.launch {
                        try {
                            messages = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use(::readSmsArchive)
                                    ?: error("Unable to open export")
                            }
                            status = if (messages.isEmpty()) "No supported SMS messages in this file." else ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = "Couldn’t read this file. Choose a supported SMS XML export under 32 MB."
                        } finally {
                            loading = false
                        }
                    }
                }
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                InboxScreen(
                    messages, status.ifBlank { null },
                    onOpenArchive = { open.launch("*/*") }, importing = loading, schedules = schedules,
                    sender = deviceSms.sender.takeIf { live },
                    onSendStateChanged = { refresh(false) },
                    loadMore = if (live && next != null && !liveLoading) { { refresh(true) } } else null,
                )
            }
        }
    }
}
