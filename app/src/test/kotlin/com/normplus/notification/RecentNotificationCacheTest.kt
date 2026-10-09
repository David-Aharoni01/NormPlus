package com.normplus.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the `OldProtocolFilter`-modelled suppression cache: exact repeats are suppressed
 * indefinitely (not for a fixed window), a removal un-suppresses, and the cache is FIFO-bounded.
 */
class RecentNotificationCacheTest {

    private fun key(id: String, title: String = "Alice", content: String = "hi") =
        SuppressionKey(id, title, content)

    @Test
    fun `a first push is allowed`() {
        assertTrue(RecentNotificationCache().shouldSend(key("wa1")))
    }

    @Test
    fun `an exact repeat is suppressed`() {
        val cache = RecentNotificationCache()
        assertTrue(cache.shouldSend(key("wa1")))
        assertFalse(cache.shouldSend(key("wa1")))
        assertFalse(cache.shouldSend(key("wa1")))
    }

    @Test
    fun `a changed title or body is a new notification`() {
        val cache = RecentNotificationCache()
        assertTrue(cache.shouldSend(key("wa1", content = "hi")))
        assertTrue(cache.shouldSend(key("wa1", content = "you there?")))
        assertTrue(cache.shouldSend(key("wa1", title = "Bob", content = "hi")))
    }

    @Test
    fun `the same text under a different id is a new notification`() {
        val cache = RecentNotificationCache()
        assertTrue(cache.shouldSend(key("wa1")))
        assertTrue(cache.shouldSend(key("wa2")))
    }

    @Test
    fun `eviction on removal lets identical text through again`() {
        val cache = RecentNotificationCache()
        assertTrue(cache.shouldSend(key("wa1")))
        assertFalse(cache.shouldSend(key("wa1")))

        cache.evict("wa1") // the phone-side dismissal — the only thing onNotificationRemoved can do
        assertTrue(cache.shouldSend(key("wa1")))
    }

    @Test
    fun `eviction only touches the given id`() {
        val cache = RecentNotificationCache()
        cache.shouldSend(key("wa1"))
        cache.shouldSend(key("wa2"))
        cache.evict("wa1")
        assertTrue(cache.shouldSend(key("wa1")))
        assertFalse(cache.shouldSend(key("wa2")))
    }

    @Test
    fun `eviction removes every cached text for that id`() {
        val cache = RecentNotificationCache()
        cache.shouldSend(key("wa1", content = "one"))
        cache.shouldSend(key("wa1", content = "two"))
        cache.evict("wa1")
        assertEquals(0, cache.size)
    }

    @Test
    fun `evicting an unknown id is a no-op`() {
        val cache = RecentNotificationCache()
        cache.shouldSend(key("wa1"))
        cache.evict("nope")
        assertEquals(1, cache.size)
    }

    @Test
    fun `the cache is FIFO-bounded and the oldest entry ages out`() {
        val cache = RecentNotificationCache()
        repeat(RecentNotificationCache.MAX_ENTRIES) { i -> assertTrue(cache.shouldSend(key("n$i"))) }
        assertEquals(RecentNotificationCache.MAX_ENTRIES, cache.size)

        // The newest entries are still suppressed...
        assertFalse(cache.shouldSend(key("n${RecentNotificationCache.MAX_ENTRIES - 1}")))

        // ...one more push evicts the head (n0), which is then forwardable again.
        assertTrue(cache.shouldSend(key("overflow")))
        assertEquals(RecentNotificationCache.MAX_ENTRIES, cache.size)
        assertTrue(cache.shouldSend(key("n0")))
    }

    @Test
    fun `a suppressed repeat does not refresh the entry's FIFO position`() {
        val cache = RecentNotificationCache()
        cache.shouldSend(key("first"))
        repeat(RecentNotificationCache.MAX_ENTRIES - 1) { i -> cache.shouldSend(key("n$i")) }
        assertFalse(cache.shouldSend(key("first"))) // suppressed, position unchanged
        cache.shouldSend(key("overflow"))           // evicts the head, which is still "first"
        assertTrue(cache.shouldSend(key("first")))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = RecentNotificationCache()
        cache.shouldSend(key("wa1"))
        cache.clear()
        assertEquals(0, cache.size)
        assertTrue(cache.shouldSend(key("wa1")))
    }
}
