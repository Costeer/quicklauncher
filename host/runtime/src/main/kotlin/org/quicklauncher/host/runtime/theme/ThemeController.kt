package org.quicklauncher.host.runtime.theme

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction

enum class ThemeRecoveryReason {
    NONE,
    MISSING_PROFILE,
    INVALID_PROFILE,
    INCOMPATIBLE_PROFILE,
}

class ThemeControllerState(
    profiles: Collection<ThemeProfile>,
    val activeProfile: ThemeProfile,
    val previewProfile: ThemeProfile?,
    backgrounds: Map<DestinationId, BackgroundDefinition>,
    fontAssets: Set<StableKey>,
    imageAssets: Set<StableKey>,
    val recoveryReason: ThemeRecoveryReason,
    val generation: Long,
) {
    val profiles: List<ThemeProfile> = Collections.unmodifiableList(
        profiles.sortedBy { it.id.value },
    )
    val backgrounds: Map<DestinationId, BackgroundDefinition> =
        Collections.unmodifiableMap(LinkedHashMap(backgrounds))
    val fontAssets: Set<StableKey> = Collections.unmodifiableSet(LinkedHashSet(fontAssets))
    val imageAssets: Set<StableKey> = Collections.unmodifiableSet(LinkedHashSet(imageAssets))

    fun profileForPresentation(): ThemeProfile = previewProfile ?: activeProfile
}

sealed interface ThemeMutationResult {
    data class Applied(val state: ThemeControllerState) : ThemeMutationResult
    data object Missing : ThemeMutationResult
    data object Invalid : ThemeMutationResult
    data object Conflict : ThemeMutationResult
    /** The requested mutation was not applied and compensating recovery was required. */
    data object RecoveryRequired : ThemeMutationResult
    data object Closed : ThemeMutationResult
}

