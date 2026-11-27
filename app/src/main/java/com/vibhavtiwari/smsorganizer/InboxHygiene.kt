package com.vibhavtiwari.smsorganizer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vibhavtiwari.smsorganizer.core.CleanupReason
import com.vibhavtiwari.smsorganizer.core.cleanupReason
import com.vibhavtiwari.smsorganizer.core.isLikelySpam
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class InboxHygieneTab { SPAM, CLEANUP, HIDDEN }
internal enum class HygieneDecision { KEEP, SPAM, HIDE }

internal data class HygieneMessage(
    val message: InboxMessage,
    val key: String,
    val spam: Boolean,
    val cleanup: CleanupReason?,
)

internal class InboxHygiene internal constructor(
    private val store: HygieneStore,
    private val scope: CoroutineScope,
) {
    private var rows by mutableStateOf<List<HygieneMessage>?>(null)
    private var decisions by mutableStateOf<Map<String, HygieneDecision>>(emptyMap())
    private var loadedFor: Long by mutableStateOf(0L)
    var requestedFor: Long = 0L
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private fun isSpam(row: HygieneMessage, current: Map<String, HygieneDecision>): Boolean =
        when (current[row.key]) {
            HygieneDecision.SPAM -> true
            HygieneDecision.HIDE, HygieneDecision.KEEP -> false
            null -> row.spam
        }

    private fun isVisible(row: HygieneMessage, current: Map<String, HygieneDecision>): Boolean =
        current[row.key] != HygieneDecision.HIDE && !isSpam(row, current)

    private val activeRows get() = rows.takeIf { loadedFor == requestedFor }

    val ready: Boolean get() = activeRows != null

    val inboxMessages: List<InboxMessage>?
        get() {
            val current = decisions
            return activeRows?.filter { isVisible(it, current) }?.map { it.message }
        }

    fun entries(tab: InboxHygieneTab): List<HygieneMessage>? {
        val current = decisions
        return activeRows?.filter {
            when (tab) {
                InboxHygieneTab.SPAM -> isSpam(it, current)
                InboxHygieneTab.HIDDEN -> current[it.key] == HygieneDecision.HIDE
                InboxHygieneTab.CLEANUP -> it.cleanup != null && current[it.key] == null && !it.spam
            }
        }?.sortedByDescending { it.message.sentAtMillis }
    }

    fun isManualSpam(row: HygieneMessage): Boolean = decisions[row.key] == HygieneDecision.SPAM

    fun report(message: InboxMessage, onReported: () -> Unit) {
        if (!message.incoming) return
        val row = activeRows?.firstOrNull { it.message.id == message.id } ?: return
        if (isVisible(row, decisions)) change(listOf(row.key), HygieneDecision.SPAM, onReported)
    }

    fun restore(row: HygieneMessage) {
        if (activeRows?.any { it.key == row.key } == true) change(listOf(row.key), HygieneDecision.KEEP)
    }

    fun hide(selected: Set<String>) {
        val allowed = entries(InboxHygieneTab.CLEANUP)?.filter { it.key in selected }?.map { it.key }.orEmpty()
        change(allowed, HygieneDecision.HIDE)
    }

    private fun change(keys: List<String>, decision: HygieneDecision, onSuccess: () -> Unit = {}) {
        if (keys.isEmpty() || busy) return
        val version = requestedFor
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(keys, decision) }
                decisions = decisions + keys.associateWith { decision }
                error = null
                if (requestedFor == version) onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Couldn’t save the change. No messages were deleted."
            } finally {
                busy = false
            }
        }
    }

    internal suspend fun load(token: Long, input: List<InboxMessage>, live: Boolean) {
        val previous = decisions
        val decisionSnapshot: Map<String, HygieneDecision>
        val scanned: List<HygieneMessage>
        try {
            val data = withContext(Dispatchers.IO) {
                val fingerprint = Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(store.keySalt(), "HmacSHA256"))
                }
                val duplicates = hashMapOf<String, Int>()
                val now = System.currentTimeMillis()
                val entries = input.map { message ->
                    val digest = fingerprint.messageKey(message, live)
                    val ordinal = if (live) 0 else duplicates.getOrPut(digest) { 0 }.also {
                        duplicates[digest] = it + 1
                    }
                    HygieneMessage(
                        message,
                        "$digest:$ordinal",
                        isLikelySpam(message.body, message.category, message.incoming),
                        cleanupReason(message.category, message.incoming, message.sentAtMillis, now, message.address, message.body),
                    )
                }
                entries to store.all()
            }
            scanned = data.first
            decisionSnapshot = data.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (requestedFor == token) {
                rows = null
                loadedFor = token
                error = "Couldn’t load spam and cleanup settings. Reopen the app to retry."
            }
            return
        }
        if (requestedFor != token) return
        // Preserve local writes completed while this scan was running.
        decisions = decisionSnapshot + decisions.filter { (key, value) -> previous[key] != value }
        rows = scanned
        loadedFor = token
        error = null
    }
}

private val hygieneToken = AtomicLong()

@Composable
internal fun rememberInboxHygiene(messages: List<InboxMessage>, live: Boolean): InboxHygiene {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(app) { HygieneStore.get(app) }
    val hygiene = remember(store, scope) { InboxHygiene(store, scope) }
    val token = remember(messages, live) { hygieneToken.incrementAndGet() }
    hygiene.requestedFor = token
    LaunchedEffect(token) { hygiene.load(token, messages, live) }
    return hygiene
}

private fun Mac.messageKey(message: InboxMessage, live: Boolean): String {
    fun updateString(value: String) {
        val bytes = value.encodeToByteArray()
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }
    update(if (live) 1.toByte() else 0.toByte())
    update(if (message.incoming) 1.toByte() else 0.toByte())
    update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(message.sentAtMillis).array())
    if (live) update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(message.id).array())
    updateString(message.address)
    updateString(message.body)
    return doFinal().joinToString("") {
        val n = it.toInt() and 0xff
        "${"0123456789abcdef"[n ushr 4]}${"0123456789abcdef"[n and 0xf]}"
    }
}

internal class HygieneStore private constructor(context: Context) : SQLiteOpenHelper(context, "inbox-hygiene.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE installation (key_salt BLOB NOT NULL)")
        val salt = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        db.insertOrThrow("installation", null, ContentValues().apply { put("key_salt", salt) })
        db.execSQL("CREATE TABLE decisions (message_key TEXT NOT NULL PRIMARY KEY, decision TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported hygiene database upgrade from $oldVersion to $newVersion")

    fun keySalt(): ByteArray = readableDatabase
        .rawQuery("SELECT key_salt FROM installation LIMIT 1", null)
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getBlob(0)
        }

    fun all(): Map<String, HygieneDecision> = readableDatabase
        .query("decisions", arrayOf("message_key", "decision"), null, null, null, null, null)
        .use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val decision = HygieneDecision.entries.firstOrNull { it.name == cursor.getString(1) } ?: continue
                    put(cursor.getString(0), decision)
                }
            }
        }

    fun save(keys: List<String>, decision: HygieneDecision) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (key in keys) {
                val values = ContentValues().apply {
                    put("message_key", key)
                    put("decision", decision.name)
                }
                check(db.insertWithOnConflict("decisions", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private var instance: HygieneStore? = null

        @Synchronized fun get(context: Context): HygieneStore = instance
            ?: HygieneStore(context.applicationContext).also { instance = it }
    }
}
