package org.quicklauncher.host.runtime.composition

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.quicklauncher.contracts.contribution.ConfigurationLoadResult
import org.quicklauncher.contracts.contribution.ConfigurationPipeline
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.ContributionSession
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.RegisteredLayout
import org.quicklauncher.contracts.contribution.SlotDescriptor
import org.quicklauncher.contracts.contribution.find
import org.quicklauncher.contracts.contribution.compatibilityProblem
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.BlockRenderState
import org.quicklauncher.contracts.ui.CompositionRole
import org.quicklauncher.contracts.ui.CompositionState
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.LayoutRenderState
import org.quicklauncher.contracts.ui.PlacedChild
import org.quicklauncher.contracts.ui.EncodedPlacement
import org.quicklauncher.contracts.ui.PlacementRenderData
import org.quicklauncher.contracts.ui.PlacementMode
import org.quicklauncher.contracts.ui.PlacementState
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.PlacementRecord

class DefaultCompositionEngine(
    private val registry: ContributionRegistry,
    parentScope: CoroutineScope,
    private val restoreRequestSource: CompositionRestoreRequestSource? = null,
) : CompositionEngine {
    private val parentContext = parentScope.coroutineContext
    private val parentJob = parentContext[Job]
    private val managed = linkedMapOf<ModuleInstanceId, ManagedInstance>()
    private var selectedLayoutId: ModuleInstanceId? = null
    private var selectedLayoutReady = false
    private var closed = false

    @Synchronized
    override fun prepare(request: CompositionRequest): PreparedComposition {
        check(!closed) { "Composition engine is closed" }
        val managedBefore = managed.toMap()
        try {
            return prepareTransaction(request)
        } catch (failure: Throwable) {
            managed.entries
                .filter { (instanceId, value) -> managedBefore[instanceId] !== value }
                .map { it.key }
                .forEach(::dispose)
            throw failure
        }
    }

    private fun prepareTransaction(request: CompositionRequest): PreparedComposition {
        val current = request.snapshot.destinations.firstOrNull {
            it.id == request.currentDestinationId
        }
        if (current == null) {
            disposeAll()
            selectedLayoutId = null
            selectedLayoutReady = false
            return PreparedComposition(
                listOf(missingDestination(request.currentDestinationId, request.environment)),
            )
        }

        val liveDestinations = request.snapshot.destinations
            .filter { it.id == current.id || it.coordinate.isCardinalNeighborOf(current.coordinate) }
            .sortedWith(compareBy({ it.id != current.id }, { it.id.value }))
        val desiredInstances = linkedSetOf<ModuleInstanceId>()
        val prepared = liveDestinations.map { destination ->
            val role = if (destination.id == current.id) {
                CompositionRole.CURRENT
            } else {
                CompositionRole.NEIGHBOR_PREVIEW
            }
            prepareDestination(
                request.snapshot,
                destination.id,
                role,
                request.environment,
                desiredInstances,
                request.currentInteractive,
            )
        }
        disposeExcept(desiredInstances)
        val selected = prepared.single { it.destinationId == current.id }
        selectedLayoutId = selected.layoutInstanceId
        selectedLayoutReady = selected.layoutInstanceId != null &&
            selected.issues.none { it.instanceId == selected.layoutInstanceId }
        return PreparedComposition(prepared)
    }

    override suspend fun restore(instanceId: ModuleInstanceId) {
        val needsPreparation = synchronized(this) {
            check(!closed) { "Composition engine is closed" }
            instanceId != selectedLayoutId || !selectedLayoutReady || managed[instanceId] == null
        }
        if (needsPreparation) {
            val request = checkNotNull(restoreRequestSource) {
                "Selected layout '$instanceId' has not been prepared and no restore request source is installed"
            }.request(instanceId)
            val selected = request.snapshot.destinationLayouts.singleOrNull {
                it.destinationId == request.currentDestinationId && it.selected
            }
            check(selected?.layoutInstanceId == instanceId) {
                "Restore request did not select layout '$instanceId' at the current destination"
            }
            prepare(request)
        }
        synchronized(this) {
            check(!closed) { "Composition engine is closed" }
            check(instanceId == selectedLayoutId && selectedLayoutReady && managed[instanceId] != null) {
                "Selected layout '$instanceId' could not be prepared"
            }
        }
    }

    @Synchronized
    override fun release() {
        if (closed) return
        selectedLayoutId = null
        selectedLayoutReady = false
        disposeAll()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        selectedLayoutId = null
        selectedLayoutReady = false
        disposeAll()
    }

    private fun prepareDestination(
        snapshot: LauncherSnapshot,
        destinationId: DestinationId,
        role: CompositionRole,
        environment: CompositionEnvironment,
        desired: MutableSet<ModuleInstanceId>,
        currentInteractive: Boolean,
    ): PreparedDestination {
        val composition = CompositionState(
            role,
            isInteractive = role == CompositionRole.CURRENT && currentInteractive,
        )
        val layout = snapshot.destinationLayouts.singleOrNull {
            it.destinationId == destinationId && it.selected
        }
        if (layout == null) {
            val issue = issue(CompositionIssueKind.MISSING_LAYOUT, null, "Destination '$destinationId' has no selected layout")
            return placeholderDestination(destinationId, null, composition, issue, environment)
        }
        val issues = mutableListOf<CompositionIssue>()
        val root = prepareLayout(
            snapshot = snapshot,
            instanceId = layout.layoutInstanceId,
            composition = composition,
            environment = environment,
            desired = desired,
            path = emptySet(),
            issues = issues,
            expectedContributionId = layout.layoutContributionId,
        )
        return PreparedDestination(
            destinationId,
            layout.layoutInstanceId,
            composition,
            issues,
            DestinationRenderer { modifier -> root.Render(modifier) },
        )
    }

    private fun prepareLayout(
        snapshot: LauncherSnapshot,
        instanceId: ModuleInstanceId,
        composition: CompositionState,
        environment: CompositionEnvironment,
        desired: MutableSet<ModuleInstanceId>,
        path: Set<ModuleInstanceId>,
        issues: MutableList<CompositionIssue>,
        expectedContributionId: ContributionId,
    ): RenderNode {
        val recordedContributionId = snapshot.moduleInstances
            .firstOrNull { it.id == instanceId }
            ?.contributionId
        if (recordedContributionId != null && recordedContributionId != expectedContributionId) {
            val mismatch = issue(
                CompositionIssueKind.INCOMPATIBLE_PLACEMENT,
                instanceId,
                "Selected layout record expects '$expectedContributionId' but instance '$instanceId' resolves to '$recordedContributionId'",
            )
            issues += mismatch
            return placeholder(instanceId, mismatch, environment)
        }
        val resolved = resolveInstance(snapshot, instanceId, expectedLayout = true, desired, issues)
            ?: return placeholder(instanceId, issues.last(), environment)
        val registration = resolved.registration as RegisteredLayout<*>
        val descriptor = registration.descriptor
        unknownSlotIssue(snapshot, instanceId, descriptor.slots)?.let { mismatch ->
            desired -= instanceId
            dispose(instanceId)
            issues += mismatch
            return placeholder(instanceId, mismatch, environment)
        }
        val children = prepareSlots(
            snapshot,
            instanceId,
            descriptor.slots,
            composition,
            environment,
            desired,
            path + instanceId,
            issues,
        )
        val slots = children.map { it.state }
        val state = LayoutRenderState(
            environment.theme,
            environment.window,
            environment.backgroundContrast,
            RenderStatus.Ready,
            environment.editorMode,
            composition,
            PlacementState.root(),
            slots,
        )
        val actions = actionsFor(composition, environment.layoutActions)
        return LayoutNode(
            instanceId,
            resolved.managed.session as org.quicklauncher.contracts.contribution.LayoutSession,
            state,
            TreeSlotRenderer(children, environment),
            actions,
            environment,
        )
    }

    private fun prepareBlock(
        snapshot: LauncherSnapshot,
        placement: PlacementRecord,
        slot: SlotDescriptor,
        composition: CompositionState,
        environment: CompositionEnvironment,
        desired: MutableSet<ModuleInstanceId>,
        path: Set<ModuleInstanceId>,
        issues: MutableList<CompositionIssue>,
    ): RenderNode {
        val instanceId = placement.childInstanceId
        if (instanceId in path) {
            val issue = issue(CompositionIssueKind.PLACEMENT_CYCLE, instanceId, "Placement cycle reaches '$instanceId'")
            issues += issue
            return placeholder(instanceId, issue, environment)
        }
        val candidateRecord = snapshot.moduleInstances.firstOrNull { it.id == instanceId }
        val candidateRegistration = candidateRecord?.let { registry.find(it.contributionId) }
        val candidateDescriptor = (candidateRegistration as? RegisteredBlock<*>)?.descriptor
        if (candidateRecord != null && candidateDescriptor != null &&
            !compatible(slot, candidateDescriptor, placement, snapshot)
        ) {
            desired -= instanceId
            dispose(instanceId)
            val issue = issue(
                CompositionIssueKind.INCOMPATIBLE_PLACEMENT,
                instanceId,
                "Block '${candidateRecord.contributionId}' is incompatible with slot '${slot.id}'",
            )
            issues += issue
            return placeholder(instanceId, issue, environment)
        }
        val resolved = resolveInstance(snapshot, instanceId, expectedLayout = false, desired, issues)
            ?: return placeholder(instanceId, issues.last(), environment)
        val registration = resolved.registration as RegisteredBlock<*>
        val descriptor = registration.descriptor
        unknownSlotIssue(snapshot, instanceId, descriptor.childSlots)?.let { mismatch ->
            desired -= instanceId
            dispose(instanceId)
            issues += mismatch
            return placeholder(instanceId, mismatch, environment)
        }
        val children = prepareSlots(
            snapshot,
            instanceId,
            descriptor.childSlots,
            composition,
            environment,
            desired,
            path + instanceId,
            issues,
        )
        val preparedContent = try {
            environment.contentSource.contentFor(instanceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            desired -= instanceId
            dispose(instanceId)
            val issue = issue(
                CompositionIssueKind.PREPARED_CONTENT_FAILED,
                instanceId,
                "Preparing host content for '$instanceId' failed: ${failure.javaClass.simpleName}",
            )
            issues += issue
            return placeholder(instanceId, issue, environment)
        }
        val state = BlockRenderState(
            environment.theme,
            environment.window,
            environment.backgroundContrast,
            preparedContent.status,
            environment.editorMode,
            composition,
            PlacementState(slot.id, placement.index, PlacementMode.PLACED, true, true),
            preparedContent.content,
            children.map { it.state },
        )
        return BlockNode(
            instanceId,
            resolved.managed.session as org.quicklauncher.contracts.contribution.BlockSession,
            state,
            TreeSlotRenderer(children, environment),
            actionsFor(composition, environment.blockActions),
            environment,
        )
    }

    private fun prepareSlots(
        snapshot: LauncherSnapshot,
        parentId: ModuleInstanceId,
        descriptors: List<SlotDescriptor>,
        composition: CompositionState,
        environment: CompositionEnvironment,
        desired: MutableSet<ModuleInstanceId>,
        path: Set<ModuleInstanceId>,
        issues: MutableList<CompositionIssue>,
    ): List<PreparedSlot> = descriptors.map { slot ->
        val placements = snapshot.placements
            .filter { it.parentInstanceId == parentId && it.parentSlotId == slot.id }
            .sortedWith(compareBy(PlacementRecord::index, { it.id.value }))
        val nodes = placements.map { placement ->
            prepareBlock(snapshot, placement, slot, composition, environment, desired, path, issues)
        }
        val state = SlotRenderState(
            slot.id,
            slot.type,
            if (nodes.isEmpty()) RenderStatus.Empty else RenderStatus.Ready,
            placements.map { placement ->
                val child = snapshot.moduleInstances.firstOrNull { it.id == placement.childInstanceId }
                PlacedChild(
                    placement.childInstanceId,
                    child?.contributionId ?: MissingContributionId,
                    placement.index,
                    PlacementRenderData(
                        placement.placementSchemaVersion,
                        EncodedPlacement.of(placement.data.value),
                    ),
                )
            },
        )
        PreparedSlot(state, nodes)
    }

    private fun unknownSlotIssue(
        snapshot: LauncherSnapshot,
        parentId: ModuleInstanceId,
        descriptors: List<SlotDescriptor>,
    ): CompositionIssue? {
        val declaredSlots = descriptors.mapTo(hashSetOf()) { it.id }
        val invalid = snapshot.placements
            .filter { it.parentInstanceId == parentId && it.parentSlotId !in declaredSlots }
            .minByOrNull { it.id.value }
            ?: return null
        return issue(
            CompositionIssueKind.INCOMPATIBLE_PLACEMENT,
            invalid.childInstanceId,
            "Placement '${invalid.id}' targets undeclared slot '${invalid.parentSlotId}' on '$parentId'",
        )
    }

    private fun resolveInstance(
        snapshot: LauncherSnapshot,
        instanceId: ModuleInstanceId,
        expectedLayout: Boolean,
        desired: MutableSet<ModuleInstanceId>,
        issues: MutableList<CompositionIssue>,
    ): ResolvedInstance? {
        val record = snapshot.moduleInstances.firstOrNull { it.id == instanceId }
            ?: return fail(issues, CompositionIssueKind.MISSING_INSTANCE, instanceId, "Module instance '$instanceId' is missing")
        if (record.status is ModuleInstanceStatus.Quarantined) {
            return fail(issues, CompositionIssueKind.QUARANTINED, instanceId, "Module instance '$instanceId' is quarantined")
        }
        val registration = registry.find(record.contributionId)
            ?: return fail(
                issues,
                CompositionIssueKind.MISSING_CONTRIBUTION,
                instanceId,
                "Contribution '${record.contributionId}' is unavailable",
            )
        val categoryMatches = if (expectedLayout) registration is RegisteredLayout<*> else registration is RegisteredBlock<*>
        if (!categoryMatches) {
            return fail(
                issues,
                CompositionIssueKind.WRONG_CONTRIBUTION_CATEGORY,
                instanceId,
                "Contribution '${record.contributionId}' has the wrong category",
            )
        }
        val stored = snapshot.configurationDocuments.firstOrNull { it.id == record.configurationDocumentId }
            ?: return fail(
                issues,
                CompositionIssueKind.MISSING_CONFIGURATION,
                instanceId,
                "Configuration '${record.configurationDocumentId}' is missing",
            )
        val loaded = load(registration, stored.document)
        if (loaded is ConfigurationLoadResult.Failed) {
            return fail(
                issues,
                CompositionIssueKind.CONFIGURATION_FAILED,
                instanceId,
                "Configuration failed: ${loaded.error.code}",
            )
        }
        loaded as ConfigurationLoadResult.Loaded<*>
        val key = ManagedKey(record.contributionId, loaded.document)
        val existing = managed[instanceId]
        if (existing != null && existing.key != key) dispose(instanceId)
        val active = managed[instanceId] ?: open(registration, record, loaded.value, key, issues) ?: return null
        desired += instanceId
        return ResolvedInstance(registration, active)
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(
        registration: RegisteredContribution<*>,
        document: org.quicklauncher.contracts.contribution.ConfigurationDocument,
    ): ConfigurationLoadResult<*> = ConfigurationPipeline.load(
        document,
        registration.codec as org.quicklauncher.contracts.contribution.ConfigurationCodec<Any>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun open(
        registration: RegisteredContribution<*>,
        record: ModuleInstanceRecord,
        configuration: Any?,
        key: ManagedKey,
        issues: MutableList<CompositionIssue>,
    ): ManagedInstance? {
        checkNotNull(configuration)
        val job = SupervisorJob(parentJob)
        val scope = CoroutineScope(parentContext + job)
        val context = ContributionContext(record.id, configuration, JobCancellationSignal(job), scope)
        val session = try {
            when (registration) {
                is RegisteredLayout<*> ->
                    (registration.target as org.quicklauncher.contracts.contribution.LayoutContribution<Any>).open(context)
                is RegisteredBlock<*> ->
                    (registration.target as org.quicklauncher.contracts.contribution.BlockContribution<Any>).open(context)
                else -> error("Unsupported visual registration")
            }
        } catch (cancelled: CancellationException) {
            job.cancel()
            throw cancelled
        } catch (failure: RuntimeException) {
            job.cancel()
            issues += issue(
                CompositionIssueKind.SESSION_FAILED,
                record.id,
                "Opening module instance '${record.id}' failed: ${failure.javaClass.simpleName}",
            )
            return null
        }
        return ManagedInstance(key, session, job).also { managed[record.id] = it }
    }

    private fun compatible(
        slot: SlotDescriptor,
        block: org.quicklauncher.contracts.contribution.BlockDescriptor,
        placement: PlacementRecord,
        snapshot: LauncherSnapshot,
    ): Boolean {
        val child = snapshot.moduleInstances.firstOrNull { it.id == placement.childInstanceId } ?: return false
        val childCount = snapshot.placements.count {
            it.parentInstanceId == placement.parentInstanceId && it.parentSlotId == placement.parentSlotId
        }
        return slot.compatibilityProblem(block, childCount) == null
    }

    private fun <A> actionsFor(composition: CompositionState, delegate: ActionSink<A>): ActionSink<A> =
        if (composition.isInteractive) delegate else ActionSink {
            ActionDispatchResult.Rejected(
                StableKey.parse("neighbor-not-interactive"),
                "Neighbor preview actions are disabled",
            )
        }

    private fun missingDestination(
        destinationId: DestinationId,
        environment: CompositionEnvironment,
    ): PreparedDestination {
        val issue = issue(
            CompositionIssueKind.MISSING_DESTINATION,
            null,
            "Current destination '$destinationId' is missing",
        )
        return placeholderDestination(
            destinationId,
            null,
            CompositionState(CompositionRole.CURRENT, true),
            issue,
            environment,
        )
    }

    private fun placeholderDestination(
        destinationId: DestinationId,
        layoutInstanceId: ModuleInstanceId?,
        composition: CompositionState,
        issue: CompositionIssue,
        environment: CompositionEnvironment,
    ) = PreparedDestination(
        destinationId,
        layoutInstanceId,
        composition,
        listOf(issue),
        DestinationRenderer { modifier -> environment.placeholderRenderer.Render(issue, modifier) },
    )

    private fun placeholder(
        instanceId: ModuleInstanceId,
        issue: CompositionIssue,
        environment: CompositionEnvironment,
    ): RenderNode = PlaceholderNode(instanceId, issue, environment)

    private fun fail(
        issues: MutableList<CompositionIssue>,
        kind: CompositionIssueKind,
        instanceId: ModuleInstanceId,
        message: String,
    ): ResolvedInstance? {
        issues += issue(kind, instanceId, message)
        return null
    }

    private fun issue(kind: CompositionIssueKind, instanceId: ModuleInstanceId?, message: String) =
        CompositionIssue(kind, instanceId, message)

    private fun disposeExcept(desired: Set<ModuleInstanceId>) {
        managed.keys.filterNot { it in desired }.toList().forEach(::dispose)
    }

    private fun disposeAll() {
        managed.keys.toList().forEach(::dispose)
    }

    private fun dispose(instanceId: ModuleInstanceId) {
        val value = managed.remove(instanceId) ?: return
        try {
            value.session.close()
        } catch (_: RuntimeException) {
            // One broken contribution must not prevent the remaining instance scopes from closing.
        } finally {
            value.job.cancel()
        }
    }

    private data class ManagedKey(
        val contributionId: ContributionId,
        val document: org.quicklauncher.contracts.contribution.ConfigurationDocument,
    )

    private data class ManagedInstance(
        val key: ManagedKey,
        val session: ContributionSession,
        val job: Job,
    )

    private data class ResolvedInstance(
        val registration: RegisteredContribution<*>,
        val managed: ManagedInstance,
    )

    private companion object {
        val MissingContributionId = ContributionId.parse("org.quicklauncher.host/missing-contribution")
    }
}

private class JobCancellationSignal(private val job: Job) : org.quicklauncher.contracts.contribution.CancellationSignal {
    override val isCancelled: Boolean
        get() = !job.isActive
}

private data class PreparedSlot(
    val state: SlotRenderState,
    val children: List<RenderNode>,
)

private sealed interface RenderNode {
    val instanceId: ModuleInstanceId

    @Composable
    fun Render(modifier: Modifier)
}

private class LayoutNode(
    override val instanceId: ModuleInstanceId,
    private val session: org.quicklauncher.contracts.contribution.LayoutSession,
    private val state: LayoutRenderState,
    private val slots: SlotRenderer,
    private val actions: ActionSink<LayoutAction>,
    private val environment: CompositionEnvironment,
) : RenderNode {
    @Composable
    override fun Render(modifier: Modifier) {
        if (session.isClosed) {
            environment.placeholderRenderer.Render(
                CompositionIssue(
                    CompositionIssueKind.SESSION_FAILED,
                    instanceId,
                    "Module instance '$instanceId' is no longer in composition",
                ),
                modifier,
            )
            return
        }
        IsolatedRenderer(
            modifier = modifier,
            issueFor = { failure ->
                CompositionIssue(
                    CompositionIssueKind.RENDER_FAILED,
                    instanceId,
                    "Rendering layout '$instanceId' failed: ${failure.javaClass.simpleName}",
                )
            },
            environment = environment,
        ) {
            Box { session.Render(LayoutRenderInput(instanceId, state, slots, actions)) }
        }
    }
}

private class BlockNode(
    override val instanceId: ModuleInstanceId,
    private val session: org.quicklauncher.contracts.contribution.BlockSession,
    private val state: BlockRenderState,
    private val slots: SlotRenderer,
    private val actions: ActionSink<BlockAction>,
    private val environment: CompositionEnvironment,
) : RenderNode {
    @Composable
    override fun Render(modifier: Modifier) {
        if (session.isClosed) {
            environment.placeholderRenderer.Render(
                CompositionIssue(
                    CompositionIssueKind.SESSION_FAILED,
                    instanceId,
                    "Module instance '$instanceId' is no longer in composition",
                ),
                modifier,
            )
            return
        }
        IsolatedRenderer(
            modifier = modifier,
            issueFor = { failure ->
                CompositionIssue(
                    CompositionIssueKind.RENDER_FAILED,
                    instanceId,
                    "Rendering block '$instanceId' failed: ${failure.javaClass.simpleName}",
                )
            },
            environment = environment,
        ) {
            Box {
                session.Render(
                    BlockRenderInput(instanceId, state, slots, environment.contentRenderer, actions),
                )
            }
        }
    }
}

private enum class IsolatedRendererSlot {
    CONTRIBUTION,
    PLACEHOLDER,
}

/**
 * Runs contribution composition in a child slot table so an ordinary renderer failure cannot
 * corrupt the host composition. Cancellation remains structural and always escapes.
 */
@Composable
private fun IsolatedRenderer(
    modifier: Modifier,
    issueFor: (RuntimeException) -> CompositionIssue,
    environment: CompositionEnvironment,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val placeables = try {
            subcompose(IsolatedRendererSlot.CONTRIBUTION, content).map { it.measure(constraints) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            val issue = issueFor(failure)
            environment.rendererFailures.report(issue, failure)
            subcompose(IsolatedRendererSlot.PLACEHOLDER) {
                environment.placeholderRenderer.Render(issue, Modifier)
            }.map { it.measure(constraints) }
        }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}

private class PlaceholderNode(
    override val instanceId: ModuleInstanceId,
    private val issue: CompositionIssue,
    private val environment: CompositionEnvironment,
) : RenderNode {
    @Composable
    override fun Render(modifier: Modifier) {
        environment.placeholderRenderer.Render(issue, modifier)
    }
}

private class TreeSlotRenderer(
    slots: List<PreparedSlot>,
    private val environment: CompositionEnvironment,
) : SlotRenderer {
    private val byId = slots.associateBy { it.state.id }

    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) {
        val prepared = compatibleSlot(slot, modifier) ?: return
        Box(modifier) {
            slot.placements.forEach { child ->
                renderChild(prepared, child, Modifier)
            }
        }
    }

    @Composable
    override fun RenderChild(slot: SlotRenderState, child: PlacedChild, modifier: Modifier) {
        val prepared = compatibleSlot(slot, modifier) ?: return
        renderChild(prepared, child, modifier)
    }

    @Composable
    private fun compatibleSlot(slot: SlotRenderState, modifier: Modifier): PreparedSlot? {
        val prepared = byId[slot.id]
        if (prepared != null && prepared.state.type == slot.type) return prepared
        renderMismatch(null, "The contribution requested unknown or mismatched slot '${slot.id}'", modifier)
        return null
    }

    @Composable
    private fun renderChild(prepared: PreparedSlot, child: PlacedChild, modifier: Modifier) {
        val index = prepared.state.placements.indexOf(child)
        if (index < 0) {
            renderMismatch(
                child.instanceId,
                "The contribution requested an unknown or altered child '${child.instanceId}'",
                modifier,
            )
            return
        }
        prepared.children[index].Render(modifier)
    }

    @Composable
    private fun renderMismatch(instanceId: ModuleInstanceId?, message: String, modifier: Modifier) {
        val issue = CompositionIssue(CompositionIssueKind.INCOMPATIBLE_PLACEMENT, instanceId, message)
        environment.placeholderRenderer.Render(issue, modifier)
    }
}
