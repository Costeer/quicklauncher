package org.quicklauncher.host.runtime.profile

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppIcon

enum class ProfileKind { PERSONAL, WORK, PRIVATE }

enum class ProfileAvailability { AVAILABLE, QUIET, LOCKED, UNRESOLVED }

enum class ProfileTransition { IDLE, PAUSING, RESUMING, LOCKING, UNLOCKING }

/** Host-safe projection of Android's Private Space entry-point user configuration. */
enum class PrivateSpaceEntryPoint { NOT_PRIVATE, SYSTEM_DEFAULT, HIDDEN }

data class ProfileRecord(
    val serial: ProfileSerial,
    val kind: ProfileKind,
    val availability: ProfileAvailability,
    val privateSpaceEntryPoint: PrivateSpaceEntryPoint = if (kind == ProfileKind.PRIVATE) {
        PrivateSpaceEntryPoint.SYSTEM_DEFAULT
    } else {
        PrivateSpaceEntryPoint.NOT_PRIVATE
    },
) {
    init {
        require(
            (kind == ProfileKind.PRIVATE) ==
                (privateSpaceEntryPoint != PrivateSpaceEntryPoint.NOT_PRIVATE),
        ) { "Private Space entry-point policy must apply only to a private profile" }
    }
}

data class PrivateProfileItem(
    val identity: AppActivityIdentity,
    val label: String,
    val icon: AppIcon,
) {
    init {
        require(identity.profile.value >= 0L)
        require(label.isNotBlank()) { "Private app label must not be blank" }
    }

    /** Private labels, package names, and activity names must never reach incidental logging. */
    override fun toString(): String = "PrivateProfileItem(redacted)"
}

sealed interface PrivateItemsResult {
    class Available(items: Collection<PrivateProfileItem>) : PrivateItemsResult {
        val items: List<PrivateProfileItem> = immutableList(items)
    }

    data object MissingProfile : PrivateItemsResult
    data object Locked : PrivateItemsResult
    data object SecurityDenied : PrivateItemsResult
    data object Unavailable : PrivateItemsResult
}

sealed interface ProfileInvalidation {
    data object Changed : ProfileInvalidation
    data class AvailabilityChanged(val profile: ProfileSerial) : ProfileInvalidation
}

enum class WorkModeRequestResult { REQUESTED, REJECTED, MISSING_PROFILE, WRONG_PROFILE_KIND, DENIED }

enum class PrivateLaunchResult {
    LAUNCHED,
    MISSING_PROFILE,
    WRONG_PROFILE_KIND,
    LOCKED,
    ACTIVITY_UNAVAILABLE,
    DENIED,
    REJECTED,
    CLOSED,
}

sealed interface PrivateSpaceSettingsResult {
    data object Launched : PrivateSpaceSettingsResult
    data object Unavailable : PrivateSpaceSettingsResult
    data object Denied : PrivateSpaceSettingsResult
    data object Rejected : PrivateSpaceSettingsResult
    data object Closed : PrivateSpaceSettingsResult
}

interface ProfilePlatform : AutoCloseable {
    val invalidations: Flow<ProfileInvalidation>

    /** Returns classifications and availability only. It must not read private app metadata. */
    suspend fun profiles(): List<ProfileRecord>

    /** Revalidates the profile kind and availability before reading any private metadata. */
    suspend fun privateItems(profile: ProfileSerial): PrivateItemsResult

    /** Revalidates the serial and kind before requesting quiet mode. */
    suspend fun setQuietMode(profile: ProfileSerial, quiet: Boolean): WorkModeRequestResult

    /** Reclassifies, unlock-checks, resolves, and launches the private activity in one operation. */
    suspend fun launchPrivate(identity: AppActivityIdentity): PrivateLaunchResult

    /** Opens the system-owned Private Space settings without exposing an Android IntentSender. */
    suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult =
        PrivateSpaceSettingsResult.Unavailable
}

