package org.quicklauncher.host.editor

import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.find
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationVector
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleCopyIdentity
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.NewPlacedModule
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.StoreRejection
import org.quicklauncher.host.data.store.StoreRevision

enum class EditorScreen {
    CLOSED,
    MAP,
    DESTINATION,
}

enum class EditorDirection(val deltaX: Long, val deltaY: Long) {
    LEFT(-1L, 0L),
    RIGHT(1L, 0L),
    UP(0L, -1L),
    DOWN(0L, 1L),
}

data class EditorDestination(
    val id: DestinationId,
    val name: String,
    val x: Long,
    val y: Long,
    val start: Boolean,
)

data class EditorModule(
    val id: ModuleInstanceId,
    val contributionId: ContributionId,
    val selectedLayout: Boolean,
    val placedBlock: Boolean,
    val quarantined: Boolean,
)

class LauncherEditorState(
    val screen: EditorScreen,
    val revision: StoreRevision,
    val selectedDestinationId: DestinationId?,
    destinations: Collection<EditorDestination>,
    modules: Collection<EditorModule>,
    val rejection: StoreRejection?,
) {
    val destinations: List<EditorDestination> =
        Collections.unmodifiableList(ArrayList(destinations))
    val modules: List<EditorModule> = Collections.unmodifiableList(ArrayList(modules))

    companion object {
        val Closed = LauncherEditorState(
            EditorScreen.CLOSED,
            StoreRevision.ZERO,
            selectedDestinationId = null,
            destinations = emptyList(),
            modules = emptyList(),
            rejection = null,
        )
    }
}

sealed interface EditorAction {
    data object OpenMap : EditorAction
    data class OpenDestination(val destinationId: DestinationId) : EditorAction
    data object Close : EditorAction
    data class CreateDestination(val name: String, val x: Long, val y: Long) : EditorAction
    data class Rename(val destinationId: DestinationId, val name: String) : EditorAction
    data class Move(
        val destinationIds: Set<DestinationId>,
        val direction: EditorDirection,
    ) : EditorAction
    data class SetStart(val destinationId: DestinationId) : EditorAction
    data class Delete(val destinationId: DestinationId, val confirmed: Boolean) : EditorAction
    data class ResetConfiguration(val instanceId: ModuleInstanceId) : EditorAction
    data class RetainLayout(
        val destinationId: DestinationId,
        val contributionId: ContributionId,
        val configuration: ConfigurationDocument,
    ) : EditorAction
    data class SelectLayout(
        val destinationId: DestinationId,
        val layoutInstanceId: ModuleInstanceId,
    ) : EditorAction
    data class AddBlock(
        val layoutInstanceId: ModuleInstanceId,
        val parentInstanceId: ModuleInstanceId,
        val parentSlotId: StableKey,
        val contributionId: ContributionId,
        val configuration: ConfigurationDocument,
        val index: Int,
        val placement: EncodedPlacementData = EncodedPlacementData.of("{}"),
        val placementSchemaVersion: Int = 1,
    ) : EditorAction
    data class MoveBlock(
        val instanceId: ModuleInstanceId,
        val parentInstanceId: ModuleInstanceId,
        val parentSlotId: StableKey,
        val index: Int,
        val placement: EncodedPlacementData = EncodedPlacementData.of("{}"),
        val placementSchemaVersion: Int = 1,
    ) : EditorAction
    data class CopyBlock(
        val instanceId: ModuleInstanceId,
        val parentInstanceId: ModuleInstanceId,
        val parentSlotId: StableKey,
        val index: Int,
        val placement: EncodedPlacementData = EncodedPlacementData.of("{}"),
        val placementSchemaVersion: Int = 1,
    ) : EditorAction
    data class ReplaceConfiguration(
        val instanceId: ModuleInstanceId,
        val document: ConfigurationDocument,
    ) : EditorAction
    data class RemovePlacedModule(
        val instanceId: ModuleInstanceId,
        val confirmed: Boolean,
    ) : EditorAction
    data class RemoveQuarantinedModule(
        val instanceId: ModuleInstanceId,
        val confirmed: Boolean,
    ) : EditorAction
}

sealed interface EditorResult {
    data object Applied : EditorResult
    data class Rejected(val reason: StoreRejection) : EditorResult
}

interface LauncherEditor : AutoCloseable {
    val state: StateFlow<LauncherEditorState>
    suspend fun start()
    suspend fun dispatch(action: EditorAction): EditorResult
}

fun interface EditorConfigurationDefaults {
    fun defaultFor(contributionId: ContributionId): ConfigurationDocument?
}

fun registryEditorConfigurationDefaults(registry: ContributionRegistry): EditorConfigurationDefaults =
    EditorConfigurationDefaults { contributionId ->
        registry.find(contributionId)?.codec?.let(::defaultDocument)
    }

