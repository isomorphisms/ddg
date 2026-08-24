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
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Manual, on-device page-load benchmark over the sites in the owner's real browsing corpus.
 *
 * Only scheme + hostname origins are checked in. The original browsing list includes account,
 * invite, checkout, and session identifiers that should not be committed to a public repository.
 * This benchmark therefore measures each site's root origin while keeping the private URL corpus
 * outside Git.
 *
 * This deliberately complements [PageLoadCommitVisiblePerfAndroidTest]: that benchmark uses a
 * deterministic loopback fixture to isolate browser overhead, while this one keeps real network,
 * redirect, server, and page complexity in the measurement.
 *
 * Run manually after temporarily removing [Ignore]:
 *
 * ./gradlew :app:connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.duckduckgo.app.browser.pageload.PersonalSitePageLoadPerfAndroidTest
 *
 * The corpus can be sharded without editing it:
 *
 * -Pandroid.testInstrumentationRunnerArguments.siteOffset=0 \
 * -Pandroid.testInstrumentationRunnerArguments.siteLimit=25
 *
 * Results are emitted to logcat under tag "PersonalPageLoadPerf". Failures and timeouts are logged
 * as observations and do not fail the benchmark; many entries intentionally exercise redirects,
 * authentication walls, old sites, and unusual hosting.
 */
@Ignore("Performance benchmark — run manually, not in CI. See class kdoc for instructions.")
@RunWith(AndroidJUnit4::class)
class PersonalSitePageLoadPerfAndroidTest {

    @get:Rule
    val activityScenarioRule = activityScenarioRule<BrowserActivity>(
        BrowserActivity.intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            launchSource = InAppNavigation,
            queryExtra = "about:blank",
        ),
    )

    @Test
    fun benchmarkPersonalSiteCorpusToPageCommitVisible() {
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
            val sites = selectedSites(loadSites())
            log("Corpus entries selected: ${sites.size}")

            val samples = sites.mapIndexed { index, url ->
                val sample = measureOnce(webView, url, commits)
                logSample(index, sites.size, sample)
                sample
            }
            logSummary(samples)
        } finally {
            instrumentation.runOnMainSync {
                browserWebViewClient.webViewClientListener = originalListener
            }
        }
    }

    private fun loadSites(): List<String> =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(CORPUS_ASSET)
            .bufferedReader()
            .useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }

    private fun selectedSites(allSites: List<String>): List<String> {
        val args = InstrumentationRegistry.getArguments()
        val offset = args.getString("siteOffset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val requestedLimit = args.getString("siteLimit")?.toIntOrNull()?.coerceAtLeast(0)
        if (offset >= allSites.size) return emptyList()
        val remaining = allSites.drop(offset)
        return if (requestedLimit == null) remaining else remaining.take(requestedLimit)
    }

    private fun measureOnce(
        webView: WebView,
        requestedUrl: String,
        commits: LinkedBlockingQueue<Commit>,
    ): Sample {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        commits.clear()
        val startNs = SystemClock.elapsedRealtimeNanos()

        instrumentation.runOnMainSync {
            webView.loadUrl(requestedUrl)
        }

        val commit = commits.poll(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: return Sample.Timeout(requestedUrl)

        return Sample.Success(
            requestedUrl = requestedUrl,
            committedUrl = commit.url,
            elapsedNs = commit.timestampNs - startNs,
        )
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

    private fun logSample(index: Int, total: Int, sample: Sample) {
        when (sample) {
            is Sample.Success -> log(
                "[${index + 1}/$total] ${sample.requestedUrl} -> ${sample.committedUrl} " +
                    "commit_visible=${formatMs(sample.elapsedNs)} ms",
            )
            is Sample.Timeout -> log("[${index + 1}/$total] ${sample.requestedUrl} TIMEOUT after ${LOAD_TIMEOUT_MS}ms")
        }
    }

    private fun logSummary(samples: List<Sample>) {
        val successes = samples.filterIsInstance<Sample.Success>()
        val failures = samples.size - successes.size
        log("=== personal-site loadUrl -> onPageCommitVisible ===")
        log("success=${successes.size} timeout=$failures total=${samples.size}")
        if (successes.isEmpty()) return

        val sorted = successes.map { it.elapsedNs }.sorted()
        log(
            "min=${formatMs(sorted.first())} ms | median=${formatMs(percentile(sorted, 0.50))} ms | " +
                "p90=${formatMs(percentile(sorted, 0.90))} ms | max=${formatMs(sorted.last())} ms",
        )
        log("Network and site variance are intentionally included; compare the same corpus on the same device/WebView build.")
    }

    private fun percentile(sortedNs: List<Long>, percentile: Double): Long {
        val index = ((sortedNs.lastIndex) * percentile).toInt().coerceIn(sortedNs.indices)
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
        log("Corpus: $CORPUS_ASSET; real network and root-origin navigation")
        log("-----------------------")
    }

    private fun log(message: String) {
        Log.i(LOG_TAG, message)
    }

    private data class Commit(
        val url: String,
        val timestampNs: Long,
    )

    private sealed interface Sample {
        val requestedUrl: String

        data class Success(
            override val requestedUrl: String,
            val committedUrl: String,
            val elapsedNs: Long,
        ) : Sample

        data class Timeout(
            override val requestedUrl: String,
        ) : Sample
    }

    companion object {
        private const val LOG_TAG = "PersonalPageLoadPerf"
        private const val CORPUS_ASSET = "ddg-personal-sites.txt"
        private const val LOAD_TIMEOUT_MS = 20_000L
    }
}
