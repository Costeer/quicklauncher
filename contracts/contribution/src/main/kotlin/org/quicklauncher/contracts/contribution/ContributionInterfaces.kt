package org.quicklauncher.contracts.contribution

import androidx.compose.runtime.Composable
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.CommandInvocationId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.domain.SearchResultId
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
}

interface SearchProviderContribution<C : Any> : Contribution {
    fun open(context: ContributionContext<C>): SearchProviderSession
}

interface SearchProviderSession : ContributionSession {
    val results: Flow<List<ProviderResult>>

    fun updateQuery(query: SearchQuery)
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
    val configuration: ConfigurationDocument,
)

class DestinationDraft(
    val id: DestinationId,
    val name: DisplayText,
    val layout: ModuleDraft,
    blocks: Collection<ModuleDraft>,
) {
    val blocks: List<ModuleDraft> = immutableContractList(blocks)

    override fun equals(other: Any?): Boolean = other is DestinationDraft &&
        id == other.id && name == other.name && layout == other.layout && blocks == other.blocks

    override fun hashCode(): Int = 31 * (31 * (31 * id.hashCode() + name.hashCode()) + layout.hashCode()) +
        blocks.hashCode()

    override fun toString(): String =
        "DestinationDraft(id=$id, name=$name, layout=$layout, blocks=$blocks)"
}

class TemplateInput<C : Any>(
    val destinationId: DestinationId,
    val name: DisplayText,
    availableInstanceIds: Collection<ModuleInstanceId>,
    val configuration: C,
    val cancellation: CancellationSignal,
) {
    val availableInstanceIds: List<ModuleInstanceId> = immutableContractList(availableInstanceIds)
}

sealed interface TemplateResult {
    data class Created(val draft: DestinationDraft) : TemplateResult
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
