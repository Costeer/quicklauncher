package org.quicklauncher.host.runtime.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Keeps predictive-Back progress observational: only a normally completed progress flow commits
 * the Back action. AndroidX cancels the collector when a gesture is cancelled, so cancellation
 * propagates and [onCommitted] is not invoked.
 */
object PredictiveBackCommitPolicy {
    suspend fun <T> collect(
        progress: Flow<T>,
        onCommitted: suspend () -> Unit,
    ) {
        progress.collect()
        onCommitted()
    }
}
