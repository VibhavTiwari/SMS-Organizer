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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
            val scope = rememberCoroutineScope()
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
                InboxScreen(messages, status.ifBlank { null }, onOpenArchive = { open.launch("*/*") }, importing = loading)
            }
        }
    }
}
