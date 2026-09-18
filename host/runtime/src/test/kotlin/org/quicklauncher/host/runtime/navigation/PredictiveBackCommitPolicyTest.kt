package org.quicklauncher.host.runtime.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.quicklauncher.contracts.domain.DestinationId

class PredictiveBackCommitPolicyTest {
    @Test
    fun `start progress and cancellation leave destination and active surface unchanged`() = runTest {
        val initialDestination = DestinationId.parse("org.quicklauncher.destination/center")
        var currentDestination = initialDestination
        var activeSurface: String? = "folder"
        var committed = false
        val observed = mutableListOf<BackProgress>()

        try {
            PredictiveBackCommitPolicy.collect(
                progress = flow {
                    observed += BackProgress.Started
                    emit(observed.last())
                    observed += BackProgress.Progressed(0.6f)
                    emit(observed.last())
                    throw CancellationException("gesture cancelled")
                },
            ) {
                committed = true
                currentDestination = DestinationId.parse("org.quicklauncher.destination/right")
                activeSurface = null
            }
            fail("A cancelled predictive-Back flow must propagate cancellation")
        } catch (_: CancellationException) {
            // AndroidX reports a cancelled predictive gesture by cancelling the progress collector.
        }

        assertFalse(committed)
        assertEquals(initialDestination, currentDestination)
        assertEquals("folder", activeSurface)
        assertEquals(
            listOf(BackProgress.Started, BackProgress.Progressed(0.6f)),
            observed,
        )
    }

    @Test
    fun `completed progress flow commits exactly once`() = runTest {
        var commits = 0
        val observed = listOf(BackProgress.Started, BackProgress.Progressed(1f))

        PredictiveBackCommitPolicy.collect(
            progress = flowOf(*observed.toTypedArray()),
        ) {
            commits += 1
        }

        assertEquals(1, commits)
        assertEquals(listOf(BackProgress.Started, BackProgress.Progressed(1f)), observed)
    }

    private sealed interface BackProgress {
        data object Started : BackProgress
        data class Progressed(val fraction: Float) : BackProgress
    }
}
