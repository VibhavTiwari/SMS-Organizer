package com.vibhavtiwari.smsorganizer

import android.content.Context
import com.vibhavtiwari.smsorganizer.core.Category
import java.security.MessageDigest
import java.util.UUID

/**
 * Per-message corrections in app-private preferences. No number, text, or OTP is stored.
 * Imported messages and provider messages are separate even if their row IDs coincide.
 */
internal class CategoryOverrides(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("category-overrides-v1", Context.MODE_PRIVATE)

    /** Read in the background; caller supplies the classifier's unmodified messages. */
    fun applyTo(messages: List<InboxMessage>, live: Boolean): List<InboxMessage> {
        val overrides = preferences.all
        val salt = overrides[SALT] as? String ?: return messages
        if (overrides.size <= 1) return messages
        return messages.map { message ->
            val value = overrides[messageKey(message, live, salt)] as? String
            val category = Category.entries.firstOrNull { it.name == value }
            if (category == null || category == message.category) message else message.copy(category = category)
        }
    }

    /** Run on an IO thread. Null undoes the correction without touching the underlying SMS. */
    @Synchronized
    fun set(message: InboxMessage, live: Boolean, category: Category?): Boolean {
        val salt = preferences.getString(SALT, null) ?: UUID.randomUUID().toString()
        val key = messageKey(message, live, salt)
        return preferences.edit().apply {
            if (category == null) remove(key)
            else {
                putString(SALT, salt)
                putString(key, category.name)
            }
        }.commit()
    }

    private fun messageKey(message: InboxMessage, live: Boolean, salt: String): String {
        val address = message.address
        val identity = buildString {
            append(salt)
            append(if (live) "|live|" else "|archive|")
            append(message.id).append('|').append(message.sentAtMillis).append('|')
            append(message.incoming).append('|').append(address.length).append(':').append(address)
            // XML row numbers can be reused by unrelated exports. Include body only in their
            // salted fingerprint, never as preference data.
            if (!live) append('|').append(message.body)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { HEX[(it.toInt() ushr 4) and 15].toString() + HEX[it.toInt() and 15] }
    }

    private companion object {
        const val SALT = "fingerprint-salt"
        const val HEX = "0123456789abcdef"
    }
}
