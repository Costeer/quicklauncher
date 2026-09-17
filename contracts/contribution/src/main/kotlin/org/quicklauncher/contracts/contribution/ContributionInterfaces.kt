package org.quicklauncher.contracts.contribution

import androidx.compose.runtime.Composable
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.CommandInvocationId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.domain.SearchResultId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PreviewScenario

private fun <T> immutableContractList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableContractMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

interface CancellationSignal {
    val isCancelled: Boolean

    fun ensureActive() {
        if (isCancelled) throw CancellationException("Contribution work was cancelled")
    }
}

data object ActiveCancellationSignal : CancellationSignal {
    override val isCancelled: Boolean = false
}

data class ContributionContext<C : Any>(
    val instanceId: ModuleInstanceId,
    val configuration: C,
    val cancellation: CancellationSignal,
    val instanceScope: CoroutineScope,
)

interface Contribution

interface ContributionSession : AutoCloseable {
    val isClosed: Boolean

    /** Closing a session is idempotent and stops all work owned by that session. */
    override fun close()
}

interface LayoutContribution<C : Any> : Contribution {
    fun open(context: ContributionContext<C>): LayoutSession
}

interface LayoutSession : ContributionSession {
    @Composable
    fun Render(input: LayoutRenderInput)
}

interface BlockContribution<C : Any> : Contribution {
    fun open(context: ContributionContext<C>): BlockSession
}

interface BlockSession : ContributionSession {
    @Composable
    fun Render(input: BlockRenderInput)
}

@JvmInline
value class SearchQuery private constructor(val value: String) {
    override fun toString(): String = "SearchQuery(redacted)"

    companion object {
        fun of(value: String): SearchQuery {
            require(value.length <= 512) { "Search query must not exceed 512 characters" }
            require('\u0000' !in value) { "Search query must not contain a null character" }
            return SearchQuery(value)
        }
    }
}

sealed interface SearchResultAction {
    data class Execute(val id: SearchActionId) : SearchResultAction
    data class InvokeCommand(val contributionId: ContributionId) : SearchResultAction
}

data class ProviderResult(
    val id: SearchResultId,
    val title: DisplayText,
    val subtitle: DisplayText?,
    val kind: SearchResultKind,
    val action: SearchResultAction,
    val providerRelevance: Int,
) {
    init {
        require(providerRelevance in 0..1_000) {
            "Provider relevance must be between 0 and 1000"
        }
    }

    override fun toString(): String = "ProviderResult(kind=$kind, content=redacted)"
}

/** Canonical typed mapping from a provider identity to its result-identity namespace. */
object SearchProviderResultIds {
    fun create(providerId: ContributionId, localName: String): SearchResultId {
        require(localName.isNotBlank()) { "Search provider result local name must not be blank" }
        val namespace = namespace(providerId)
        return SearchResultId.parse("$namespace/$localName")
    }

    fun belongsTo(resultId: SearchResultId, providerId: ContributionId): Boolean =
        resultId.value.startsWith("${namespace(providerId)}/")

    private fun namespace(providerId: ContributionId): String =
        providerId.value.substringBefore('/') + "." +
            providerId.value.substringAfter('/').replace('-', '.')
}

interface SearchProviderContribution<C : Any> : Contribution {
    fun open(context: ContributionContext<C>): SearchProviderSession
}

interface SearchProviderSession : ContributionSession {
    val results: Flow<List<ProviderResult>>

    fun updateQuery(query: SearchQuery)
}

/**
 * Host-prepared, generation-tagged input for providers that participate in production search.
 * Candidates have already crossed host permission and profile policy; providers only retrieve
 * and score from this immutable snapshot.
 */
class SearchProviderRequest(
    val generation: Long,
    val query: SearchQuery,
    candidates: Collection<ProviderResult>,
) {
    val candidates: List<ProviderResult> = immutableContractList(candidates)

    init {
        require(generation > 0L) { "Search provider generation must be positive" }
    }
}

