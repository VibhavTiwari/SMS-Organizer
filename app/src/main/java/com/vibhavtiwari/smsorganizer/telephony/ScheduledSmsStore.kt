package com.vibhavtiwari.smsorganizer.telephony

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal enum class ScheduledState {
    PENDING, CLAIMED, SUBMITTED, UNKNOWN, FAILED, BLOCKED, MISSED,
}

internal data class ScheduledSms(
    val id: String,
    val number: String,
    val body: String,
    val sim: Int,
    val simLabel: String,
    val at: Long,
    val state: ScheduledState = ScheduledState.PENDING,
    val claimedAt: Long = 0,
    val attempt: String? = null,
)

internal class ScheduledSmsStore(context: Context) : SQLiteOpenHelper(context, "scheduled-sms.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA secure_delete = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE scheduled_sms (
                id TEXT PRIMARY KEY NOT NULL, number TEXT NOT NULL, body TEXT NOT NULL,
                sim INTEGER NOT NULL, sim_label TEXT NOT NULL, send_at INTEGER NOT NULL,
                state TEXT NOT NULL, claimed_at INTEGER NOT NULL DEFAULT 0, attempt TEXT
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("No scheduled SMS migration from $oldVersion to $newVersion")

    fun list(id: String? = null): List<ScheduledSms> = readableDatabase.query(
        TABLE, arrayOf("id", "number", "body", "sim", "sim_label", "send_at", "state", "claimed_at", "attempt"),
        id?.let { "id = ?" }, id?.let { arrayOf(it) }, null, null, "send_at ASC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ScheduledSms(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3),
                cursor.getString(4), cursor.getLong(5), ScheduledState.valueOf(cursor.getString(6)),
                cursor.getLong(7), cursor.getString(8),
            ))
        }
    }

    fun put(row: ScheduledSms, editingId: String?, arm: () -> Unit): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", row.id)
                put("number", row.number)
                put("body", row.body)
                put("sim", row.sim)
                put("sim_label", row.simLabel)
                put("send_at", row.at)
                put("state", ScheduledState.PENDING.name)
            }
            val saved = if (editingId != null) db.update(TABLE, values, BY_STATE, args(editingId, ScheduledState.PENDING)) == 1
            else {
                if (list().size >= MAX_RECORDS) return false
                db.insertOrThrow(TABLE, null, values) != -1L
            }
            if (saved) {
                arm() // Do not commit if Android refuses to set the alarm.
                db.setTransactionSuccessful()
            }
            return saved
        } finally {
            db.endTransaction()
        }
    }

    fun transition(id: String, from: ScheduledState, to: ScheduledState, now: Long = 0, attempt: String? = null): Boolean =
        writableDatabase.update(TABLE, ContentValues().apply {
            put("state", to.name)
            put("body", "") // Text is needed only while the message is pending.
            if (now != 0L) put("claimed_at", now)
            if (attempt != null) put("attempt", attempt)
        }, BY_STATE, args(id, from)) == 1

    fun delete(id: String, state: ScheduledState): Boolean =
        writableDatabase.delete(TABLE, BY_STATE, args(id, state)) == 1

    private companion object {
        const val TABLE = "scheduled_sms"
        const val BY_STATE = "id = ? AND state = ?"
        const val MAX_RECORDS = 100
        fun args(id: String, state: ScheduledState) = arrayOf(id, state.name)
    }
}