data class ProfileState(
    val serial: ProfileSerial,
    val kind: ProfileKind,
    val availability: ProfileAvailability,
    val transition: ProfileTransition,
    val privateSpaceEntryPoint: PrivateSpaceEntryPoint = if (kind == ProfileKind.PRIVATE) {
        PrivateSpaceEntryPoint.SYSTEM_DEFAULT
    } else {
        PrivateSpaceEntryPoint.NOT_PRIVATE
    },
) {
    init {
        require(
            (kind == ProfileKind.PRIVATE) ==
                (privateSpaceEntryPoint != PrivateSpaceEntryPoint.NOT_PRIVATE),
        ) { "Private Space entry-point policy must apply only to a private profile" }
    }
}

class PrivateSpaceState(
    val profile: ProfileSerial?,
    val availability: ProfileAvailability,
    val transition: ProfileTransition,
    items: Collection<PrivateProfileItem>,
) {
    val items: List<PrivateProfileItem> = immutableList(items)

    init {
        require(availability == ProfileAvailability.AVAILABLE || items.isEmpty()) {
            "Unavailable Private Space cannot retain app metadata"
        }
        require(profile != null || items.isEmpty()) {
            "Unresolved Private Space cannot retain app metadata"
        }
    }
}

class ProfileCoordinatorState(
    profiles: Collection<ProfileState>,
    val privateSpace: PrivateSpaceState,
) {
    val profiles: List<ProfileState> = immutableList(profiles)

    fun profile(serial: ProfileSerial): ProfileState? = profiles.firstOrNull { it.serial == serial }

    companion object {
        val Empty = ProfileCoordinatorState(
            emptyList(),
            PrivateSpaceState(null, ProfileAvailability.UNRESOLVED, ProfileTransition.IDLE, emptyList()),
        )
    }
}

sealed interface WorkModeResult {
    data object Requested : WorkModeResult
    data object AlreadyInRequestedMode : WorkModeResult
    data object TransitionInProgress : WorkModeResult
    data object MissingProfile : WorkModeResult
    data object NotWorkProfile : WorkModeResult
    data object Denied : WorkModeResult
    data object Rejected : WorkModeResult
    data object Closed : WorkModeResult
}

sealed interface PrivateSpaceActionResult {
    data object Requested : PrivateSpaceActionResult
    data object AlreadyInRequestedMode : PrivateSpaceActionResult
    data object TransitionInProgress : PrivateSpaceActionResult
    data object Unavailable : PrivateSpaceActionResult
    data object Denied : PrivateSpaceActionResult
    data object Rejected : PrivateSpaceActionResult
    data object Closed : PrivateSpaceActionResult
}

interface ProfileCoordinator : AutoCloseable {
    val state: StateFlow<ProfileCoordinatorState>

    suspend fun refresh()
    suspend fun setWorkMode(profile: ProfileSerial, enabled: Boolean): WorkModeResult
    suspend fun setPrivateSpaceLocked(locked: Boolean): PrivateSpaceActionResult
    suspend fun launchPrivate(identity: AppActivityIdentity): PrivateLaunchResult
    suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult =
        PrivateSpaceSettingsResult.Unavailable
}

