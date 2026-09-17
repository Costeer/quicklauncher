package org.quicklauncher.host.runtime.shortcuts

import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ShortcutTarget
import org.quicklauncher.host.data.store.StoreRejection

data class ShortcutOwner(
    val profile: ProfileSerial,
    val packageName: PackageName,
)

data class ShortcutTargetIdentity(
    val profile: ProfileSerial,
    val packageName: PackageName,
    val shortcutId: ShortcutId,
) {
    val owner: ShortcutOwner get() = ShortcutOwner(profile, packageName)
}

class ShortcutIcon private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is ShortcutIcon && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "ShortcutIcon(${value.size} bytes)"

    companion object {
        fun of(bytes: ByteArray): ShortcutIcon {
            require(bytes.isNotEmpty()) { "Shortcut icon bytes must not be empty" }
            return ShortcutIcon(bytes)
        }
    }
}

data class DiscoveredShortcut(
    val target: ShortcutTargetIdentity,
    val label: String,
    val icon: ShortcutIcon?,
    val dynamic: Boolean,
    val pinned: Boolean,
    val enabled: Boolean,
) {
    init {
        require(label.isNotBlank()) { "Shortcut label must not be blank" }
        require(dynamic || pinned) { "Only dynamic or pinned shortcuts may enter the host" }
    }
}

enum class ShortcutUnavailableReason {
    MISSING,
    DISABLED,
    PROFILE_LOCKED,
    PROFILE_UNAVAILABLE,
    PRIVATE_PROFILE,
    SECURITY_DENIED,
    PLATFORM_FAILURE,
}

sealed interface ShortcutQueryResult {
    class Available(shortcuts: Collection<DiscoveredShortcut>) : ShortcutQueryResult {
        val shortcuts: List<DiscoveredShortcut> = immutableList(shortcuts)

        init {
            require(this.shortcuts.map(DiscoveredShortcut::target).distinct().size == this.shortcuts.size) {
                "Shortcut query results must have unique targets"
            }
        }
    }

    data class Unavailable(val reason: ShortcutUnavailableReason) : ShortcutQueryResult
}

class ShortcutPinRequest(
    val owner: ShortcutOwner,
    shortcutIds: Collection<ShortcutId>,
) {
    val shortcutIds: Set<ShortcutId> = Collections.unmodifiableSet(LinkedHashSet(shortcutIds))

    override fun equals(other: Any?): Boolean = other is ShortcutPinRequest &&
        owner == other.owner && shortcutIds == other.shortcutIds

    override fun hashCode(): Int = 31 * owner.hashCode() + shortcutIds.hashCode()

    override fun toString(): String = "ShortcutPinRequest(owner=$owner, shortcutIds=$shortcutIds)"
}

sealed interface ShortcutPinResult {
    data object Applied : ShortcutPinResult
    data class Unavailable(val reason: ShortcutUnavailableReason) : ShortcutPinResult
}

sealed interface ShortcutLaunchResult {
    data object Launched : ShortcutLaunchResult
    data class Unavailable(val reason: ShortcutUnavailableReason) : ShortcutLaunchResult
    data object Closed : ShortcutLaunchResult
}

sealed interface ShortcutInvalidation {
    data object Changed : ShortcutInvalidation
}

/** Android-free seam for profile-aware shortcut discovery, pinning, and launch. */
interface ShortcutPlatform : AutoCloseable {
    val invalidations: Flow<ShortcutInvalidation>

    suspend fun query(owner: ShortcutOwner): ShortcutQueryResult

    /** Replaces the launcher's complete pinned set for [ShortcutPinRequest.owner]. */
    suspend fun pin(request: ShortcutPinRequest): ShortcutPinResult

    suspend fun launch(target: ShortcutTargetIdentity): ShortcutLaunchResult
}

data class ShortcutPlacement(
    val id: ContentItemId,
    val target: ShortcutTargetIdentity,
)

fun interface ShortcutPlacementSource {
    suspend fun read(): List<ShortcutPlacement>

    suspend fun create(id: ContentItemId, target: ShortcutTargetIdentity): CommitResult? = null
}

class StoreShortcutPlacementSource(private val store: LauncherStore) : ShortcutPlacementSource {
    override suspend fun read(): List<ShortcutPlacement> = store.read().contentItems.mapNotNull { item ->
        if (item.kind != ContentItemKind.SHORTCUT) return@mapNotNull null
        val target = requireNotNull(item.shortcutTarget) { "Shortcut record is missing its target" }
        ShortcutPlacement(
            id = item.id,
            target = ShortcutTargetIdentity(target.profile, target.packageName, target.shortcutId),
        )
    }

    override suspend fun create(id: ContentItemId, target: ShortcutTargetIdentity): CommitResult {
        val before = store.read()
        return store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.CreateShortcutPlacement(
                        ContentItemRecord.shortcut(
                            id = id,
                            encoded = "{}",
                            target = ShortcutTarget(target.profile, target.packageName, target.shortcutId),
                        ),
                    ),
                ),
            ),
        )
    }
}

fun interface ShortcutPlacementIdentitySource {
    fun next(): ContentItemId
}