/** Coordinates Room profile records with the atomic Proto active-profile selector. */
class ThemeController(
    private val store: LauncherStore,
    private val preferences: LauncherPreferencesStore,
    private val registeredCapabilities: Map<ContributionId, Set<CapabilityId>>,
    private val availableFontAssets: suspend () -> Set<StableKey> = { emptySet() },
    private val availableImageAssets: suspend () -> Set<StableKey> = { emptySet() },
    private val cleanupAssets: suspend (Set<StableKey>) -> Unit = {},
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(
        ThemeControllerState(
            BuiltInThemeProfiles.All,
            BuiltInThemeProfiles.System,
            null,
            emptyMap(),
            emptySet(),
            emptySet(),
            ThemeRecoveryReason.NONE,
            0L,
        ),
    )
    val state: StateFlow<ThemeControllerState> = mutableState.asStateFlow()
    private var preferenceCollector: Job? = null

    suspend fun start() {
        mutex.withLock {
            checkOpen()
            reload(preferences.read().themeProfileId, preservePreview = false)
            cleanupBestEffort(referencedAssets(mutableState.value))
            if (preferenceCollector == null) {
                preferenceCollector = scope.launch {
                    preferences.state.collectLatest { value ->
                        mutex.withLock {
                            if (!closed.get()) reload(value.themeProfileId, preservePreview = true)
                        }
                    }
                }
            }
        }
    }

    suspend fun select(profileId: ThemeProfileId): ThemeMutationResult = mutex.withLock {
        if (closed.get()) return ThemeMutationResult.Closed
        val profile = mutableState.value.profiles.singleOrNull { it.id == profileId }
            ?: return ThemeMutationResult.Missing
        if (!isResolvable(profile)) return ThemeMutationResult.Invalid
        try {
            preferences.setThemeProfile(profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ThemeMutationResult.RecoveryRequired
        }
        reload(profileId, preservePreview = false)
        ThemeMutationResult.Applied(mutableState.value)
    }

    suspend fun preview(profile: ThemeProfile?): ThemeMutationResult = mutex.withLock {
        if (closed.get()) return ThemeMutationResult.Closed
        if (profile != null && !isResolvable(profile)) return ThemeMutationResult.Invalid
        val current = mutableState.value
        mutableState.value = ThemeControllerState(
            current.profiles,
            current.activeProfile,
            profile,
            current.backgrounds,
            current.fontAssets,
            current.imageAssets,
            current.recoveryReason,
            current.generation + 1L,
        )
        ThemeMutationResult.Applied(mutableState.value)
    }

    suspend fun save(profile: ThemeProfile, select: Boolean): ThemeMutationResult = mutex.withLock {
        if (closed.get()) return ThemeMutationResult.Closed
        if (BuiltInThemeProfiles.find(profile.id) != null || !isResolvable(profile)) {
            return ThemeMutationResult.Invalid
        }
        val encoded = ThemeProfileCodec.encode(profile)
        if (ThemeProfileCodec.decode(encoded) !is ThemeProfileDecodeResult.Valid) {
            return ThemeMutationResult.Invalid
        }
        val selectedBefore = preferences.read().themeProfileId
        val snapshot = store.read()
        val committed = when (
            val result = store.commit(
                LauncherTransaction(snapshot.revision, listOf(LauncherEdit.PutThemeProfile(encoded))),
            )
        ) {
            is CommitResult.Rejected -> return ThemeMutationResult.Conflict
            is CommitResult.Committed -> result
        }
        if (select) {
            try {
                preferences.setThemeProfile(profile.id)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    compensateProfileSave(committed.state.revision, snapshot, profile.id)
                }
                throw cancelled
            } catch (_: Exception) {
                compensateProfileSave(committed.state.revision, snapshot, profile.id)
                reload(selectedBefore, preservePreview = false)
                return ThemeMutationResult.RecoveryRequired
            }
        }
        reload(if (select) profile.id else preferences.read().themeProfileId, preservePreview = false)
        cleanupBestEffort(referencedAssets(mutableState.value))
        ThemeMutationResult.Applied(mutableState.value)
    }

    suspend fun delete(profileId: ThemeProfileId): ThemeMutationResult = mutex.withLock {
        if (closed.get()) return ThemeMutationResult.Closed
        if (BuiltInThemeProfiles.find(profileId) != null) return ThemeMutationResult.Invalid
        val selectedBefore = preferences.read().themeProfileId
        val snapshot = store.read()
        if (snapshot.themeProfiles.none { it.id == profileId }) return ThemeMutationResult.Missing
        val edits = snapshot.destinationBackgrounds
            .filter { it.themeProfileId == profileId }
            .map { LauncherEdit.DeleteDestinationBackground(it.destinationId) } +
            LauncherEdit.DeleteThemeProfile(profileId, confirmed = true)
        val deletingActiveProfile = mutableState.value.activeProfile.id == profileId
        val committed = when (val result = store.commit(LauncherTransaction(snapshot.revision, edits))) {
            is CommitResult.Rejected -> return ThemeMutationResult.Conflict
            is CommitResult.Committed -> result
        }
        if (deletingActiveProfile) {
            try {
                preferences.setThemeProfile(BuiltInThemeProfiles.SystemId)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    compensateProfileDelete(committed.state.revision, snapshot, profileId)
                }
                throw cancelled
            } catch (_: Exception) {
                compensateProfileDelete(committed.state.revision, snapshot, profileId)
                reload(selectedBefore, preservePreview = false)
                return ThemeMutationResult.RecoveryRequired
            }
        }
        reload(preferences.read().themeProfileId, preservePreview = false)
        cleanupBestEffort(referencedAssets(mutableState.value))
        ThemeMutationResult.Applied(mutableState.value)
    }

    suspend fun setDestinationBackground(
        destinationId: DestinationId,
        background: BackgroundDefinition?,
        materializedProfileId: ThemeProfileId? = null,
    ): ThemeMutationResult = mutex.withLock {
        if (closed.get()) return ThemeMutationResult.Closed
        val active = mutableState.value.activeProfile
        if (!isResolvable(active)) return ThemeMutationResult.Invalid
        val selectedBefore = preferences.read().themeProfileId
        val snapshot = store.read()
        val isBuiltIn = BuiltInThemeProfiles.find(active.id) != null
        val materializedProfile = if (background != null && isBuiltIn) {
            val id = materializedProfileId ?: return ThemeMutationResult.Invalid
            if (
                BuiltInThemeProfiles.find(id) != null ||
                snapshot.themeProfiles.any { it.id == id }
            ) {
                return ThemeMutationResult.Invalid
            }
            active.copy(id = id, name = "Customized ${active.name}")
        } else {
            null
        }
        val profileId = materializedProfile?.id ?: active.id
        val edits: List<LauncherEdit> = if (background == null) {
            listOf(LauncherEdit.DeleteDestinationBackground(destinationId))
        } else {
            val record = DestinationBackgroundCodec.encode(destinationId, profileId, background)
            if (DestinationBackgroundCodec.decode(record) !is DestinationBackgroundDecodeResult.Valid) {
                return ThemeMutationResult.Invalid
            }
            buildList {
                materializedProfile?.let {
                    val encoded = ThemeProfileCodec.encode(it)
                    if (ThemeProfileCodec.decode(encoded) !is ThemeProfileDecodeResult.Valid) {
                        return ThemeMutationResult.Invalid
                    }
                    add(LauncherEdit.PutThemeProfile(encoded))
                }
                add(LauncherEdit.PutDestinationBackground(record))
            }
        }
        val committed = when (val result = store.commit(LauncherTransaction(snapshot.revision, edits))) {
            is CommitResult.Rejected -> return ThemeMutationResult.Conflict
            is CommitResult.Committed -> result
        }
        if (materializedProfile != null) {
            try {
                preferences.setThemeProfile(materializedProfile.id)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    compensateMaterializedBackground(
                        committed.state.revision,
                        snapshot,
                        destinationId,
                        materializedProfile.id,
                    )
                }
                throw cancelled
            } catch (_: Exception) {
                compensateMaterializedBackground(
                    committed.state.revision,
                    snapshot,
                    destinationId,
                    materializedProfile.id,
                )
                reload(selectedBefore, preservePreview = true)
                return ThemeMutationResult.RecoveryRequired
            }
        }
        reload(materializedProfile?.id ?: preferences.read().themeProfileId, preservePreview = true)
        cleanupBestEffort(referencedAssets(mutableState.value))
        ThemeMutationResult.Applied(mutableState.value)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            preferenceCollector?.cancel()
            scope.cancel()
        }
    }

    private suspend fun reload(selectedId: ThemeProfileId?, preservePreview: Boolean) {
        val snapshot = store.read()
        var invalidSelected = false
        val custom = snapshot.themeProfiles.take(MAX_THEME_PROFILE_SCAN).mapNotNull { record ->
            if (BuiltInThemeProfiles.find(record.id) != null) return@mapNotNull null
            when (val decoded = ThemeProfileCodec.decode(record)) {
                is ThemeProfileDecodeResult.Valid -> decoded.profile
                ThemeProfileDecodeResult.Invalid -> {
                    if (record.id == selectedId) invalidSelected = true
                    null
                }
            }
        }
        val profiles = BuiltInThemeProfiles.All + custom
        val selected = BuiltInThemeProfiles.find(selectedId) ?: custom.singleOrNull { it.id == selectedId }
        val reason = when {
            selected != null && isResolvable(selected) -> ThemeRecoveryReason.NONE
            invalidSelected || selected != null -> ThemeRecoveryReason.INVALID_PROFILE
            selectedId != null -> ThemeRecoveryReason.MISSING_PROFILE
            else -> ThemeRecoveryReason.NONE
        }
        val active = selected?.takeIf { isResolvable(it) } ?: BuiltInThemeProfiles.System
        val backgrounds = snapshot.destinationBackgrounds
            .take(MAX_DESTINATION_BACKGROUND_SCAN)
            .mapNotNull { record ->
                if (record.themeProfileId != active.id) return@mapNotNull null
                when (val decoded = DestinationBackgroundCodec.decode(record)) {
                    is DestinationBackgroundDecodeResult.Valid -> record.destinationId to decoded.background
                    DestinationBackgroundDecodeResult.Invalid -> null
                }
            }
            .toMap()
        val current = mutableState.value
        val fonts = fontAssetsOrEmpty()
        val images = imageAssetsOrEmpty()
        mutableState.value = ThemeControllerState(
            profiles,
            active,
            current.previewProfile.takeIf { preservePreview },
            backgrounds,
            fonts,
            images,
            reason,
            current.generation + 1L,
        )
    }

    private suspend fun isResolvable(profile: ThemeProfile): Boolean {
        val assets = fontAssetsOrEmpty()
        return try {
            listOf(false, true).all { dark ->
                BuiltInLauncherThemeResolver.resolve(
                    ThemeResolutionRequest(
                        dark,
                        1f,
                        false,
                        profile,
                        registeredCapabilities,
                        assets,
                    ),
                )
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            false
        }
    }

    private suspend fun cleanupBestEffort(referenced: Set<StableKey>) {
        try {
            cleanupAssets(referenced)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cleanup is retried at the next successful mutation and cold start.
        }
    }

    private suspend fun compensateProfileSave(
        revision: org.quicklauncher.host.data.store.StoreRevision,
        before: org.quicklauncher.host.data.store.LauncherSnapshot,
        profileId: ThemeProfileId,
    ) {
        val previous = before.themeProfiles.singleOrNull { it.id == profileId }
        compensate(
            revision,
            listOfNotNull(
                previous?.let(LauncherEdit::PutThemeProfile)
                    ?: LauncherEdit.DeleteThemeProfile(profileId, confirmed = true),
            ),
        )
    }

    private suspend fun compensateProfileDelete(
        revision: org.quicklauncher.host.data.store.StoreRevision,
        before: org.quicklauncher.host.data.store.LauncherSnapshot,
        profileId: ThemeProfileId,
    ) {
        val profile = before.themeProfiles.singleOrNull { it.id == profileId } ?: return
        compensate(
            revision,
            listOf(LauncherEdit.PutThemeProfile(profile)) +
                before.destinationBackgrounds
                    .filter { it.themeProfileId == profileId }
                    .map(LauncherEdit::PutDestinationBackground),
        )
    }

    private suspend fun compensateMaterializedBackground(
        revision: org.quicklauncher.host.data.store.StoreRevision,
        before: org.quicklauncher.host.data.store.LauncherSnapshot,
        destinationId: DestinationId,
        profileId: ThemeProfileId,
    ) {
        val previousBackground = before.destinationBackgrounds
            .singleOrNull { it.destinationId == destinationId }
        compensate(
            revision,
            listOfNotNull(
                previousBackground?.let(LauncherEdit::PutDestinationBackground)
                    ?: LauncherEdit.DeleteDestinationBackground(destinationId),
                LauncherEdit.DeleteThemeProfile(profileId, confirmed = true),
            ),
        )
    }

    private suspend fun compensate(
        revision: org.quicklauncher.host.data.store.StoreRevision,
        edits: List<LauncherEdit>,
    ) {
        try {
            store.commit(LauncherTransaction(revision, edits))
        } catch (_: Exception) {
            // RecoveryRequired tells the caller to retain the safe state and retry reconciliation.
        }
    }

    private suspend fun fontAssetsOrEmpty(): Set<StableKey> = try {
        availableFontAssets().take(MAX_TRACKED_FONT_ASSETS).toSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }

    private suspend fun imageAssetsOrEmpty(): Set<StableKey> = try {
        availableImageAssets().take(MAX_TRACKED_IMAGE_ASSETS).toSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }

    private fun checkOpen() = check(!closed.get()) { "Theme controller is closed" }

    private fun referencedAssets(value: ThemeControllerState): Set<StableKey> = buildSet {
        value.profiles.forEach { profile ->
            profile.fonts.values.forEach { add(it.assetId) }
            profile.imageDerived?.let { add(it.previewId) }
            profile.background?.let { addBackgroundAssets(it) }
        }
        value.backgrounds.values.forEach { addBackgroundAssets(it) }
    }

    private fun MutableSet<StableKey>.addBackgroundAssets(background: BackgroundDefinition) {
        if (background is BackgroundDefinition.Image) {
            add(background.assetId)
            add(background.previewId)
        }
    }

    private companion object {
        const val MAX_TRACKED_FONT_ASSETS = 32
        const val MAX_TRACKED_IMAGE_ASSETS = 256
        const val MAX_THEME_PROFILE_SCAN = 65
        const val MAX_DESTINATION_BACKGROUND_SCAN = 257
    }
}