/** Result snapshots carry the host generation so stale provider work can be rejected. */
class ProviderResultSnapshot(
    val generation: Long,
    results: Collection<ProviderResult>,
) {
    val results: List<ProviderResult> = immutableContractList(results)

    init {
        require(generation > 0L) { "Provider result generation must be positive" }
    }
}

/**
 * Production extension of the major-1 provider contract. The legacy members remain for source
 * compatibility, while the host accepts only generation-aware sessions for production fan-out.
 */
interface GenerationAwareSearchProviderSession : SearchProviderSession {
    val snapshots: Flow<ProviderResultSnapshot>

    fun update(request: SearchProviderRequest)
}

sealed interface SettingValue {
    data class BooleanValue(val value: Boolean) : SettingValue
    data class ChoiceValue(val value: StableKey) : SettingValue
    data class NumberValue(val value: Int) : SettingValue
    data class DimensionValue(val dp: Int) : SettingValue
    data class ColorValue(val value: ArgbColor) : SettingValue
    data class FontValue(val role: FontRole) : SettingValue

    class AppSelectionValue(values: Collection<ProfilePackageIdentity>) : SettingValue {
        val values: List<ProfilePackageIdentity> = immutableContractList(values)
    }

    class ContentSelectionValue(values: Collection<ContentItemId>) : SettingValue {
        val values: List<ContentItemId> = immutableContractList(values)
    }
}

class CommandParameters(values: Map<StableKey, SettingValue>) {
    val values: Map<StableKey, SettingValue> = immutableContractMap(values)
}

data class CommandInput<C : Any>(
    val invocationId: CommandInvocationId,
    val configuration: C,
    val parameters: CommandParameters,
    val cancellation: CancellationSignal,
)

sealed interface LauncherCommandAction {
    data object OpenLauncherSettings : LauncherCommandAction
    data object OpenSearch : LauncherCommandAction
    data class NavigateTo(val destinationId: DestinationId) : LauncherCommandAction
    data class OpenItemActions(val itemId: ContentItemId) : LauncherCommandAction
}

sealed interface CommandResult {
    data class Succeeded(val action: LauncherCommandAction?) : CommandResult
    data object Cancelled : CommandResult
    data class MissingAccess(val capability: CapabilityId) : CommandResult
    data class Unavailable(val reason: DisplayText) : CommandResult
    data class RecoverableFailure(val code: StableKey, val message: DisplayText) : CommandResult
}

interface LauncherCommandContribution<C : Any> : Contribution {
    suspend fun execute(input: CommandInput<C>): CommandResult
}

data class ModuleDraft(
    val instanceId: ModuleInstanceId,
    val contributionId: ContributionId,
    val configurationDocumentId: ConfigurationDocumentId,
    val configuration: ConfigurationDocument,
)

data class ModuleDraftIdentity(
    val instanceId: ModuleInstanceId,
    val configurationDocumentId: ConfigurationDocumentId,
)

/** Opaque layout-owned placement payload preserved without normalization. */
@JvmInline
value class EncodedPlacementData private constructor(val value: String) {
    companion object {
        fun of(value: String): EncodedPlacementData = EncodedPlacementData(value)
    }
}

data class PlacementData(
    val schemaVersion: SchemaVersion,
    val encoded: EncodedPlacementData,
)

data class PlacementDraft(
    val parentInstanceId: ModuleInstanceId,
    val parentSlotId: StableKey,
    val childInstanceId: ModuleInstanceId,
    val index: Int,
    val data: PlacementData,
) {
    init {
        require(index >= 0) { "Placement index must not be negative" }
    }
}

