package com.jot.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.jot.app.core.Contact

class Contacts(context: Context) {
    private val app = context.applicationContext

    private class Entry(val name: String, val number: String, val preferred: Boolean, val mobile: Boolean)

    /** One number per person: the one they marked as default, then a mobile. */
    fun all(): List<Contact> {
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val columns = arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE)
        val entries = runCatching {
            app.contentResolver.query(Phone.CONTENT_URI, columns, null, null, null)?.use { row ->
                buildList {
                    while (row.moveToNext()) {
                        val name = row.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                        val number = row.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        add(Entry(name, number, row.getInt(2) != 0, row.getInt(3) == Phone.TYPE_MOBILE))
                    }
                }
            }
        }.getOrNull().orEmpty()
        return entries.sortedWith(compareByDescending<Entry> { it.preferred }.thenByDescending { it.mobile })
            .distinctBy { it.name }.map { Contact(it.name, it.number) }
    }
}
