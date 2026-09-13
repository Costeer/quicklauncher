package org.quicklauncher.host.runtime.notifications

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.ui.NotificationIndicator
import org.quicklauncher.host.data.preferences.NotificationStyle

enum class NotificationListenerStatus { NO_ACCESS, CONNECTED, DISCONNECTED, UNAVAILABLE }

data class NotificationCount(val identity: ProfilePackageIdentity, val count: Int) {
    init {
        require(count > 0) { "Notification count must be positive" }
    }
}

class NotificationCountSnapshot(counts: Collection<NotificationCount>) {
    val counts: List<NotificationCount> = immutableList(counts)

    init {
        require(counts.map(NotificationCount::identity).distinct().size == counts.size) {
            "Notification count identities must be unique"
        }
    }

    companion object {
        val Empty = NotificationCountSnapshot(emptyList())
    }
}

enum class NotificationPlatformActionResult { OPENED, UNAVAILABLE, DENIED }

/** No notification content type exists at this seam. */
interface NotificationPlatform : AutoCloseable {
    val invalidations: Flow<Unit>
    suspend fun status(): NotificationListenerStatus
    suspend fun counts(): NotificationCountSnapshot
    suspend fun requestAccess(): NotificationPlatformActionResult
    suspend fun openRestrictedSettingsRecovery(): NotificationPlatformActionResult
}

enum class IndicatorProfileKind { PERSONAL, WORK }

data class IndicatorEligibleApp(
    val identity: ProfilePackageIdentity,
    val profileKind: IndicatorProfileKind,
    val profileAvailable: Boolean,
    val collectionVisible: Boolean,
    val indicatorEligible: Boolean,
)

enum class NotificationAccessState {
    ACTION_REQUIRED,
    AWAITING_DECISION,
    CONNECTED,
    DISCONNECTED,
    DENIED,
    REVOKED,
    UNAVAILABLE,
}

class NotificationIndicatorState(
    val access: NotificationAccessState,
    indicators: Map<ProfilePackageIdentity, NotificationIndicator>,
) {
    private val values: Map<ProfilePackageIdentity, NotificationIndicator> =
        Collections.unmodifiableMap(LinkedHashMap(indicators))

    fun indicator(identity: ProfilePackageIdentity): NotificationIndicator =
        values[identity] ?: NotificationIndicator.None

    fun asMap(): Map<ProfilePackageIdentity, NotificationIndicator> = values

    companion object {
        val Initial = NotificationIndicatorState(NotificationAccessState.ACTION_REQUIRED, emptyMap())
    }
}

sealed interface NotificationAccessAction {
    data object OpenedSettings : NotificationAccessAction
    data object Unavailable : NotificationAccessAction
    data object Denied : NotificationAccessAction
    data object Closed : NotificationAccessAction
}

interface NotificationIndicatorCoordinator : AutoCloseable {
    val state: StateFlow<NotificationIndicatorState>
    suspend fun refresh(apps: Collection<IndicatorEligibleApp>)
    suspend fun refreshAfterAccessSettings(apps: Collection<IndicatorEligibleApp>)
    suspend fun requestAccess(): NotificationAccessAction
    suspend fun openRestrictedSettingsRecovery(): NotificationAccessAction
}