class DestinationDraft(
    val id: DestinationId,
    val name: DisplayText,
    val layout: ModuleDraft,
    blocks: Collection<ModuleDraft>,
    placements: Collection<PlacementDraft>,
) {
    val blocks: List<ModuleDraft> = immutableContractList(blocks)
    val placements: List<PlacementDraft> = immutableContractList(placements)

    init {
        val modules = listOf(layout) + this.blocks
        val moduleIds = modules.map { it.instanceId }
        require(moduleIds.size == moduleIds.toSet().size) {
            "Module instance IDs must be unique across the layout root and blocks"
        }
        val knownIds = moduleIds.toSet()
        val blockIds = this.blocks.mapTo(LinkedHashSet()) { it.instanceId }
        this.placements.forEach { placement ->
            require(placement.parentInstanceId in knownIds) {
                "Placement parent must be a known draft instance; received " +
                    "'${placement.parentInstanceId}'"
            }
            require(placement.childInstanceId != layout.instanceId) {
                "Layout root must not be the child of a placement"
            }
            require(placement.childInstanceId in blockIds) {
                "Placement child must be a declared block instance; received " +
                    "'${placement.childInstanceId}'"
            }
        }
        val duplicateChild = this.placements.groupingBy { it.childInstanceId }
            .eachCount()
            .entries
            .firstOrNull { it.value != 1 }
            ?.key
        require(duplicateChild == null) {
            "Each block instance must be the child of exactly one placement; duplicate '$duplicateChild'"
        }
        val placedChildren = this.placements.mapTo(LinkedHashSet()) { it.childInstanceId }
        val missingChild = blockIds.firstOrNull { it !in placedChildren }
        require(missingChild == null) {
            "Every declared block must be placed; missing '$missingChild'"
        }

        this.placements.groupBy { it.parentInstanceId to it.parentSlotId }.forEach { (slot, siblings) ->
            val actual = siblings.map { it.index }.sorted()
            val expected = siblings.indices.toList()
            require(actual == expected) {
                "Placement indexes must be contiguous from zero within parent '${slot.first}' " +
                    "slot '${slot.second}'; received $actual"
            }
        }

        val childrenByParent = this.placements.groupBy(
            keySelector = { it.parentInstanceId },
            valueTransform = { it.childInstanceId },
        )
        val visiting = HashSet<ModuleInstanceId>()
        val visited = HashSet<ModuleInstanceId>()
        fun visit(instanceId: ModuleInstanceId): Boolean {
            if (instanceId in visiting) return false
            if (!visited.add(instanceId)) return true
            visiting += instanceId
            val acyclic = childrenByParent[instanceId].orEmpty().all(::visit)
            visiting -= instanceId
            return acyclic
        }
        require(knownIds.all(::visit)) { "Placement graph must be acyclic" }

        val reachable = LinkedHashSet<ModuleInstanceId>()
        fun collectReachable(instanceId: ModuleInstanceId) {
            if (!reachable.add(instanceId)) return
            childrenByParent[instanceId].orEmpty().forEach(::collectReachable)
        }
        collectReachable(layout.instanceId)
        val unreachable = blockIds.filterNot { it in reachable }
        require(unreachable.isEmpty()) {
            "Every declared block must be reachable from the layout root; unreachable $unreachable"
        }
    }

    override fun equals(other: Any?): Boolean = other is DestinationDraft &&
        id == other.id && name == other.name && layout == other.layout && blocks == other.blocks &&
        placements == other.placements

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * id.hashCode() + name.hashCode()) + layout.hashCode()) +
            blocks.hashCode()) + placements.hashCode()

    override fun toString(): String =
        "DestinationDraft(id=$id, name=$name, layout=$layout, blocks=$blocks, placements=$placements)"
}

data class TemplateCoordinate(val x: Int, val y: Int) {
    fun isCardinalNeighborOf(other: TemplateCoordinate): Boolean =
        kotlin.math.abs(x.toLong() - other.x.toLong()) +
            kotlin.math.abs(y.toLong() - other.y.toLong()) == 1L
}

data class PositionedDestinationDraft(
    val coordinate: TemplateCoordinate,
    val draft: DestinationDraft,
    val isStart: Boolean,
)

class TemplatePlan(destinations: Collection<PositionedDestinationDraft>) {
    val destinations: List<PositionedDestinationDraft> = immutableContractList(destinations)
    val startDestination: PositionedDestinationDraft

