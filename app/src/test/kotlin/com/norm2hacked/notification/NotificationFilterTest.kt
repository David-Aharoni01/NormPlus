package com.norm2hacked.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure [NotificationFilter.decide] rule chain and the [NotificationFilter.isMediaTemplate]
 * classifier. The thin Android bundle-reading in [toFacts] is exercised on-device; the tricky bits it
 * relies on (media-template matching) are pulled out here and tested directly.
 */
class NotificationFilterTest {

    /** A plain, forwardable messaging notification — every rule overridden off. */
    private fun message() = NotificationFacts(
        pkg = "com.whatsapp",
        category = null,
        isOngoing = false,
        isForegroundService = false,
        isGroupSummary = false,
        isLocalOnly = false,
        isMediaStyle = false,
        hasProgress = false,
        titleBlank = false,
        textBlank = false,
    )

    private fun assertDropped(facts: NotificationFacts, reason: DropReason) {
        assertEquals(FilterDecision.Drop(reason), NotificationFilter.decide(facts))
    }

    private fun assertForwarded(facts: NotificationFacts) {
        assertEquals(FilterDecision.Forward, NotificationFilter.decide(facts))
    }

    @Test
    fun `a plain message is forwarded`() {
        assertForwarded(message())
    }

    @Test
    fun `system source packages are dropped (charging or status)`() {
        assertDropped(message().copy(pkg = "android"), DropReason.SYSTEM_SOURCE)
        assertDropped(message().copy(pkg = "com.android.systemui"), DropReason.SYSTEM_SOURCE)
    }

    @Test
    fun `dialer packages are NOT treated as system source (calls ride the text path for now)`() {
        assertForwarded(message().copy(pkg = "com.google.android.dialer"))
    }

    @Test
    fun `ongoing notifications are dropped`() {
        assertDropped(message().copy(isOngoing = true), DropReason.ONGOING)
    }

    @Test
    fun `foreground-service notifications are dropped (WhatsApp 'Waiting for messages')`() {
        assertDropped(message().copy(isForegroundService = true), DropReason.FOREGROUND_SERVICE)
    }

    @Test
    fun `ongoing user-facing categories are still forwarded (message, alarm)`() {
        // A messenger holding a foreground service must not lose its actual chat message.
        assertForwarded(message().copy(isForegroundService = true, category = NotificationFilter.CATEGORY_MESSAGE))
        assertForwarded(message().copy(isOngoing = true, category = NotificationFilter.CATEGORY_ALARM))
    }

    @Test
    fun `call notifications are dropped (handled first-class by CallManager)`() {
        // Incoming-call notifications are ongoing CATEGORY_CALL; missed are CATEGORY_MISSED_CALL.
        assertDropped(message().copy(isOngoing = true, category = NotificationFilter.CATEGORY_CALL), DropReason.CALL_HANDLED_ELSEWHERE)
        assertDropped(message().copy(category = NotificationFilter.CATEGORY_MISSED_CALL), DropReason.CALL_HANDLED_ELSEWHERE)
    }

    @Test
    fun `media notifications are dropped (Spotify)`() {
        assertDropped(message().copy(isMediaStyle = true), DropReason.MEDIA)
        assertDropped(message().copy(category = NotificationFilter.CATEGORY_TRANSPORT), DropReason.MEDIA)
    }

    @Test
    fun `progress notifications are dropped (downloads, charging bars)`() {
        assertDropped(message().copy(hasProgress = true), DropReason.PROGRESS)
    }

    @Test
    fun `group summaries are NOT dropped here — NotificationMergePolicy decides`() {
        // The original app keeps the summary and evicts the children for our device class
        // (isSupportGroupNotification() is a hardcoded false), the opposite of what this filter
        // used to do. The call needs sibling state, so it lives in the merge policy instead.
        assertForwarded(message().copy(isGroupSummary = true))
    }

    @Test
    fun `local-only is dropped, except whitelisted messaging apps`() {
        assertDropped(message().copy(pkg = "com.some.app", isLocalOnly = true), DropReason.LOCAL_ONLY)
        assertForwarded(message().copy(pkg = "org.telegram.messenger", isLocalOnly = true))
    }

    @Test
    fun `junk categories are dropped`() {
        assertDropped(message().copy(category = NotificationFilter.CATEGORY_SERVICE), DropReason.JUNK_CATEGORY)
        assertDropped(message().copy(category = NotificationFilter.CATEGORY_SYSTEM), DropReason.JUNK_CATEGORY)
    }

    @Test
    fun `empty content is dropped`() {
        assertDropped(message().copy(titleBlank = true, textBlank = true), DropReason.EMPTY_CONTENT)
    }

    @Test
    fun `a title with empty body is still forwarded`() {
        assertForwarded(message().copy(textBlank = true))
    }

    // --- rule precedence (a notification matching several rules reports the first-matched reason) ---

    @Test
    fun `system source beats every other rule`() {
        val f = message().copy(
            pkg = "android", isOngoing = true, isMediaStyle = true, hasProgress = true,
        )
        assertDropped(f, DropReason.SYSTEM_SOURCE)
    }

    @Test
    fun `ongoing is reported before media and progress`() {
        val f = message().copy(isOngoing = true, isMediaStyle = true, hasProgress = true)
        assertDropped(f, DropReason.ONGOING)
    }

    @Test
    fun `media is reported before progress`() {
        assertDropped(message().copy(isMediaStyle = true, hasProgress = true), DropReason.MEDIA)
    }

    // --- isMediaTemplate (the classifier toFacts relies on) ---

    @Test
    fun `isMediaTemplate matches platform and androidx MediaStyle variants`() {
        assertTrue(NotificationFilter.isMediaTemplate("android.app.Notification\$MediaStyle"))
        assertTrue(NotificationFilter.isMediaTemplate("android.app.Notification\$DecoratedMediaCustomViewStyle"))
        assertTrue(NotificationFilter.isMediaTemplate("androidx.media.app.NotificationCompat\$MediaStyle"))
        assertTrue(NotificationFilter.isMediaTemplate("androidx.media.app.NotificationCompat\$DecoratedMediaCustomViewStyle"))
    }

    @Test
    fun `isMediaTemplate rejects non-media templates and null`() {
        assertFalse(NotificationFilter.isMediaTemplate(null))
        assertFalse(NotificationFilter.isMediaTemplate("android.app.Notification\$BigTextStyle"))
        assertFalse(NotificationFilter.isMediaTemplate("android.app.Notification\$MessagingStyle"))
    }
}