private val RandomShortcutPlacementIdentitySource = ShortcutPlacementIdentitySource {
    ContentItemId.parse("org.quicklauncher.content/shortcut-${UUID.randomUUID().toString().lowercase()}")
}

sealed interface ResolvedShortcutPlacement {
    val id: ContentItemId
    val target: ShortcutTargetIdentity

    data class Available(
        override val id: ContentItemId,
        override val target: ShortcutTargetIdentity,
        val label: String,
        val icon: ShortcutIcon?,
        val dynamic: Boolean,
        val pinned: Boolean,
    ) : ResolvedShortcutPlacement

    data class Unavailable(
        override val id: ContentItemId,
        override val target: ShortcutTargetIdentity,
        val reason: ShortcutUnavailableReason,
    ) : ResolvedShortcutPlacement
}

class ShortcutSnapshot(
    placements: Collection<ResolvedShortcutPlacement>,
    reconciliation: Map<ShortcutOwner, ShortcutPinResult>,
    availableShortcuts: Collection<DiscoveredShortcut> = emptyList(),
    unavailableOwners: Map<ShortcutOwner, ShortcutUnavailableReason> = emptyMap(),
) {
    val placements: List<ResolvedShortcutPlacement> = immutableList(placements)
    val reconciliation: Map<ShortcutOwner, ShortcutPinResult> =
        Collections.unmodifiableMap(LinkedHashMap(reconciliation))
    val availableShortcuts: List<DiscoveredShortcut> = immutableList(availableShortcuts)
    val unavailableOwners: Map<ShortcutOwner, ShortcutUnavailableReason> =
        Collections.unmodifiableMap(LinkedHashMap(unavailableOwners))

    companion object {
        val Empty = ShortcutSnapshot(emptyList(), emptyMap())
    }
}

interface ShortcutCoordinator : AutoCloseable {
    val state: StateFlow<ShortcutSnapshot>

    suspend fun refresh()

    /** Refreshes discovery only for owners already admitted by host visibility and profile policy. */
    suspend fun refresh(eligibleOwners: Collection<ShortcutOwner>) = refresh()

    suspend fun createPlacement(target: ShortcutTargetIdentity): ShortcutPlacementCreation =
        ShortcutPlacementCreation.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)

    suspend fun launch(placementId: ContentItemId): ShortcutLaunchResult

    /** Revalidates and launches a currently discoverable dynamic or pinned search target. */
    suspend fun launch(target: ShortcutTargetIdentity): ShortcutLaunchResult =
        ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.MISSING)
}

sealed interface ShortcutPlacementCreation {
    data class Created(val placement: ShortcutPlacement) : ShortcutPlacementCreation
    data class Unavailable(val reason: ShortcutUnavailableReason) : ShortcutPlacementCreation
    data class Rejected(val reason: StoreRejection) : ShortcutPlacementCreation
}