    init {
        require(this.destinations.isNotEmpty()) { "Template plan must contain at least one destination" }
        require(this.destinations.map { it.draft.id }.toSet().size == this.destinations.size) {
            "Template plan destination IDs must be unique"
        }
        require(this.destinations.map { it.coordinate }.toSet().size == this.destinations.size) {
            "Template plan coordinates must be unique"
        }
        val modules = this.destinations.flatMap { listOf(it.draft.layout) + it.draft.blocks }
        require(modules.map { it.instanceId }.toSet().size == modules.size) {
            "Template plan module instance IDs must be unique across destinations"
        }
        require(modules.map { it.configurationDocumentId }.toSet().size == modules.size) {
            "Template plan configuration document IDs must be unique across destinations"
        }
        val starts = this.destinations.filter { it.isStart }
        require(starts.size == 1) { "Template plan must contain exactly one start destination" }
        startDestination = starts.single()

        val reachable = LinkedHashSet<TemplateCoordinate>()
        val pending = ArrayDeque<TemplateCoordinate>()
        pending += startDestination.coordinate
        while (pending.isNotEmpty()) {
            val coordinate = pending.removeFirst()
            if (!reachable.add(coordinate)) continue
            this.destinations.asSequence()
                .map { it.coordinate }
                .filter(coordinate::isCardinalNeighborOf)
                .filterNot(reachable::contains)
                .forEach(pending::addLast)
        }
        require(reachable.size == this.destinations.size) {
            "Template plan destinations must form one cardinally connected map"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TemplatePlan && destinations == other.destinations

    override fun hashCode(): Int = destinations.hashCode()

    override fun toString(): String = "TemplatePlan(destinations=$destinations)"
}

data class TemplateDestinationInput(
    val destinationId: DestinationId,
    val name: DisplayText,
)

class TemplateInput<C : Any>(
    destinations: Collection<TemplateDestinationInput>,
    availableModuleIdentities: Collection<ModuleDraftIdentity>,
    val configuration: C,
    val cancellation: CancellationSignal,
) {
    val destinations: List<TemplateDestinationInput> = immutableContractList(destinations)
    val availableModuleIdentities: List<ModuleDraftIdentity> =
        immutableContractList(availableModuleIdentities)

    init {
        require(this.destinations.isNotEmpty()) {
            "Template input must supply at least one destination identity"
        }
        require(this.destinations.map { it.destinationId }.toSet().size == this.destinations.size) {
            "Template input destination IDs must be unique"
        }
        require(this.availableModuleIdentities.isNotEmpty()) {
            "Template input must supply at least one module identity"
        }
        require(
            this.availableModuleIdentities.map { it.instanceId }.toSet().size ==
                this.availableModuleIdentities.size,
        ) { "Template input module instance IDs must be unique" }
        require(
            this.availableModuleIdentities.map { it.configurationDocumentId }.toSet().size ==
                this.availableModuleIdentities.size,
        ) { "Template input configuration document IDs must be unique" }
    }
}

sealed interface TemplateResult {
    data class Created(val plan: TemplatePlan) : TemplateResult
    data class Invalid(val code: StableKey, val message: DisplayText) : TemplateResult
}

interface DestinationTemplateContribution<C : Any> : Contribution {
    fun create(input: TemplateInput<C>): TemplateResult
}

interface ContributionContractDeclaration<C : Any> {
    val contributionId: ContributionId
    val scenarios: Set<PreviewScenario>
    val performanceHooks: List<PerformanceHookDeclaration>
}

interface LayoutContractTestDeclaration<C : Any> : ContributionContractDeclaration<C>
interface BlockContractTestDeclaration<C : Any> : ContributionContractDeclaration<C>
interface SearchProviderContractTestDeclaration<C : Any> : ContributionContractDeclaration<C>
interface LauncherCommandContractTestDeclaration<C : Any> : ContributionContractDeclaration<C>
interface DestinationTemplateContractTestDeclaration<C : Any> : ContributionContractDeclaration<C>
