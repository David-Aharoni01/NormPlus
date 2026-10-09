package com.normplus.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure [NotificationMergePolicy.merge] — the phone-side replacement for the
 * watch-side update/delete that the `New` push generation does not have.
 */
class NotificationMergePolicyTest {

    private fun post(
        key: String,
        group: String? = null,
        summary: Boolean = false,
        title: String = "t",
        content: String = "c",
    ) = PendingNotification(
        mergeKey = key,
        groupKey = group,
        isGroupSummary = summary,
        pkg = "com.whatsapp",
        messageType = 0x0A,
        title = title,
        content = content,
    )

    private fun keys(merged: List<PendingNotification>) = merged.map { it.mergeKey }

    // --- last-write-wins per merge key (B3) ---

    @Test
    fun `an empty window merges to nothing`() {
        assertTrue(NotificationMergePolicy.merge(emptyList()).isEmpty())
    }

    @Test
    fun `repeated posts of the same chat collapse to the newest`() {
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("wa1", content = "first"),
                post("wa1", content = "second"),
                post("wa1", content = "third"),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("third", merged.single().content)
    }

    @Test
    fun `different chats are all kept`() {
        val merged = NotificationMergePolicy.merge(listOf(post("wa1"), post("wa2"), post("wa3")))
        assertEquals(listOf("wa1", "wa2", "wa3"), keys(merged))
    }

    @Test
    fun `a re-post moves the entry to the back of the window`() {
        val merged = NotificationMergePolicy.merge(listOf(post("a"), post("b"), post("a")))
        assertEquals(listOf("b", "a"), keys(merged))
    }

    // --- group summary vs children (B4) ---

    @Test
    fun `two or more children plus a summary collapse to the summary alone`() {
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("wa1", group = "g"),
                post("wa2", group = "g"),
                post("wa3", group = "g"),
                post("sum", group = "g", summary = true),
            )
        )
        assertEquals(listOf("sum"), keys(merged))
    }

    @Test
    fun `summary order in the window does not matter`() {
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("sum", group = "g", summary = true),
                post("wa1", group = "g"),
                post("wa2", group = "g"),
            )
        )
        assertEquals(listOf("sum"), keys(merged))
    }

    @Test
    fun `a lone child beats the summary (UX policy deviation)`() {
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("wa1", group = "g", content = "Alice: on my way"),
                post("sum", group = "g", summary = true, content = "1 new message"),
            )
        )
        assertEquals(listOf("wa1"), keys(merged))
        assertEquals("Alice: on my way", merged.single().content)
    }

    @Test
    fun `preferLoneChild=false restores the strict smali behaviour`() {
        val merged = NotificationMergePolicy.merge(
            listOf(post("wa1", group = "g"), post("sum", group = "g", summary = true)),
            preferLoneChild = false,
        )
        assertEquals(listOf("sum"), keys(merged))
    }

    @Test
    fun `repeats of one child still count as a single child`() {
        // Three in-place updates of the same chat are ONE child after phase 1, so the lone-child
        // rule applies and the user sees the actual message, not "3 new messages".
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("wa1", group = "g", content = "one"),
                post("wa1", group = "g", content = "two"),
                post("sum", group = "g", summary = true),
                post("wa1", group = "g", content = "three"),
            )
        )
        assertEquals(listOf("wa1"), keys(merged))
        assertEquals("three", merged.single().content)
    }

    @Test
    fun `a summary with no children in the window is forwarded`() {
        val merged = NotificationMergePolicy.merge(listOf(post("sum", group = "g", summary = true)))
        assertEquals(listOf("sum"), keys(merged))
    }

    @Test
    fun `children with no summary in the window are all forwarded`() {
        val merged = NotificationMergePolicy.merge(
            listOf(post("wa1", group = "g"), post("wa2", group = "g"))
        )
        assertEquals(listOf("wa1", "wa2"), keys(merged))
    }

    @Test
    fun `only the newest summary of a group survives`() {
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("sumA", group = "g", summary = true, content = "2 new"),
                post("sumB", group = "g", summary = true, content = "3 new"),
                post("wa1", group = "g"),
                post("wa2", group = "g"),
            )
        )
        assertEquals(listOf("sumB"), keys(merged))
    }

    @Test
    fun `groups do not interfere with each other`() {
        val merged = NotificationMergePolicy.merge(
            listOf(
                post("wa1", group = "wa"),
                post("wa2", group = "wa"),
                post("waSum", group = "wa", summary = true),
                post("tg1", group = "tg"),
                post("mail", group = null),
            )
        )
        assertEquals(listOf("waSum", "tg1", "mail"), keys(merged))
    }

    @Test
    fun `ungrouped posts are never collapsed`() {
        val merged = NotificationMergePolicy.merge(listOf(post("a"), post("b"), post("c")))
        assertEquals(3, merged.size)
    }

    // --- buffer plumbing ---

    @Test
    fun `buffer drains once and empties`() {
        val buffer = NotificationMergeBuffer()
        buffer.offer(post("a"))
        buffer.offer(post("a", content = "newer"))
        buffer.offer(post("b"))
        assertEquals(3, buffer.size)

        val first = buffer.drainMerged()
        assertEquals(listOf("a", "b"), keys(first))
        assertEquals("newer", first.first().content)

        assertEquals(0, buffer.size)
        assertTrue(buffer.drainMerged().isEmpty())
    }
}
