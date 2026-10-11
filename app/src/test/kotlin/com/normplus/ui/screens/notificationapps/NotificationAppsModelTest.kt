package com.normplus.ui.screens.notificationapps

import com.normplus.data.apps.InstalledApp
import com.normplus.data.db.entities.NotificationRuleEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The list rules Notification apps keeps from the old picker (#104): pure, on the JVM. */
class NotificationAppsModelTest {

    private val apps = listOf(
        InstalledApp("com.whatsapp", "WhatsApp", isSystem = false),
        InstalledApp("com.android.settings", "Settings", isSystem = true),
        InstalledApp("com.android.dialer", "Phone", isSystem = true),
        InstalledApp("org.calendar", "Calendar", isSystem = false),
        InstalledApp("il.messages", "הודעות", isSystem = false),
    )

    private fun rule(pkg: String, enabled: Boolean, suppress: Boolean = true, vibrate: Boolean = true) =
        NotificationRuleEntity(pkg, pkg, enabled, vibrate, suppress)

    private fun rows(rules: List<NotificationRuleEntity> = emptyList(), query: String = "", system: Boolean = false) =
        appRows(apps, emptyMap(), rules, query, system)

    @Test
    fun `system apps are hidden unless shown`() {
        assertEquals(listOf("Calendar", "WhatsApp", "הודעות"), rows().map { it.label })
        assertEquals(5, rows(system = true).size)
    }

    @Test
    fun `an enabled system app is never hidden`() {
        val list = rows(listOf(rule("com.android.dialer", enabled = true)))
        assertTrue(list.any { it.packageName == "com.android.dialer" })
        assertFalse(list.any { it.packageName == "com.android.settings" })
    }

    @Test
    fun `a disabled system app stays hidden`() {
        assertFalse(rows(listOf(rule("com.android.dialer", enabled = false))).any { it.packageName == "com.android.dialer" })
    }

    @Test
    fun `enabled apps come first, then by label ignoring case`() {
        val list = rows(listOf(rule("org.calendar", enabled = false), rule("il.messages", enabled = true)))
        assertEquals(listOf("הודעות", "Calendar", "WhatsApp"), list.map { it.label })
    }

    @Test
    fun `search matches label or package, trimmed, ignoring case`() {
        assertEquals(listOf("WhatsApp"), rows(query = "  whats ").map { it.label })
        assertEquals(listOf("Calendar"), rows(query = "ORG.CAL").map { it.label })
        assertEquals(listOf("הודעות"), rows(query = "הוד").map { it.label })
        assertTrue(rows(query = "nothing").isEmpty())
    }

    @Test
    fun `an app without a rule is off, with the stored defaults`() {
        val whatsapp = rows().first { it.packageName == "com.whatsapp" }
        assertFalse(whatsapp.enabled)
        assertTrue(whatsapp.suppressDuplicates)
        assertTrue(whatsapp.vibrateOnFirst)
    }

    @Test
    fun `a saved row writes suppression to muteGroupChats and keeps vibrateOnFirst`() {
        val row = rows(listOf(rule("com.whatsapp", enabled = true, suppress = false, vibrate = false)))
            .first { it.packageName == "com.whatsapp" }
        assertFalse(row.suppressDuplicates)
        val saved = row.copy(suppressDuplicates = true).toRule()
        assertEquals(NotificationRuleEntity("com.whatsapp", "WhatsApp", enabled = true, vibrateOnFirst = false, muteGroupChats = true), saved)
    }

    @Test
    fun `the initial is one whole code point`() {
        assertEquals("W", initial("whatsApp"))
        assertEquals("ה", initial("הודעות"))
        assertEquals("😀", initial("😀 Smile"))
        assertEquals("?", initial(""))
    }
}
