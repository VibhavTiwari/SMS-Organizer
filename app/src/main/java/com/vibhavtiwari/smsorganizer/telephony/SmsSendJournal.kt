package com.vibhavtiwari.smsorganizer.telephony

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vibhavtiwari.smsorganizer.core.PartSendResult
import com.vibhavtiwari.smsorganizer.core.SmsSendStatus
import com.vibhavtiwari.smsorganizer.core.summariseSmsSend

internal data class SmsSendRecord(
    val providerRow: Long,
    val partCount: Int,
    val launchedAt: Long,
    val transportUncertain: Boolean,
)

/** Private, durable callback state. No message text or phone numbers are stored here. */
internal class SmsSendJournal(context: Context) : SQLiteOpenHelper(context, "sms-send.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE attempt (token TEXT PRIMARY KEY, provider_id INTEGER NOT NULL, parts INTEGER NOT NULL, started INTEGER NOT NULL, uncertain INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE callback (token TEXT NOT NULL REFERENCES attempt(token) ON DELETE CASCADE, part INTEGER NOT NULL, accepted INTEGER NOT NULL, PRIMARY KEY (token, part, accepted))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("No SMS journal migration from $oldVersion to $newVersion")

    fun start(token: String, row: Long, parts: Int, at: Long) {
        writableDatabase.insertOrThrow("attempt", null, ContentValues().apply {
            put("token", token)
            put("provider_id", row)
            put("parts", parts)
            put("started", at)
        })
    }

    fun uncertain(token: String) {
        writableDatabase.update("attempt", ContentValues().apply { put("uncertain", 1) }, "token = ?", arrayOf(token))
    }

    fun record(token: String): SmsSendRecord? = readableDatabase.query(
        "attempt", arrayOf("provider_id", "parts", "started", "uncertain"), "token = ?", arrayOf(token),
        null, null, null,
    ).use { if (it.moveToFirst()) SmsSendRecord(it.getLong(0), it.getInt(1), it.getLong(2), it.getInt(3) != 0) else null }

    fun addCallback(token: String, part: Int, result: PartSendResult) {
        val entry = record(token) ?: return
        if (part !in 0 until entry.partCount) return
        writableDatabase.insertWithOnConflict("callback", null, ContentValues().apply {
            put("token", token)
            put("part", part)
            put("accepted", if (result == PartSendResult.ACCEPTED) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun status(token: String, now: Long): Pair<SmsSendRecord, SmsSendStatus>? {
        val row = record(token) ?: return null
        val history = readableDatabase.query(
            "callback", arrayOf("part", "accepted"), "token = ?", arrayOf(token), null, null, null,
        ).use {
            buildList {
                while (it.moveToNext()) {
                    add(it.getInt(0) to if (it.getInt(1) == 1) PartSendResult.ACCEPTED else PartSendResult.REJECTED)
                }
            }
        }
        val result = summariseSmsSend(row.partCount, history, row.transportUncertain || now - row.launchedAt >= TIMEOUT)
        return row to result
    }

    private companion object {
        const val TIMEOUT = 5 * 60 * 1000L
    }
}
