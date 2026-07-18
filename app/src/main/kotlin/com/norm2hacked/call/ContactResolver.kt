package com.norm2hacked.call

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Resolves a phone number to a contact display name via `ContactsContract.PhoneLookup`
 * (mirrors the original app's `ContactUtils`). Falls back to the raw number when there's no
 * match, the number is blank, or READ_CONTACTS isn't granted (the query then throws and is
 * swallowed) — so a missing permission degrades to showing the number, never crashes.
 */
object ContactResolver {

    fun displayName(context: Context, number: String): String {
        if (number.isBlank()) return number
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: number
    }
}
