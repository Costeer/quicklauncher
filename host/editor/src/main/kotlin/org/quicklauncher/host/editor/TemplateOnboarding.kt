package org.quicklauncher.host.editor

import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.contribution.ConfigurationLoadResult
import org.quicklauncher.contracts.contribution.ConfigurationPipeline
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.DestinationTemplateContribution
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.RegisteredDestinationTemplate
import org.quicklauncher.contracts.contribution.RegisteredLayout
import org.quicklauncher.contracts.contribution.TemplateInput
import org.quicklauncher.contracts.contribution.TemplatePlan
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.contribution.find
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherPlanInstallation
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument

class OnboardingPreview internal constructor(
    val templateId: ContributionId,
    val plan: TemplatePlan,
    issues: Collection<String>,
) {
    val issues: List<String> = Collections.unmodifiableList(ArrayList(issues))
    val canInstall: Boolean get() = issues.isEmpty()
}

data class SafeLayoutDraft(
    val layout: DestinationLayoutRecord,
    val instance: ModuleInstanceRecord,
    val configuration: StoredConfigurationDocument,
)

fun interface OnboardingSafeLayoutFactory {
    fun create(destinationId: DestinationId): SafeLayoutDraft
}

sealed interface OnboardingResult {
    data class Previewed(val preview: OnboardingPreview) : OnboardingResult
    data class Invalid(val code: String, val message: String) : OnboardingResult
    data object Installed : OnboardingResult
    data class Rejected(val code: String, val message: String) : OnboardingResult
}