class DefaultNotificationIndicatorCoordinator(
    private val platform: NotificationPlatform,
    notificationStyle: Flow<NotificationStyle>,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : NotificationIndicatorCoordinator {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow(NotificationIndicatorState.Initial)
    private var currentStyle = NotificationStyle.HIDDEN
    private var currentApps: List<IndicatorEligibleApp> = emptyList()
    private var currentCounts = NotificationCountSnapshot.Empty
    private var requestedAccess = false
    private var connectedBefore = false

    override val state: StateFlow<NotificationIndicatorState> = mutableState.asStateFlow()

    init {
        // Start both collectors synchronously so the initial preference and the first listener
        // callback are visible to a refresh invoked immediately after construction.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            notificationStyle.collect { style ->
                mutex.withLock {
                    currentStyle = style
                    publish(mutableState.value.access)
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            platform.invalidations.collect {
                refresh(currentApps)
            }
        }
    }

    override suspend fun refresh(apps: Collection<IndicatorEligibleApp>) {
        refreshInternal(apps, returnedFromSettings = false)
    }

    override suspend fun refreshAfterAccessSettings(apps: Collection<IndicatorEligibleApp>) {
        refreshInternal(apps, returnedFromSettings = true)
    }

    override suspend fun requestAccess(): NotificationAccessAction {
        if (closed.get()) return NotificationAccessAction.Closed
        val result = withContext(dispatcher) { platform.requestAccess() }
        return mutex.withLock {
            when (result) {
                NotificationPlatformActionResult.OPENED -> {
                    requestedAccess = true
                    mutableState.value = NotificationIndicatorState(
                        NotificationAccessState.AWAITING_DECISION,
                        emptyMap(),
                    )
                    NotificationAccessAction.OpenedSettings
                }
                NotificationPlatformActionResult.UNAVAILABLE -> NotificationAccessAction.Unavailable
                NotificationPlatformActionResult.DENIED -> NotificationAccessAction.Denied
            }
        }
    }

    override suspend fun openRestrictedSettingsRecovery(): NotificationAccessAction {
        if (closed.get()) return NotificationAccessAction.Closed
        return when (withContext(dispatcher) { platform.openRestrictedSettingsRecovery() }) {
            NotificationPlatformActionResult.OPENED -> NotificationAccessAction.OpenedSettings
            NotificationPlatformActionResult.UNAVAILABLE -> NotificationAccessAction.Unavailable
            NotificationPlatformActionResult.DENIED -> NotificationAccessAction.Denied
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        currentCounts = NotificationCountSnapshot.Empty
        currentApps = emptyList()
        mutableState.value = NotificationIndicatorState.Initial
        scope.cancel()
        platform.close()
    }

    private suspend fun refreshInternal(
        apps: Collection<IndicatorEligibleApp>,
        returnedFromSettings: Boolean,
    ) {
        if (closed.get()) return
        val immutableApps = immutableList(apps)
        require(immutableApps.map(IndicatorEligibleApp::identity).distinct().size == immutableApps.size) {
            "Indicator eligibility identities must be unique"
        }
        mutex.withLock {
            currentApps = immutableApps
            val status = try {
                withContext(dispatcher) { platform.status() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                NotificationListenerStatus.UNAVAILABLE
            }
            val access = when (status) {
                NotificationListenerStatus.CONNECTED -> {
                    connectedBefore = true
                    requestedAccess = false
                    NotificationAccessState.CONNECTED
                }
                NotificationListenerStatus.DISCONNECTED -> NotificationAccessState.DISCONNECTED
                NotificationListenerStatus.UNAVAILABLE -> NotificationAccessState.UNAVAILABLE
                NotificationListenerStatus.NO_ACCESS -> when {
                    connectedBefore -> NotificationAccessState.REVOKED
                    returnedFromSettings && requestedAccess -> NotificationAccessState.DENIED
                    requestedAccess -> NotificationAccessState.AWAITING_DECISION
                    else -> NotificationAccessState.ACTION_REQUIRED
                }
            }
            currentCounts = if (access == NotificationAccessState.CONNECTED) {
                try {
                    withContext(dispatcher) { platform.counts() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    NotificationCountSnapshot.Empty
                }
            } else {
                NotificationCountSnapshot.Empty
            }
            publish(access)
        }
    }

    private fun publish(access: NotificationAccessState) {
        val eligible = currentApps.asSequence()
            .filter {
                it.profileAvailable && it.collectionVisible && it.indicatorEligible
            }
            .map(IndicatorEligibleApp::identity)
            .toSet()
        val values = if (
            access != NotificationAccessState.CONNECTED || currentStyle == NotificationStyle.HIDDEN
        ) {
            emptyMap()
        } else {
            currentCounts.counts.asSequence()
                .filter { it.identity in eligible }
                .associate { count -> count.identity to indicator(currentStyle, count.count) }
        }
        mutableState.value = NotificationIndicatorState(access, values)
    }
}

private fun indicator(style: NotificationStyle, count: Int): NotificationIndicator = when (style) {
    NotificationStyle.HIDDEN -> NotificationIndicator.None
    NotificationStyle.DOT -> NotificationIndicator.Dot
    NotificationStyle.APPROXIMATE_COUNT -> NotificationIndicator.ApproximateCount(
        APPROXIMATE_BUCKETS.firstOrNull { count <= it } ?: APPROXIMATE_BUCKETS.last(),
    )
}

private val APPROXIMATE_BUCKETS = intArrayOf(1, 2, 5, 10, 20, 50, 99)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