@Suppress("UNCHECKED_CAST")
private fun defaultDocument(codec: ConfigurationCodec<*>): ConfigurationDocument {
    val typed = codec as ConfigurationCodec<Any>
    return ConfigurationDocument(typed.configType, typed.currentSchemaVersion, typed.encode(typed.default))
}

private val UnavailableConfigurationDefaults = EditorConfigurationDefaults { null }

fun interface EditorDestinationFactory {
    fun create(destinationId: DestinationId, name: String, x: Long, y: Long): DestinationInstall?
}

fun interface EditorIdentitySource {
    fun nextLocalId(): String
}

private val RandomEditorIdentitySource = EditorIdentitySource {
    UUID.randomUUID().toString().lowercase()
}

private val UnavailableDestinationFactory = EditorDestinationFactory { _, _, _, _ -> null }

class DefaultLauncherEditor(
    private val store: LauncherStore,
    parentScope: CoroutineScope,
    private val configurationDefaults: EditorConfigurationDefaults = UnavailableConfigurationDefaults,
    private val destinationFactory: EditorDestinationFactory = UnavailableDestinationFactory,
    private val identities: EditorIdentitySource = RandomEditorIdentitySource,
) : LauncherEditor {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    @Suppress("unused")
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow(LauncherEditorState.Closed)

    override val state: StateFlow<LauncherEditorState> = mutableState.asStateFlow()

    override suspend fun start() {
        checkOpen()
        mutex.withLock { publish(EditorScreen.CLOSED, selected = null, rejection = null) }
    }

    override suspend fun dispatch(action: EditorAction): EditorResult {
        checkOpen()
        return mutex.withLock {
            when (action) {
                EditorAction.OpenMap -> {
                    publish(EditorScreen.MAP, selected = null, rejection = null)
                    EditorResult.Applied
                }
                is EditorAction.OpenDestination -> {
                    val snapshot = store.read()
                    if (snapshot.destinations.none { it.id == action.destinationId }) {
                        reject(notFound("Destination '${action.destinationId}' is unavailable"))
                    } else {
                        publish(EditorScreen.DESTINATION, action.destinationId, rejection = null)
                        EditorResult.Applied
                    }
                }
                EditorAction.Close -> {
                    publish(EditorScreen.CLOSED, selected = null, rejection = null)
                    EditorResult.Applied
                }
                is EditorAction.CreateDestination -> createDestination(action)
                is EditorAction.Rename -> commit(
                    LauncherEdit.RenameDestination(action.destinationId, action.name),
                )
                is EditorAction.Move -> commit(
                    LauncherEdit.MoveDestinations(
                        action.destinationIds,
                        DestinationVector(action.direction.deltaX, action.direction.deltaY),
                    ),
                )
                is EditorAction.SetStart -> commit(LauncherEdit.SetStartDestination(action.destinationId))
                is EditorAction.Delete -> commit(
                    LauncherEdit.DeleteDestination(action.destinationId, action.confirmed),
                )
                is EditorAction.RetainLayout -> retainLayout(action)
                is EditorAction.SelectLayout -> commit(
                    LauncherEdit.SelectLayout(action.destinationId, action.layoutInstanceId),
                )
                is EditorAction.AddBlock -> addBlock(action)
                is EditorAction.MoveBlock -> moveBlock(action)
                is EditorAction.CopyBlock -> copyBlock(action)
                is EditorAction.ResetConfiguration -> resetConfiguration(action.instanceId)
                is EditorAction.ReplaceConfiguration -> replaceConfiguration(action.instanceId, action.document)
                is EditorAction.RemovePlacedModule -> removePlacedModule(action.instanceId, action.confirmed)
                is EditorAction.RemoveQuarantinedModule -> removeQuarantined(action)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
    }

    private suspend fun commit(edit: LauncherEdit): EditorResult {
        val before = store.read()
        return try {
            when (val result = store.commit(LauncherTransaction(before.revision, listOf(edit)))) {
                is CommitResult.Committed -> {
                    publishFrom(
                        result.state,
                        mutableState.value.screen,
                        mutableState.value.selectedDestinationId?.takeIf { selected ->
                            result.state.destinations.any { it.id == selected }
                        },
                        rejection = null,
                    )
                    EditorResult.Applied
                }
                is CommitResult.Rejected -> reject(result.reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun createDestination(action: EditorAction.CreateDestination): EditorResult {
        if (action.name.isBlank()) return reject(invalid("Destination name must not be blank"))
        val destinationId = DestinationId.parse("org.quicklauncher.destination/${identities.nextLocalId()}")
        val install = destinationFactory.create(destinationId, action.name, action.x, action.y)
            ?: return reject(invalid("Destination creation is unavailable"))
        val snapshot = store.read()
        val edit = if (snapshot.destinations.isEmpty()) {
            LauncherEdit.Bootstrap(install)
        } else {
            LauncherEdit.InstallDestination(install)
        }
        return commit(edit)
    }

    private suspend fun retainLayout(action: EditorAction.RetainLayout): EditorResult {
        val instanceId = moduleId()
        val documentId = configurationId()
        return commit(
            LauncherEdit.RetainLayout(
                DestinationLayoutRecord(
                    action.destinationId,
                    action.contributionId,
                    instanceId,
                    selected = false,
                ),
                ModuleInstanceRecord(instanceId, action.contributionId, documentId),
                StoredConfigurationDocument(documentId, action.configuration),
            ),
        )
    }

    private suspend fun addBlock(action: EditorAction.AddBlock): EditorResult {
        val instanceId = moduleId()
        val documentId = configurationId()
        val placementId = placementId()
        return commit(
            LauncherEdit.CommitDrop(
                NewPlacedModule(
                    ModuleInstanceRecord(instanceId, action.contributionId, documentId),
                    StoredConfigurationDocument(documentId, action.configuration),
                    PlacementRecord(
                        placementId,
                        action.layoutInstanceId,
                        action.parentInstanceId,
                        action.parentSlotId,
                        instanceId,
                        action.index,
                        action.placement,
                        action.placementSchemaVersion,
                    ),
                ),
            ),
        )
    }

    private suspend fun moveBlock(action: EditorAction.MoveBlock): EditorResult {
        val placement = store.read().placements.singleOrNull {
            it.childInstanceId == action.instanceId
        } ?: return reject(notFound("Placed module '${action.instanceId}' is unavailable"))
        return commit(
            LauncherEdit.MoveDrop(
                placement.id,
                action.parentInstanceId,
                action.parentSlotId,
                action.index,
                action.placement,
                action.placementSchemaVersion,
            ),
        )
    }

    private suspend fun copyBlock(action: EditorAction.CopyBlock): EditorResult {
        val snapshot = store.read()
        val root = snapshot.placements.singleOrNull { it.childInstanceId == action.instanceId }
            ?: return reject(notFound("Placed module '${action.instanceId}' is unavailable"))
        val descendants = linkedSetOf(action.instanceId)
        val pending = ArrayDeque<ModuleInstanceId>().apply { add(action.instanceId) }
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            snapshot.placements.filter { it.parentInstanceId == parent }.forEach { placement ->
                if (descendants.add(placement.childInstanceId)) pending.addLast(placement.childInstanceId)
            }
        }
        val copyIdentities = descendants.map { sourceId ->
            val sourcePlacement = snapshot.placements.single { it.childInstanceId == sourceId }
            ModuleCopyIdentity(
                sourceId,
                moduleId(),
                configurationId(),
                placementId(),
                if (sourcePlacement.id == root.id) action.placement else sourcePlacement.data,
                if (sourcePlacement.id == root.id) {
                    action.placementSchemaVersion
                } else {
                    sourcePlacement.placementSchemaVersion
                },
            )
        }
        return commit(
            LauncherEdit.CopySubtree(
                root.id,
                action.parentInstanceId,
                action.parentSlotId,
                action.index,
                copyIdentities,
            ),
        )
    }

    private suspend fun resetConfiguration(instanceId: ModuleInstanceId): EditorResult {
        val snapshot = store.read()
        val instance = snapshot.moduleInstances.singleOrNull { it.id == instanceId }
            ?: return reject(notFound("Module instance '$instanceId' is unavailable"))
        val document = configurationDefaults.defaultFor(instance.contributionId)
            ?: return reject(
                StoreRejection(
                    org.quicklauncher.host.data.store.StoreRejectionCode.CONFIGURATION_INVALID,
                    "Contribution '${instance.contributionId}' has no available configuration default",
                ),
            )
        return commit(LauncherEdit.ReplaceConfiguration(instance.configurationDocumentId, document))
    }

    private suspend fun replaceConfiguration(
        instanceId: ModuleInstanceId,
        document: ConfigurationDocument,
    ): EditorResult {
        val snapshot = store.read()
        val instance = snapshot.moduleInstances.singleOrNull { it.id == instanceId }
            ?: return reject(notFound("Module instance '$instanceId' is unavailable"))
        return commit(LauncherEdit.ReplaceConfiguration(instance.configurationDocumentId, document))
    }

    private suspend fun removePlacedModule(
        instanceId: ModuleInstanceId,
        confirmed: Boolean,
    ): EditorResult {
        val snapshot = store.read()
        val placement = snapshot.placements.singleOrNull { it.childInstanceId == instanceId }
            ?: return reject(
                StoreRejection(
                    org.quicklauncher.host.data.store.StoreRejectionCode.INVALID_REFERENCE,
                    "Only a placed block subtree can be removed; '$instanceId' is not placed",
                ),
            )
        return commit(LauncherEdit.RemovePlacedSubtree(placement.id, confirmed))
    }

    private suspend fun removeQuarantined(action: EditorAction.RemoveQuarantinedModule): EditorResult {
        val snapshot = store.read()
        val instance = snapshot.moduleInstances.singleOrNull { it.id == action.instanceId }
            ?: return reject(notFound("Module instance '${action.instanceId}' is unavailable"))
        if (instance.status !is org.quicklauncher.host.data.store.ModuleInstanceStatus.Quarantined) {
            return reject(invalid("Only quarantined modules can be removed through recovery"))
        }
        val placement = snapshot.placements.singleOrNull { it.childInstanceId == action.instanceId }
        if (placement != null) {
            return commit(LauncherEdit.RemovePlacedSubtree(placement.id, action.confirmed))
        }
        val retained = snapshot.destinationLayouts.singleOrNull { it.layoutInstanceId == action.instanceId }
            ?: return reject(invalid("Quarantined module '${action.instanceId}' is not removable"))
        return commit(LauncherEdit.RemoveRetainedLayout(retained.layoutInstanceId, action.confirmed))
    }

    private suspend fun publish(
        screen: EditorScreen,
        selected: DestinationId?,
        rejection: StoreRejection?,
    ) = publishFrom(store.read(), screen, selected, rejection)

    private fun publishFrom(
        snapshot: org.quicklauncher.host.data.store.LauncherSnapshot,
        screen: EditorScreen,
        selected: DestinationId?,
        rejection: StoreRejection?,
    ) {
        mutableState.value = LauncherEditorState(
            screen = screen,
            revision = snapshot.revision,
            selectedDestinationId = selected,
            destinations = snapshot.destinations.map { destination ->
                EditorDestination(
                    destination.id,
                    destination.name,
                    destination.coordinate.x,
                    destination.coordinate.y,
                    destination.id == snapshot.startDestinationId,
                )
            },
            modules = modulesFor(snapshot, selected),
            rejection = rejection,
        )
    }

    private fun reject(reason: StoreRejection): EditorResult.Rejected {
        val current = mutableState.value
        mutableState.value = LauncherEditorState(
            current.screen,
            current.revision,
            current.selectedDestinationId,
            current.destinations,
            current.modules,
            reason,
        )
        return EditorResult.Rejected(reason)
    }

    private fun notFound(message: String) = StoreRejection(
        org.quicklauncher.host.data.store.StoreRejectionCode.NOT_FOUND,
        message,
    )

    private fun invalid(message: String) = StoreRejection(
        org.quicklauncher.host.data.store.StoreRejectionCode.INVALID_REFERENCE,
        message,
    )

    private fun moduleId() = ModuleInstanceId.parse(
        "org.quicklauncher.instance/${identities.nextLocalId()}",
    )

    private fun configurationId() = ConfigurationDocumentId.parse(
        "org.quicklauncher.configuration/${identities.nextLocalId()}",
    )

    private fun placementId() = PlacementId.parse(
        "org.quicklauncher.placement/${identities.nextLocalId()}",
    )

    private fun checkOpen() {
        check(!closed.get()) { "Launcher editor is closed" }
    }

    private fun modulesFor(
        snapshot: org.quicklauncher.host.data.store.LauncherSnapshot,
        destinationId: DestinationId?,
    ): List<EditorModule> {
        if (destinationId == null) return emptyList()
        val roots = snapshot.destinationLayouts.filter { it.destinationId == destinationId }
        val reachable = LinkedHashSet<ModuleInstanceId>()
        val queue = ArrayDeque(roots.map { it.layoutInstanceId })
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            if (!reachable.add(parent)) continue
            snapshot.placements.filter { it.parentInstanceId == parent }
                .forEach { queue.addLast(it.childInstanceId) }
        }
        val selectedRoot = roots.singleOrNull { it.selected }?.layoutInstanceId
        val placed = snapshot.placements.mapTo(HashSet()) { it.childInstanceId }
        return snapshot.moduleInstances
            .filter { it.id in reachable }
            .sortedBy { it.id.value }
            .map { instance ->
                EditorModule(
                    id = instance.id,
                    contributionId = instance.contributionId,
                    selectedLayout = instance.id == selectedRoot,
                    placedBlock = instance.id in placed,
                    quarantined = instance.status is org.quicklauncher.host.data.store.ModuleInstanceStatus.Quarantined,
                )
            }
    }
}
