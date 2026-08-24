package com.duckduckgo.prefetch.api

import java.io.InputStream

/** A bounded set of resources that may be fetched before normal browser navigation requests them. */
data class PrefetchRequest(
    val resources: List<PrefetchResource>,
)

data class PrefetchResource(
    val url: String,
    val priority: Int = 0,
)

data class PrefetchedResponse(
    val mimeType: String?,
    val encoding: String?,
    val statusCode: Int,
    val reasonPhrase: String,
    val responseHeaders: Map<String, String>,
    val body: InputStream,
)

interface BrowserPrefetcher {
    /** Enqueues only the resources supplied by the caller; discovery/crawling policy lives outside this API. */
    fun enqueue(request: PrefetchRequest)

    /** Returns a cached response for the exact URL when available, otherwise null. */
    fun open(url: String): PrefetchedResponse?
}
