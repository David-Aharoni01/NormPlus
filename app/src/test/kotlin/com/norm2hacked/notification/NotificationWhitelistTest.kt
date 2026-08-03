package com.norm2hacked.notification

import com.norm2hacked.data.db.entities.NotificationRuleEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Unit tests for the pure [NotificationWhitelistPolicy.decide]. Lives in `:app`'s (JVM) test source
 * set rather than `:protocol` because the rule type is an `:app` Room entity, and the Android
 * dependency of the policy itself is zero — only the DB read in [NotificationWhitelist] touches
 * Android, and that is exercised on-device.
 */
class NotificationWhitelistTest {

    private fun rule(
        enabled: Boolean = true,
        vibrateOnFirst: Boolean = true,
        muteGroupChats: Boolean = true,
    ) = WhitelistRule(enabled, vibrateOnFirst, muteGroupChats)

    // ── Whitelist semantics ───────────────────────────────────────────────────

    @Test
    fun `unknown app is not whitelisted`() {
        // The defining property: an app the user has never configured has no row at all, and must
        // NOT forward. (Before the whitelist this defaulted to "forward and auto-add a rule".)
        assertEquals(WhitelistDecision.NotWhitelisted, NotificationWhitelistPolicy.decide(null))
    }

    @Test
    fun `disabled rule is not whitelisted`() {
        assertEquals(
            WhitelistDecision.NotWhitelisted,
            NotificationWhitelistPolicy.decide(rule(enabled = false)),
        )
    }

    @Test
    fun `enabled rule is allowed`() {
        assertIs<WhitelistDecision.Allowed>(NotificationWhitelistPolicy.decide(rule(enabled = true)))
    }

    // ── Per-app options survive the gate ──────────────────────────────────────

    @Test
    fun `allowed decision carries the per-app delivery options`() {
        val decision = NotificationWhitelistPolicy.decide(
            rule(enabled = true, vibrateOnFirst = false, muteGroupChats = false),
        )
        assertEquals(WhitelistDecision.Allowed(vibrateOnFirst = false, muteGroupChats = false), decision)
    }

    @Test
    fun `disabled rule ignores its delivery options`() {
        // A row can keep vibrate/mute preferences while switched off (so re-enabling restores them);
        // they must never leak a notification through.
        assertEquals(
            WhitelistDecision.NotWhitelisted,
            NotificationWhitelistPolicy.decide(rule(enabled = false, vibrateOnFirst = true, muteGroupChats = true)),
        )
    }

    // ── Entity mapping + the default that makes the whitelist safe ────────────

    @Test
    fun `entity default is not forwarding`() {
        // Guards the migration contract: any rule row created without an explicit user opt-in
        // (defaults only) must be inert.
        val fresh = NotificationRuleEntity(packageName = "com.example", appLabel = "Example")
        assertFalse(fresh.enabled)
        assertEquals(
            WhitelistDecision.NotWhitelisted,
            NotificationWhitelistPolicy.decide(fresh.toWhitelistRule()),
        )
    }

    @Test
    fun `entity maps to whitelist rule field for field`() {
        val entity = NotificationRuleEntity(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            enabled = true,
            vibrateOnFirst = false,
            muteGroupChats = true,
        )
        assertEquals(
            WhitelistRule(enabled = true, vibrateOnFirst = false, muteGroupChats = true),
            entity.toWhitelistRule(),
        )
    }
}
