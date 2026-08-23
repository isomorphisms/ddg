/*
 * Copyright (c) 2026 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser.pageload

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.BrowserWebViewClient
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.WebViewClientListener
import com.duckduckgo.app.browser.mode.InAppNavigation
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * On-device page-load benchmark measuring the browser path from the actual [WebView.loadUrl]
 * call to the production [android.webkit.WebViewClient.onPageCommitVisible] callback.
 *
 * The fixture is served from an in-process [MockWebServer] so DNS, Internet latency, and remote
 * server variance do not dominate the measurement. The existing DuckDuckGo [BrowserWebViewClient]
 * remains installed: this test only wraps its [WebViewClientListener] to timestamp the callback,
 * then delegates every callback to the original listener.
 *
 * Like the other on-device perf tests in this module, this is observation-only: there is no
 * absolute performance assertion and CI should not treat device timing variance as a failure.
 *
 * To execute manually, temporarily remove [Ignore] and run:
 *
 * ./gradlew :app:connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.duckduckgo.app.browser.pageload.PageLoadCommitVisiblePerfAndroidTest
 *
 * Output is emitted to logcat under tag "PageLoadCommitPerf":
 *
 * adb logcat -s PageLoadCommitPerf:I '*:S'
 */
@Ignore("Performance benchmark — run manually, not in CI. See class kdoc for instructions.")
@RunWith(AndroidJUnit4::class)
class PageLoadCommitVisiblePerfAndroidTest {

    @get:Rule
    val activityScenarioRule = activityScenarioRule<BrowserActivity>(
        BrowserActivity.intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            launchSource = InAppNavigation,
            queryExtra = "about:blank",
        ),
    )

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                        .setBody(FIXTURE_HTML)
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun benchmarkLoadUrlToPageCommitVisible() {
        assumeTrue(
            "Current WebView must support retrieving its installed WebViewClient",
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_CLIENT),
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val commits = LinkedBlockingQueue<Commit>()
        lateinit var webView: WebView
        lateinit var browserWebViewClient: BrowserWebViewClient
        lateinit var originalListener: WebViewClientListener

        activityScenarioRule.scenario.onActivity { activity ->
            webView = activity.findViewById(R.id.browserWebView)
            val installedClient = WebViewCompat.getWebViewClient(webView)
            check(installedClient is BrowserWebViewClient) {
                "Expected BrowserWebViewClient, found ${installedClient.javaClass.name}"
            }
            browserWebViewClient = installedClient
            originalListener = installedClient.webViewClientListener
                ?: error("BrowserWebViewClient has no listener")
            installedClient.webViewClientListener = observingListener(originalListener, commits)
        }

        try {
            logRuntimeContext()

            repeat(WARMUP_ROUNDS) { iteration ->
                measureOnce(
                    webView = webView,
                    path = "/fixture?warmup=$iteration",
                    commits = commits,
                    startNs = AtomicLong(),
                )
            }

            val samplesNs = List(MEASURED_ITERATIONS) { iteration ->
                measureOnce(
                    webView = webView,
                    path = "/fixture?sample=$iteration",
                    commits = commits,
                    startNs = AtomicLong(),
                )
            }

            logResults(samplesNs)
        } finally {
            instrumentation.runOnMainSync {
                browserWebViewClient.webViewClientListener = originalListener
            }
        }
    }

    private fun measureOnce(
        webView: WebView,
        path: String,
        commits: LinkedBlockingQueue<Commit>,
        startNs: AtomicLong,
    ): Long {
        val targetUrl = server.url(path).toString()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            startNs.set(SystemClock.elapsedRealtimeNanos())
            webView.loadUrl(targetUrl)
        }

        val deadlineMs = SystemClock.elapsedRealtime() + LOAD_TIMEOUT_MS
        while (true) {
            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            check(remainingMs > 0) { "Timed out waiting for onPageCommitVisible for $targetUrl" }

            val commit = commits.poll(remainingMs, TimeUnit.MILLISECONDS)
                ?: error("Timed out waiting for onPageCommitVisible for $targetUrl")
            if (commit.url == targetUrl) {
                return commit.timestampNs - startNs.get()
            }
        }
    }

    private fun observingListener(
        original: WebViewClientListener,
        commits: LinkedBlockingQueue<Commit>,
    ): WebViewClientListener {
        val proxy = Proxy.newProxyInstance(
            original.javaClass.classLoader,
            arrayOf(WebViewClientListener::class.java),
        ) { _, method, args ->
            if (method.name == "onPageCommitVisible") {
                val url = args?.getOrNull(1) as? String
                if (url != null) {
                    commits.offer(Commit(url, SystemClock.elapsedRealtimeNanos()))
                }
            }

            try {
                method.invoke(original, *(args ?: emptyArray()))
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }
        }
        return proxy as WebViewClientListener
    }

    private fun logResults(samplesNs: List<Long>) {
        val sorted = samplesNs.sorted()
        val medianNs = percentile(sorted, 0.50)
        val p90Ns = percentile(sorted, 0.90)

        log("=== loadUrl -> onPageCommitVisible (local deterministic fixture) ===")
        log("Warmup rounds: $WARMUP_ROUNDS")
        log("Measured iterations: $MEASURED_ITERATIONS")
        log("min=${formatMs(sorted.first())} ms | median=${formatMs(medianNs)} ms | p90=${formatMs(p90Ns)} ms | max=${formatMs(sorted.last())} ms")
        log("samples_ms=[${samplesNs.joinToString(", ") { formatMs(it) }}]")
        log("No pass/fail threshold: compare distributions on the same device/WebView build.")
    }

    private fun percentile(sortedNs: List<Long>, percentile: Double): Long {
        val index = ((sortedNs.lastIndex) * percentile).roundToInt()
        return sortedNs[index]
    }

    private fun formatMs(nanoseconds: Long): String =
        String.format(Locale.US, "%.2f", nanoseconds / 1_000_000.0)

    private fun logRuntimeContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
        log("--- Runtime context ---")
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}")
        log("WebView: ${webViewPackage?.packageName ?: "unknown"} ${webViewPackage?.versionName ?: "unknown"}")
        log("Fixture server: loopback MockWebServer; response is static HTML with Cache-Control: no-store")
        log("-----------------------")
    }

    private fun log(message: String) {
        Log.i(LOG_TAG, message)
    }

    private data class Commit(
        val url: String,
        val timestampNs: Long,
    )

    companion object {
        private const val LOG_TAG = "PageLoadCommitPerf"
        private const val WARMUP_ROUNDS = 5
        private const val MEASURED_ITERATIONS = 30
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val FIXTURE_HTML = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Page load benchmark fixture</title>
                <style>
                  body { font-family: sans-serif; margin: 24px; }
                  main { max-width: 42rem; }
                </style>
              </head>
              <body>
                <main>
                  <h1>Page load benchmark fixture</h1>
                  <p>This page intentionally has no remote subresources.</p>
                </main>
              </body>
            </html>
        """
    }
}
