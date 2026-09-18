package org.quicklauncher.benchmark.macrobenchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class PhaseNinePerformanceBenchmark {
    @get:Rule
    val benchmark = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun homeEntry() {
        benchmark.measureRepeated(
            packageName = ScenarioContract.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = ScenarioContract.HOME_ITERATIONS,
            setupBlock = {
                pressHome()
                seedFixture()
            },
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun spatialGesture() {
        benchmark.measureRepeated(
            packageName = ScenarioContract.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            iterations = 1,
            setupBlock = {
                seedFixture()
                startActivityAndWait()
                waitForLauncher()
                horizontalRoundTrip()
            },
        ) {
            repeat(ScenarioContract.SPATIAL_TRANSITIONS / 2) { horizontalRoundTrip() }
        }
    }

    @Test
    fun appCatalogLoading() {
        benchmark.measureRepeated(
            packageName = ScenarioContract.PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric(
                    sectionName = ScenarioContract.CATALOG_TRACE,
                    mode = TraceSectionMetric.Mode.First,
                    label = "catalogReady",
                    targetPackageOnly = false,
                ),
            ),
            compilationMode = CompilationMode.None(),
            iterations = ScenarioContract.DEFAULT_ITERATIONS,
            setupBlock = { killProcess() },
        ) {
            Trace.beginSection(ScenarioContract.CATALOG_TRACE)
            try {
                runFixtureAction(ScenarioContract.ACTION_CATALOG)
            } finally {
                Trace.endSection()
            }
        }
    }

    @Test
    fun searchFirstResult() {
        benchmark.measureRepeated(
            packageName = ScenarioContract.PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric(
                    sectionName = ScenarioContract.SEARCH_TRACE,
                    mode = TraceSectionMetric.Mode.First,
                    label = "searchReady",
                    targetPackageOnly = false,
                ),
            ),
            compilationMode = CompilationMode.None(),
            iterations = ScenarioContract.DEFAULT_ITERATIONS,
            setupBlock = {
                seedFixture()
                startActivityAndWait()
                waitForLauncher()
                moveToSearchDestination()
            },
        ) {
            val input = checkNotNull(
                device.wait(
                    Until.findObject(By.clazz("android.widget.EditText")),
                    ScenarioContract.UI_TIMEOUT_MILLIS,
                ),
            ) { "Search presentation exposed no editable text node in the fixed fixture" }
            Trace.beginSection(ScenarioContract.SEARCH_TRACE)
            try {
                input.text = ScenarioContract.SEARCH_QUERY
                checkNotNull(
                    device.wait(
                        Until.findObject(By.textContains(ScenarioContract.SEARCH_RESULT)),
                        ScenarioContract.UI_TIMEOUT_MILLIS,
                    ),
                ) { "Fixed local search result did not appear" }
            } finally {
                Trace.endSection()
            }
        }
    }

    @Test
    fun currentPlusNeighborsMemory() {
        benchmark.measureRepeated(
            packageName = ScenarioContract.PACKAGE_NAME,
            metrics = listOf(
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Max,
                    subMetrics = listOf(
                        MemoryUsageMetric.SubMetric.RssAnon,
                        MemoryUsageMetric.SubMetric.RssFile,
                        MemoryUsageMetric.SubMetric.RssShmem,
                    ),
                ),
            ),
            compilationMode = CompilationMode.None(),
            iterations = ScenarioContract.DEFAULT_ITERATIONS,
            setupBlock = {
                seedFixture()
                startActivityAndWait()
                waitForLauncher()
            },
        ) {
            repeat(4) { horizontalRoundTrip() }
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.seedFixture() {
        killProcess()
        runFixtureAction(ScenarioContract.ACTION_SEED)
        killProcess()
    }

    private fun waitForLauncher() {
        check(device.wait(Until.hasObject(By.pkg(ScenarioContract.PACKAGE_NAME).depth(0)), ScenarioContract.UI_TIMEOUT_MILLIS)) {
            "Quicklauncher root did not appear"
        }
    }

    private fun horizontalRoundTrip() {
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width * 4 / 5, height / 2, width / 5, height / 2, 24)
        SystemClock.sleep(ScenarioContract.SETTLE_MILLIS)
        device.swipe(width / 5, height / 2, width * 4 / 5, height / 2, 24)
        SystemClock.sleep(ScenarioContract.SETTLE_MILLIS)
    }

    private fun moveToSearchDestination() {
        val root = checkNotNull(device.findObject(By.pkg(ScenarioContract.PACKAGE_NAME).depth(0))) {
            "Quicklauncher root is unavailable"
        }
        root.swipe(Direction.DOWN, 0.7f, 300)
        checkNotNull(device.wait(Until.findObject(By.desc("Search input")), ScenarioContract.UI_TIMEOUT_MILLIS)) {
            "Search destination did not settle"
        }
    }

    private fun runFixtureAction(action: String) {
        val latch = CountDownLatch(1)
        var resultCode = Activity.RESULT_CANCELED
        var resultData: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                resultCode = getResultCode()
                resultData = getResultData()
                latch.countDown()
            }
        }
        val intent = Intent(action)
            .setPackage(ScenarioContract.PACKAGE_NAME)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        instrumentation.context.sendOrderedBroadcast(
            intent,
            // The target receiver's manifest permission authenticates this sender. Supplying the
            // same value here would instead filter for receivers that also hold the permission.
            null,
            receiver,
            null,
            Activity.RESULT_CANCELED,
            null,
            Bundle.EMPTY,
        )
        check(latch.await(ScenarioContract.UI_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "Benchmark fixture action timed out: $action"
        }
        assertEquals("Benchmark fixture failed: $action ($resultData)", Activity.RESULT_OK, resultCode)
        assertEquals(ScenarioContract.FIXTURE_ID, resultData)
    }
}

/** Mirrors the immutable limits in docs/status/phase-9-release-scope.md. */
internal object ScenarioContract {
    const val PACKAGE_NAME = "org.quicklauncher"
    const val FIXTURE_PERMISSION = "org.quicklauncher.permission.CONTROL_BENCHMARK_FIXTURE"
    const val FIXTURE_ID = "phase9-five-destination-v1"
    const val ACTION_SEED = "org.quicklauncher.benchmark.SEED_FIVE_DESTINATIONS"
    const val ACTION_CATALOG = "org.quicklauncher.benchmark.RUN_CATALOG"
    const val CATALOG_TRACE = "quicklauncher.catalog.request-to-published"
    const val SEARCH_TRACE = "quicklauncher.search.ready"
    const val SEARCH_QUERY = "security"
    const val SEARCH_RESULT = "Security settings"
    const val HOME_ITERATIONS = 10
    const val SPATIAL_TRANSITIONS = 20
    const val DEFAULT_ITERATIONS = 10
    const val UI_TIMEOUT_MILLIS = 10_000L
    const val SETTLE_MILLIS = 220L
}