class DefaultProfileCoordinator(
    private val platform: ProfilePlatform,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ProfileCoordinator {
    private val closed = AtomicBoolean(false)
    private val privateMetadataEpoch = AtomicLong(0L)
    private val privateMetadataRead = AtomicReference<ActivePrivateMetadataRead?>(null)
    private val privateTransitionPoll = AtomicReference<Job?>(null)
    private val mutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow(ProfileCoordinatorState.Empty)

    override val state: StateFlow<ProfileCoordinatorState> = mutableState.asStateFlow()

    init {
        // Subscribe before the constructor returns. Profile broadcasts are edge-triggered, so a
        // callback delivered immediately after construction must not be lost.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            platform.invalidations.collect { invalidation ->
                if (invalidation is ProfileInvalidation.AvailabilityChanged) {
                    purgeUnavailableProfile(invalidation.profile)
                }
                refresh()
            }
        }
    }

    override suspend fun refresh() {
        if (closed.get()) return
        mutex.withLock {
            val records = try {
                withContext(dispatcher) { platform.profiles() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                mutableState.value = ProfileCoordinatorState.Empty
                return@withLock
            }
            require(records.map(ProfileRecord::serial).distinct().size == records.size) {
                "Profile platform returned duplicate serials"
            }
            val previous = mutableState.value.profiles.associateBy(ProfileState::serial)
            val profiles = records.map { record ->
                ProfileState(
                    record.serial,
                    record.kind,
                    record.availability,
                    settledTransition(previous[record.serial], record),
                    record.privateSpaceEntryPoint,
                )
            }
            val private = records.singleOrNull { it.kind == ProfileKind.PRIVATE }
            val oldPrivate = mutableState.value.privateSpace
            val metadataEpoch = privateMetadataEpoch.get()
            val privateResult = if (private?.availability == ProfileAvailability.AVAILABLE) {
                try {
                    readPrivateItems(private.serial, metadataEpoch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    PrivateItemsResult.Unavailable
                }
            } else {
                null
            }
            val privateResultIsCurrent = metadataEpoch == privateMetadataEpoch.get()
            val privateItems = if (privateResultIsCurrent) {
                (privateResult as? PrivateItemsResult.Available)?.items.orEmpty()
            } else {
                emptyList()
            }
            val privateAvailability = when {
                private == null -> ProfileAvailability.UNRESOLVED
                !privateResultIsCurrent -> ProfileAvailability.UNRESOLVED
                privateResult == PrivateItemsResult.MissingProfile ||
                    privateResult == PrivateItemsResult.SecurityDenied ||
                    privateResult == PrivateItemsResult.Unavailable -> ProfileAvailability.UNRESOLVED
                privateResult == PrivateItemsResult.Locked -> ProfileAvailability.LOCKED
                else -> private.availability
            }
            mutableState.value = ProfileCoordinatorState(
                profiles,
                PrivateSpaceState(
                    profile = private?.serial,
                    availability = privateAvailability,
                    transition = settledPrivateTransition(oldPrivate, privateAvailability),
                    items = privateItems,
                ),
            )
        }
    }

    override suspend fun setWorkMode(profile: ProfileSerial, enabled: Boolean): WorkModeResult {
        if (closed.get()) return WorkModeResult.Closed
        return mutex.withLock {
            val current = mutableState.value.profile(profile) ?: return@withLock WorkModeResult.MissingProfile
            if (current.kind != ProfileKind.WORK) return@withLock WorkModeResult.NotWorkProfile
            if (current.transition != ProfileTransition.IDLE) {
                return@withLock WorkModeResult.TransitionInProgress
            }
            val alreadyEnabled = current.availability == ProfileAvailability.AVAILABLE
            if (alreadyEnabled == enabled) return@withLock WorkModeResult.AlreadyInRequestedMode
            when (withContext(dispatcher) { platform.setQuietMode(profile, quiet = !enabled) }) {
                WorkModeRequestResult.REQUESTED -> {
                    updateProfileTransition(
                        profile,
                        if (enabled) ProfileTransition.RESUMING else ProfileTransition.PAUSING,
                    )
                    WorkModeResult.Requested
                }
                WorkModeRequestResult.MISSING_PROFILE -> WorkModeResult.MissingProfile
                WorkModeRequestResult.WRONG_PROFILE_KIND -> WorkModeResult.NotWorkProfile
                WorkModeRequestResult.DENIED -> WorkModeResult.Denied
                WorkModeRequestResult.REJECTED -> WorkModeResult.Rejected
            }
        }
    }

    override suspend fun setPrivateSpaceLocked(locked: Boolean): PrivateSpaceActionResult {
        if (closed.get()) return PrivateSpaceActionResult.Closed
        return mutex.withLock {
            val private = mutableState.value.privateSpace
            val serial = private.profile ?: return@withLock PrivateSpaceActionResult.Unavailable
            if (private.transition != ProfileTransition.IDLE) {
                return@withLock PrivateSpaceActionResult.TransitionInProgress
            }
            when {
                private.availability == ProfileAvailability.UNRESOLVED ->
                    return@withLock PrivateSpaceActionResult.Unavailable
                locked && private.availability == ProfileAvailability.LOCKED ->
                    return@withLock PrivateSpaceActionResult.AlreadyInRequestedMode
                !locked && private.availability == ProfileAvailability.AVAILABLE ->
                    return@withLock PrivateSpaceActionResult.AlreadyInRequestedMode
                locked && private.availability != ProfileAvailability.AVAILABLE ->
                    return@withLock PrivateSpaceActionResult.Unavailable
                !locked && private.availability != ProfileAvailability.LOCKED ->
                    return@withLock PrivateSpaceActionResult.Unavailable
            }
            if (locked) beginPrivateLock(serial, private)
            when (withContext(dispatcher) { platform.setQuietMode(serial, quiet = locked) }) {
                WorkModeRequestResult.REQUESTED -> {
                    if (!locked) publishPrivateTransition(serial, private, ProfileTransition.UNLOCKING)
                    pollPrivateTransition(serial, locked)
                    PrivateSpaceActionResult.Requested
                }
                WorkModeRequestResult.DENIED -> {
                    if (locked) failPrivateLock(serial)
                    PrivateSpaceActionResult.Denied
                }
                WorkModeRequestResult.REJECTED -> {
                    if (locked) failPrivateLock(serial)
                    PrivateSpaceActionResult.Rejected
                }
                WorkModeRequestResult.MISSING_PROFILE,
                WorkModeRequestResult.WRONG_PROFILE_KIND,
                -> {
                    if (locked) failPrivateLock(serial)
                    PrivateSpaceActionResult.Unavailable
                }
            }
        }
    }

    override suspend fun launchPrivate(identity: AppActivityIdentity): PrivateLaunchResult {
        if (closed.get()) return PrivateLaunchResult.CLOSED
        return try {
            withContext(dispatcher) { platform.launchPrivate(identity) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            PrivateLaunchResult.REJECTED
        }
    }

    override suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult {
        if (closed.get()) return PrivateSpaceSettingsResult.Closed
        return try {
            withContext(dispatcher) { platform.openPrivateSpaceSettings() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            PrivateSpaceSettingsResult.Rejected
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        privateMetadataEpoch.incrementAndGet()
        privateMetadataRead.getAndSet(null)?.job?.cancel()
        mutableState.value = ProfileCoordinatorState.Empty
        scope.cancel()
        platform.close()
    }

    private fun purgeUnavailableProfile(serial: ProfileSerial) {
        val activeRead = privateMetadataRead.get()
        val current = mutableState.value
        val private = current.privateSpace
        if (private.profile != serial && activeRead?.serial != serial) return
        privateMetadataEpoch.incrementAndGet()
        if (activeRead?.serial == serial && privateMetadataRead.compareAndSet(activeRead, null)) {
            activeRead.job.cancel()
        }
        if (private.profile != serial) return
        mutableState.value = ProfileCoordinatorState(
            current.profiles.map {
                if (it.serial == serial) {
                    it.copy(availability = ProfileAvailability.UNRESOLVED)
                } else {
                    it
                }
            },
            PrivateSpaceState(serial, ProfileAvailability.UNRESOLVED, private.transition, emptyList()),
        )
    }

    private fun updateProfileTransition(serial: ProfileSerial, transition: ProfileTransition) {
        val current = mutableState.value
        mutableState.value = ProfileCoordinatorState(
            current.profiles.map { if (it.serial == serial) it.copy(transition = transition) else it },
            current.privateSpace,
        )
    }

    private fun beginPrivateLock(serial: ProfileSerial, private: PrivateSpaceState) {
        privateMetadataEpoch.incrementAndGet()
        val activeRead = privateMetadataRead.get()
        if (activeRead?.serial == serial && privateMetadataRead.compareAndSet(activeRead, null)) {
            activeRead.job.cancel()
        }
        publishPrivateTransition(serial, private, ProfileTransition.LOCKING, items = emptyList())
    }

    private fun publishPrivateTransition(
        serial: ProfileSerial,
        private: PrivateSpaceState,
        transition: ProfileTransition,
        items: Collection<PrivateProfileItem> = private.items,
    ) {
        val current = mutableState.value
        mutableState.value = ProfileCoordinatorState(
            current.profiles.map {
                if (it.serial == serial) it.copy(transition = transition) else it
            },
            PrivateSpaceState(serial, private.availability, transition, items),
        )
    }

    private fun failPrivateLock(serial: ProfileSerial) {
        val current = mutableState.value
        mutableState.value = ProfileCoordinatorState(
            current.profiles.map {
                if (it.serial == serial) {
                    it.copy(
                        availability = ProfileAvailability.UNRESOLVED,
                        transition = ProfileTransition.IDLE,
                    )
                } else {
                    it
                }
            },
            PrivateSpaceState(
                serial,
                ProfileAvailability.UNRESOLVED,
                ProfileTransition.IDLE,
                emptyList(),
            ),
        )
    }

    private fun pollPrivateTransition(serial: ProfileSerial, locked: Boolean) {
        privateTransitionPoll.getAndSet(
            scope.launch {
                repeat(PRIVATE_TRANSITION_POLL_ATTEMPTS) {
                    delay(PRIVATE_TRANSITION_POLL_INTERVAL_MS)
                    refresh()
                    val private = mutableState.value.privateSpace
                    if (private.profile != serial) return@launch
                    val settled = if (locked) {
                        private.availability == ProfileAvailability.LOCKED
                    } else {
                        private.availability == ProfileAvailability.AVAILABLE
                    }
                    if (settled) return@launch
                }
            },
        )?.cancel()
    }

    private suspend fun readPrivateItems(
        serial: ProfileSerial,
        metadataEpoch: Long,
    ): PrivateItemsResult = try {
        withContext(dispatcher) {
            val readJob = currentCoroutineContext().job
            val activeRead = ActivePrivateMetadataRead(serial, readJob)
            privateMetadataRead.set(activeRead)
            if (metadataEpoch != privateMetadataEpoch.get()) readJob.cancel()
            try {
                platform.privateItems(serial)
            } finally {
                privateMetadataRead.compareAndSet(activeRead, null)
            }
        }
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (metadataEpoch == privateMetadataEpoch.get()) throw cancelled
        PrivateItemsResult.Unavailable
    }
}

private const val PRIVATE_TRANSITION_POLL_ATTEMPTS = 600
private const val PRIVATE_TRANSITION_POLL_INTERVAL_MS = 250L

private data class ActivePrivateMetadataRead(val serial: ProfileSerial, val job: Job)

private fun settledTransition(previous: ProfileState?, current: ProfileRecord): ProfileTransition =
    when (previous?.transition) {
        ProfileTransition.PAUSING -> if (current.availability == ProfileAvailability.QUIET) {
            ProfileTransition.IDLE
        } else {
            ProfileTransition.PAUSING
        }
        ProfileTransition.RESUMING -> if (current.availability == ProfileAvailability.AVAILABLE) {
            ProfileTransition.IDLE
        } else {
            ProfileTransition.RESUMING
        }
        else -> ProfileTransition.IDLE
    }

private fun settledPrivateTransition(
    previous: PrivateSpaceState,
    availability: ProfileAvailability,
): ProfileTransition = when (previous.transition) {
    ProfileTransition.LOCKING -> if (availability != ProfileAvailability.AVAILABLE) {
        ProfileTransition.IDLE
    } else {
        ProfileTransition.LOCKING
    }
    ProfileTransition.UNLOCKING -> if (availability == ProfileAvailability.AVAILABLE) {
        ProfileTransition.IDLE
    } else {
        ProfileTransition.UNLOCKING
    }
    else -> ProfileTransition.IDLE
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
