package org.quicklauncher.host.editor

import java.util.Collections
import java.util.concurrent.CancellationException
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
import org.quicklauncher.contracts.contribution.TemplateDestinationInput
import org.quicklauncher.contracts.contribution.ModuleDraftIdentity
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.TemplatePlan
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.contribution.find
import org.quicklauncher.contracts.contribution.compatibilityProblem
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
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
    val snapshot: org.quicklauncher.host.data.store.LauncherSnapshot? = null,
    internal val installation: LauncherPlanInstallation? = null,
) {
    val issues: List<String> = Collections.unmodifiableList(ArrayList(issues))
    val canInstall: Boolean get() = issues.isEmpty()
}

data class OnboardingTemplate(
    val id: ContributionId,
    val name: String,
    val description: String,
    val defaultConfiguration: String,
    val settings: List<String>,
)

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
        randomEditorLocalId()
    },
) {
    private val mutex = Mutex()

    fun availableTemplates(): List<OnboardingTemplate> = registry.entries
        .filterIsInstance<RegisteredDestinationTemplate<*>>()
        .sortedBy { it.descriptor.metadata.id.value }
        .map {
            OnboardingTemplate(
                it.descriptor.metadata.id,
                it.descriptor.metadata.displayName.value,
                it.descriptor.metadata.description.value,
                defaultDocument(it.codec).encoded.value,
                it.descriptor.metadata.settings?.fields?.map { field -> field.label }.orEmpty(),
            )
        }

    /** Builds a typed, oversized identity pool and previews the registered production default. */
    fun previewDefault(templateId: ContributionId): OnboardingResult {
        val registration = registry.find(templateId) as? RegisteredDestinationTemplate<*>
            ?: return OnboardingResult.Invalid("template.missing", "Template '$templateId' is unavailable")
        return previewConfigured(registration, defaultDocument(registration.codec).encoded.value)
    }

    fun previewConfigured(templateId: ContributionId, encoded: String): OnboardingResult {
        val registration = registry.find(templateId) as? RegisteredDestinationTemplate<*>
            ?: return OnboardingResult.Invalid("template.missing", "Template '$templateId' is unavailable")
        return previewConfigured(registration, encoded)
    }

    @Suppress("UNCHECKED_CAST")
    private fun previewConfigured(
        registration: RegisteredDestinationTemplate<*>,
        encoded: String,
    ): OnboardingResult {
        val typed = registration as RegisteredDestinationTemplate<Any>
        val configuration = when (
            val decoded = typed.codec.decode(
                org.quicklauncher.contracts.contribution.EncodedConfiguration.of(encoded),
            )
        ) {
            is org.quicklauncher.contracts.contribution.CodecResult.Decoded -> decoded.value
            is org.quicklauncher.contracts.contribution.CodecResult.Failed -> return OnboardingResult.Invalid(
                "template.configuration-invalid",
                decoded.reason,
            )
        }
        val destinationCount = 16
        val suggestedNames = listOf("Home", "Entry", "All apps")
        val destinations = List(destinationCount) { index ->
            TemplateDestinationInput(
                DestinationId.parse("org.quicklauncher.destination/${identities.nextLocalId()}"),
                DisplayText.of(suggestedNames.getOrElse(index) { "Destination ${index + 1}" }),
            )
        }
        val moduleCount = destinationCount + registration.descriptor.maximumBlocks
        val modules = List(moduleCount) {
            ModuleDraftIdentity(
                ModuleInstanceId.parse("org.quicklauncher.instance/${identities.nextLocalId()}"),
                ConfigurationDocumentId.parse(
                    "org.quicklauncher.configuration/${identities.nextLocalId()}",
                ),
            )
        }
        return previewErased(registration, destinations, modules, configuration)
    }

    @Suppress("UNCHECKED_CAST")
    private fun previewErased(
        registration: RegisteredDestinationTemplate<*>,
        destinations: List<TemplateDestinationInput>,
        modules: List<ModuleDraftIdentity>,
        configuration: Any,
    ): OnboardingResult {
        val typed = registration as RegisteredDestinationTemplate<Any>
        return preview(
            typed.descriptor.metadata.id,
            TemplateInput(destinations, modules, configuration, org.quicklauncher.contracts.contribution.ActiveCancellationSignal),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun <C : Any> preview(templateId: ContributionId, input: TemplateInput<C>): OnboardingResult {
        val registration = registry.find(templateId) as? RegisteredDestinationTemplate<C>
            ?: return OnboardingResult.Invalid("template.missing", "Template '$templateId' is unavailable")
        val missingRequired = registration.descriptor.requiredContributions
            .filter { registry.find(it) == null }
            .sortedBy { it.value }
        if (missingRequired.isNotEmpty()) {
            return OnboardingResult.Invalid(
                "template.contribution-missing",
                "Required contributions are unavailable: ${missingRequired.joinToString()}",
            )
        }
        val result = try {
            (registration.target as DestinationTemplateContribution<C>).create(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            return OnboardingResult.Invalid("template.invalid", failure.message ?: "Template plan is invalid")
        } catch (failure: RuntimeException) {
            return OnboardingResult.Invalid("template.failed", "Template creation failed")
        }
        if (result is TemplateResult.Invalid) {
            return OnboardingResult.Invalid(result.code.value, result.message.value)
        }
        val plan = (result as TemplateResult.Created).plan
        val issues = validatePlan(plan, registration.descriptor, input)
        val installation = if (issues.isEmpty()) buildInstallation(plan) else null
        return OnboardingResult.Previewed(
            OnboardingPreview(
                templateId,
                plan,
                issues,
                installation?.let { org.quicklauncher.host.data.store.LauncherSnapshot.preview(it) },
                installation,
            ),
        )
    }

    suspend fun install(preview: OnboardingPreview): OnboardingResult = mutex.withLock {
        if (!preview.canInstall) {
            return@withLock OnboardingResult.Rejected(
                "template.invalid",
                preview.issues.joinToString(),
            )
        }
        val installation = preview.installation ?: return@withLock OnboardingResult.Rejected(
            "template.preview-missing",
            "The validated preview must be rebuilt before installation",
        )
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

    private fun validatePlan(
        plan: TemplatePlan,
        descriptor: org.quicklauncher.contracts.contribution.DestinationTemplateDescriptor,
        input: TemplateInput<*>,
    ): List<String> = buildList {
        val suppliedDestinations = input.destinations.associate { it.destinationId to it.name }
        val suppliedModules = input.availableModuleIdentities.toSet()
        plan.destinations.forEach { positioned ->
            val draft = positioned.draft
            if (suppliedDestinations[draft.id] != draft.name) {
                add("Destination '${draft.id}' does not match a supplied destination identity and name")
            }
            val draftModules = listOf(draft.layout) + draft.blocks
            draftModules.forEach { module ->
                val identity = ModuleDraftIdentity(module.instanceId, module.configurationDocumentId)
                if (identity !in suppliedModules) {
                    add("Module '${module.instanceId}' does not use a supplied module identity")
                }
                if (module.contributionId !in descriptor.requiredContributions) {
                    add("Module contribution '${module.contributionId}' was not declared by the template")
                }
            }
            if (draft.blocks.size > descriptor.maximumBlocks) {
                add(
                    "Destination '${draft.id}' has ${draft.blocks.size} blocks; " +
                        "the template allows at most ${descriptor.maximumBlocks}",
                )
            }
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
                    if (placement.data.schemaVersion.value != 1) {
                        add("Slot '${slot.id}' does not support placement schema ${placement.data.schemaVersion.value}")
                    }
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
    ): Boolean = slot.compatibilityProblem(block.descriptor, childCount = 1) == null

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
