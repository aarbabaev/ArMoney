package com.armoney.android

import org.junit.Assert.*
import org.junit.Test

class PaginationTest {
    private data class Row(val id: String, val value: String = id)
    private fun pages() = CursorPage<Row>({ it.id }) {}

    @Test fun mergeDeduplicatesAndUpdatesWithoutChangingServerOrder() {
        val pages = pages()
        pages.complete(pages.begin(true)!!, listOf(Row("new"), Row("middle")), "next")
        pages.complete(pages.begin(false)!!, listOf(Row("middle", "updated"), Row("old")), null)
        assertEquals(listOf(Row("new"), Row("middle", "updated"), Row("old")), pages.state.items)
        assertNull(pages.begin(false))
        assertTrue(pages.state.loaded)
    }

    @Test fun networkFailureRetriesExactCursorAndPreservesRows() {
        val pages = pages()
        pages.complete(pages.begin(true)!!, listOf(Row("new")), "opaque_next")
        val request = pages.begin(false)!!
        assertNull(pages.begin(false)) // duplicate taps cannot overlap
        pages.fail(request, "unavailable")
        assertEquals(listOf(Row("new")), pages.state.items)
        assertEquals("opaque_next", pages.state.nextCursor)
        assertFalse(pages.state.loading)
        assertEquals(request.cursor, pages.begin(false)!!.cursor)
    }

    @Test fun refreshReplacesRowsAndRejectsOldPageSuccessAndError() {
        val pages = pages()
        pages.complete(pages.begin(true)!!, listOf(Row("previous")), "old_cursor")
        val old = pages.begin(false)!!
        val refresh = pages.begin(true)!!
        assertNull(refresh.cursor)
        pages.complete(old, listOf(Row("stale")), null)
        pages.fail(old, "stale error")
        assertTrue(pages.state.loading)
        pages.complete(refresh, listOf(Row("latest")), "new_cursor")
        assertEquals(listOf(Row("latest")), pages.state.items)
        assertNull(pages.state.error)
        assertEquals("new_cursor", pages.state.nextCursor)
    }

    @Test fun logoutAndNewSessionRejectOldResponses() {
        val pages = pages()
        val old = pages.begin(true)!!
        pages.reset()
        assertTrue(pages.state.items.isEmpty())
        assertFalse(pages.state.loaded)
        val current = pages.begin(true)!!
        pages.complete(old, listOf(Row("other owner")), "old_cursor")
        pages.fail(old, "other owner error")
        pages.complete(current, listOf(Row("current owner")), null)
        assertEquals(listOf(Row("current owner")), pages.state.items)
    }

    @Test fun invalidCursorAndFailedRefreshRequireFirstPage() {
        val pages = pages()
        pages.complete(pages.begin(true)!!, listOf(Row("retained")), "expired")
        pages.fail(pages.begin(false)!!, "invalid cursor", invalidCursor = true)
        assertTrue(pages.state.refreshRequired)
        assertNull(pages.begin(false))
        val refresh = pages.begin(true)!!
        assertNull(refresh.cursor)
        pages.fail(refresh, "offline")
        assertEquals(listOf(Row("retained")), pages.state.items)
        assertTrue(pages.state.refreshRequired)
        val retry = pages.begin(true)!!
        assertNull(retry.cursor)
        pages.complete(retry, emptyList(), null)
        assertTrue(pages.state.loaded)
        assertTrue(pages.state.items.isEmpty())
        assertNull(pages.begin(false))
    }

    @Test fun markReadUpdatesOnlyMatchingRowAndPreservesCursor() {
        val pages = pages()
        pages.complete(pages.begin(true)!!, listOf(Row("new"), Row("old")), "next")
        pages.update(Row("old", "read"))
        assertEquals(listOf(Row("new"), Row("old", "read")), pages.state.items)
        assertEquals("next", pages.state.nextCursor)
    }

    @Test fun pageMetadataIsRequiredAndCursorRemainsOpaque() {
        assertNull(wire.decodeFromString<PaymentPage>("""{"payments":[],"next_cursor":null}""").next_cursor)
        assertEquals("opaque_-123", wire.decodeFromString<NoticePage>("""{"notifications":[],"next_cursor":"opaque_-123"}""").next_cursor)
        assertTrue(runCatching { wire.decodeFromString<PaymentPage>("""{"payments":[]}""") }.isFailure)
        assertTrue(runCatching { wire.decodeFromString<NoticePage>("""{"notifications":[]}""") }.isFailure)
        assertEquals("/v1/payments/page?limit=50", pagePath("payments", null))
        assertEquals("/v1/notifications/page?limit=50&cursor=opaque_-123", pagePath("notifications", "opaque_-123"))
        listOf("", "a".repeat(513), "a&limit=100", "https://other.example").forEach {
            assertTrue(runCatching { pagePath("payments", it) }.isFailure)
        }
    }

    @Test fun malformedOrNonAdvancingPageNeverReplacesLoadedRows() {
        val pages = pages()
        pages.complete(pages.begin(true)!!, listOf(Row("retained")), "next")
        val request = pages.begin(false)!!
        assertTrue(runCatching { pages.complete(request, List(51) { Row("$it") }, null) }.isFailure)
        assertTrue(runCatching { pages.complete(request, listOf(Row("bad")), "next") }.isFailure)
        assertTrue(runCatching { pages.complete(request, emptyList(), "different") }.isFailure)
        pages.fail(request, "bad response")
        assertEquals(listOf(Row("retained")), pages.state.items)
        assertEquals("next", pages.state.nextCursor)
    }
}