/** Validates contribution output and commits the complete configured map in one store revision. */
class TemplateOnboarding(
    private val registry: ContributionRegistry,
    private val store: LauncherStore,
    private val safeLayouts: OnboardingSafeLayoutFactory,
    private val identities: EditorIdentitySource = EditorIdentitySource {
        java.util.UUID.randomUUID().toString().lowercase()
    },
) {
    private val mutex = Mutex()

    @Suppress("UNCHECKED_CAST")
    fun <C : Any> preview(templateId: ContributionId, input: TemplateInput<C>): OnboardingResult {
        val registration = registry.find(templateId) as? RegisteredDestinationTemplate<C>
            ?: return OnboardingResult.Invalid("template.missing", "Template '$templateId' is unavailable")
        val result = try {
            (registration.target as DestinationTemplateContribution<C>).create(input)
        } catch (failure: IllegalArgumentException) {
            return OnboardingResult.Invalid("template.invalid", failure.message ?: "Template plan is invalid")
        }
        if (result is TemplateResult.Invalid) {
            return OnboardingResult.Invalid(result.code.value, result.message.value)
        }
        val plan = (result as TemplateResult.Created).plan
        val issues = validatePlan(plan)
        return OnboardingResult.Previewed(OnboardingPreview(templateId, plan, issues))
    }

    suspend fun install(preview: OnboardingPreview): OnboardingResult = mutex.withLock {
        if (!preview.canInstall) {
            return@withLock OnboardingResult.Rejected(
                "template.invalid",
                preview.issues.joinToString(),
            )
        }
        val installation = buildInstallation(preview.plan)
        val before = store.read()
        when (
            val result = store.commit(
                LauncherTransaction(before.revision, listOf(LauncherEdit.InstallLauncherPlan(installation))),
            )
        ) {
            is CommitResult.Committed -> OnboardingResult.Installed
            is CommitResult.Rejected -> OnboardingResult.Rejected(result.reason.code.name, result.reason.message)
        }
    }

    private fun validatePlan(plan: TemplatePlan): List<String> = buildList {
        plan.destinations.forEach { positioned ->
            val draft = positioned.draft
            val layout = registry.find(draft.layout.contributionId) as? RegisteredLayout<*>
            if (layout == null) {
                add("Layout '${draft.layout.contributionId}' is unavailable")
                return@forEach
            }
            validateConfiguration(draft.layout, layout, this)
            val modules = (listOf(draft.layout) + draft.blocks).associateBy { it.instanceId }
            draft.blocks.forEach { block ->
                val registered = registry.find(block.contributionId) as? RegisteredBlock<*>
                if (registered == null) add("Block '${block.contributionId}' is unavailable")
                else validateConfiguration(block, registered, this)
            }
            draft.placements.groupBy { it.parentInstanceId to it.parentSlotId }.forEach { (key, placements) ->
                val parent = modules[key.first]
                val parentSlots = when (val registered = parent?.let { registry.find(it.contributionId) }) {
                    is RegisteredLayout<*> -> registered.descriptor.slots
                    is RegisteredBlock<*> -> registered.descriptor.childSlots
                    else -> emptyList()
                }
                val slot = parentSlots.singleOrNull { it.id == key.second }
                if (slot == null) {
                    add("Parent '${key.first}' has no slot '${key.second}'")
                    return@forEach
                }
                if (placements.size > slot.maximumChildren) add("Slot '${slot.id}' is over capacity")
                placements.forEach { placement ->
                    val block = modules[placement.childInstanceId]?.let { registry.find(it.contributionId) }
                        as? RegisteredBlock<*>
                    if (block != null && !isCompatible(slot, block)) {
                        add("Block '${block.descriptor.metadata.id}' is incompatible with slot '${slot.id}'")
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateConfiguration(
        draft: org.quicklauncher.contracts.contribution.ModuleDraft,
        registration: org.quicklauncher.contracts.contribution.RegisteredContribution<*>,
        issues: MutableList<String>,
    ) {
        val loaded = ConfigurationPipeline.load(
            draft.configuration,
            registration.codec as org.quicklauncher.contracts.contribution.ConfigurationCodec<Any>,
        )
        if (loaded is ConfigurationLoadResult.Failed) {
            issues += "Configuration for '${draft.contributionId}' failed: ${loaded.error.code}"
        }
    }

    private fun isCompatible(
        slot: org.quicklauncher.contracts.contribution.SlotDescriptor,
        block: RegisteredBlock<*>,
    ): Boolean = block.descriptor.metadata.id in slot.acceptedBlocks &&
        slot.type in block.descriptor.compatibleSlotTypes &&
        block.descriptor.metadata.providedCapabilities.containsAll(slot.requiredCapabilities) &&
        slot.allowedScrollAxes.containsAll(block.descriptor.occupiedScrollAxes)

    private fun buildInstallation(plan: TemplatePlan): LauncherPlanInstallation {
        val destinations = mutableListOf<DestinationRecord>()
        val layouts = mutableListOf<DestinationLayoutRecord>()
        val instances = mutableListOf<ModuleInstanceRecord>()
        val configurations = mutableListOf<StoredConfigurationDocument>()
        val placements = mutableListOf<PlacementRecord>()
        plan.destinations.forEach { positioned ->
            val draft = positioned.draft
            destinations += DestinationRecord(
                draft.id,
                draft.name.value,
                DestinationCoordinate(positioned.coordinate.x.toLong(), positioned.coordinate.y.toLong()),
            )
            val safe = safeLayouts.create(draft.id)
            layouts += safe.layout.copy(selected = false)
            instances += safe.instance
            configurations += safe.configuration
            layouts += DestinationLayoutRecord(
                draft.id,
                draft.layout.contributionId,
                draft.layout.instanceId,
                selected = true,
            )
            (listOf(draft.layout) + draft.blocks).forEach { module ->
                instances += ModuleInstanceRecord(
                    module.instanceId,
                    module.contributionId,
                    module.configurationDocumentId,
                )
                configurations += StoredConfigurationDocument(
                    module.configurationDocumentId,
                    module.configuration,
                )
            }
            draft.placements.forEach { placement ->
                placements += PlacementRecord(
                    PlacementId.parse("org.quicklauncher.placement/${identities.nextLocalId()}"),
                    draft.layout.instanceId,
                    placement.parentInstanceId,
                    placement.parentSlotId,
                    placement.childInstanceId,
                    placement.index,
                    EncodedPlacementData.of(placement.data.encoded.value),
                    placement.data.schemaVersion.value,
                )
            }
        }
        return LauncherPlanInstallation(
            plan.startDestination.draft.id,
            destinations,
            layouts,
            instances,
            configurations,
            placements,
        )
    }
}