/** Owns durable-placement reconciliation and exposes only sanitized shortcut records. */
class DefaultShortcutCoordinator(
    private val platform: ShortcutPlatform,
    private val placements: ShortcutPlacementSource,
    parentScope: CoroutineScope,
    private val identities: ShortcutPlacementIdentitySource = RandomShortcutPlacementIdentitySource,
) : ShortcutCoordinator {
    private val closed = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow(ShortcutSnapshot.Empty)
    private val reconciledOwners = LinkedHashSet<ShortcutOwner>()
    private var eligibleOwners = emptyList<ShortcutOwner>()

    override val state: StateFlow<ShortcutSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            platform.invalidations.collect { refresh() }
        }
    }

    override suspend fun refresh() {
        refreshMutex.withLock { refreshLocked() }
    }

    override suspend fun refresh(eligibleOwners: Collection<ShortcutOwner>) {
        check(!closed.get()) { "Shortcut coordinator is closed" }
        refreshMutex.withLock {
            this.eligibleOwners = immutableList(LinkedHashSet(eligibleOwners))
            refreshLocked()
        }
    }

    override suspend fun createPlacement(target: ShortcutTargetIdentity): ShortcutPlacementCreation {
        check(!closed.get()) { "Shortcut coordinator is closed" }
        return refreshMutex.withLock {
            if (target.owner !in eligibleOwners) {
                return@withLock ShortcutPlacementCreation.Unavailable(
                    ShortcutUnavailableReason.PROFILE_UNAVAILABLE,
                )
            }
            when (val query = platformQuery(target.owner)) {
                is ShortcutQueryResult.Unavailable ->
                    return@withLock ShortcutPlacementCreation.Unavailable(query.reason)
                is ShortcutQueryResult.Available -> {
                    val current = query.shortcuts.singleOrNull { it.target == target }
                        ?: return@withLock ShortcutPlacementCreation.Unavailable(
                            ShortcutUnavailableReason.MISSING,
                        )
                    if (!current.enabled) {
                        return@withLock ShortcutPlacementCreation.Unavailable(
                            ShortcutUnavailableReason.DISABLED,
                        )
                    }
                }
            }
            val placement = ShortcutPlacement(identities.next(), target)
            when (val committed = placements.create(placement.id, target)) {
                null -> ShortcutPlacementCreation.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
                is CommitResult.Rejected -> ShortcutPlacementCreation.Rejected(committed.reason)
                is CommitResult.Committed -> {
                    refreshLocked()
                    ShortcutPlacementCreation.Created(placement)
                }
            }
        }
    }

    override suspend fun launch(placementId: ContentItemId): ShortcutLaunchResult {
        if (closed.get()) return ShortcutLaunchResult.Closed
        val placement = placements.read().singleOrNull { it.id == placementId }
            ?: return ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.MISSING)
        val query = platformQuery(placement.target.owner)
        if (query is ShortcutQueryResult.Unavailable) {
            return ShortcutLaunchResult.Unavailable(query.reason)
        }
        query as ShortcutQueryResult.Available
        val current = query.shortcuts.singleOrNull { it.target == placement.target }
            ?: return ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.MISSING)
        if (!current.enabled) return ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.DISABLED)
        return try {
            platform.launch(placement.target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
        } catch (_: RuntimeException) {
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
        }
    }

    override suspend fun launch(target: ShortcutTargetIdentity): ShortcutLaunchResult {
        if (closed.get()) return ShortcutLaunchResult.Closed
        if (target.owner !in eligibleOwners) {
            return ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.PROFILE_UNAVAILABLE)
        }
        val query = platformQuery(target.owner)
        if (query is ShortcutQueryResult.Unavailable) {
            return ShortcutLaunchResult.Unavailable(query.reason)
        }
        val current = (query as ShortcutQueryResult.Available).shortcuts.singleOrNull {
            it.target == target
        }
            ?: return ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.MISSING)
        if (!current.enabled) {
            return ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.DISABLED)
        }
        return try {
            platform.launch(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
        } catch (_: RuntimeException) {
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        platform.close()
    }

    private suspend fun platformQuery(owner: ShortcutOwner): ShortcutQueryResult = try {
        platform.query(owner)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
    } catch (_: RuntimeException) {
        ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
    }

    private suspend fun platformPin(request: ShortcutPinRequest): ShortcutPinResult = try {
        platform.pin(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        ShortcutPinResult.Unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
    } catch (_: RuntimeException) {
        ShortcutPinResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
    }

    private suspend fun refreshLocked() {
        check(!closed.get()) { "Shortcut coordinator is closed" }
        val durable = placements.read()
        require(durable.map(ShortcutPlacement::id).distinct().size == durable.size) {
            "Shortcut placements must have unique content item identities"
        }
        val byOwner = durable.groupBy { it.target.owner }
        val queries = LinkedHashMap<ShortcutOwner, ShortcutQueryResult>()
        val pinResults = LinkedHashMap<ShortcutOwner, ShortcutPinResult>()
        val owners = LinkedHashSet<ShortcutOwner>().apply {
            addAll(eligibleOwners)
            addAll(byOwner.keys)
            addAll(reconciledOwners)
        }
        owners.forEach { owner ->
            queries[owner] = platformQuery(owner)
            val ownerPlacements = byOwner[owner].orEmpty()
            pinResults[owner] = platformPin(
                ShortcutPinRequest(owner, ownerPlacements.map { it.target.shortcutId }.toSet()),
            )
        }
        reconciledOwners.clear()
        reconciledOwners.addAll(byOwner.keys)
        pinResults.forEach { (owner, result) ->
            if (result is ShortcutPinResult.Unavailable) reconciledOwners += owner
        }
        val discoveryQueries = eligibleOwners.associateWith { owner -> queries.getValue(owner) }
        mutableState.value = ShortcutSnapshot(
            placements = durable.map { placement -> resolve(placement, queries.getValue(placement.target.owner)) },
            reconciliation = pinResults,
            availableShortcuts = discoveryQueries.values
                .filterIsInstance<ShortcutQueryResult.Available>()
                .flatMap { result -> result.shortcuts.filter(DiscoveredShortcut::enabled) },
            unavailableOwners = discoveryQueries.mapNotNull { (owner, result) ->
                (result as? ShortcutQueryResult.Unavailable)?.let { owner to it.reason }
            }.toMap(),
        )
    }

    private fun resolve(
        placement: ShortcutPlacement,
        query: ShortcutQueryResult,
    ): ResolvedShortcutPlacement {
        if (query is ShortcutQueryResult.Unavailable) {
            return ResolvedShortcutPlacement.Unavailable(placement.id, placement.target, query.reason)
        }
        query as ShortcutQueryResult.Available
        val shortcut = query.shortcuts.singleOrNull { it.target == placement.target }
            ?: return ResolvedShortcutPlacement.Unavailable(
                placement.id,
                placement.target,
                ShortcutUnavailableReason.MISSING,
            )
        if (!shortcut.enabled) {
            return ResolvedShortcutPlacement.Unavailable(
                placement.id,
                placement.target,
                ShortcutUnavailableReason.DISABLED,
            )
        }
        return ResolvedShortcutPlacement.Available(
            id = placement.id,
            target = placement.target,
            label = shortcut.label,
            icon = shortcut.icon,
            dynamic = shortcut.dynamic,
            pinned = shortcut.pinned,
        )
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
