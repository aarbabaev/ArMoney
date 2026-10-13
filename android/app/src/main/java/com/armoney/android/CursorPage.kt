package com.armoney.android

import java.net.URLEncoder

internal const val HISTORY_PAGE_SIZE = 50

internal fun pagePath(resource: String, cursor: String?): String {
    require(resource == "payments" || resource == "notifications")
    require(cursor == null || cursor.matches(Regex("[A-Za-z0-9_-]{1,512}")))
    return "/v1/$resource/page?limit=$HISTORY_PAGE_SIZE" +
        (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
}

data class PageState<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val refreshRequired: Boolean = false,
)

/** Main-thread state transitions; tickets invalidate responses after refresh or session reset. */
internal class CursorPage<T>(private val id: (T) -> String, private val changed: (PageState<T>) -> Unit) {
    data class Request(val revision: Long, val cursor: String?, val refresh: Boolean)
    var state = PageState<T>(); private set
    private var revision = 0L
    private var active: Request? = null

    fun reset() {
        revision++; active = null
        publish(PageState())
    }

    fun begin(refresh: Boolean): Request? {
        if (!refresh && (state.loading || state.refreshRequired || (state.loaded && state.nextCursor == null))) return null
        val request = Request(++revision, if (refresh) null else state.nextCursor, refresh || !state.loaded)
        active = request
        publish(state.copy(loading = true, error = null, refreshRequired = false))
        return request
    }

    fun complete(request: Request, items: List<T>, nextCursor: String?) {
        if (request != active) return
        require(items.size <= HISTORY_PAGE_SIZE)
        require(nextCursor == null || nextCursor.matches(Regex("[A-Za-z0-9_-]{1,512}")))
        require(nextCursor == null || (items.isNotEmpty() && nextCursor != request.cursor))
        val merged = linkedMapOf<String, T>()
        if (!request.refresh) state.items.forEach { merged[id(it)] = it }
        items.forEach { merged[id(it)] = it }
        active = null
        publish(PageState(items = merged.values.toList(), nextCursor = nextCursor, loaded = true))
    }

    fun fail(request: Request, message: String, invalidCursor: Boolean = false) {
        if (request != active) return
        active = null
        // A failed refresh must retry the first page, never append using the old cursor.
        publish(state.copy(loading = false, error = message, refreshRequired = invalidCursor || request.refresh))
    }

    fun update(item: T) {
        publish(state.copy(items = state.items.map { if (id(it) == id(item)) item else it }))
    }

    private fun publish(value: PageState<T>) { state = value; changed(value) }
}
