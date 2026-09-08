package org.quicklauncher.host.runtime.catalog

import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ProfileSerial

enum class AppCatalogStatus {
    LOADING,
    READY,
    ERROR,
}

data class CatalogApp(
    val identity: AppActivityIdentity,
    val label: String,
    val icon: AppIcon,
    val favorite: Boolean,
    val collectionVisible: Boolean = true,
)

class CatalogProfile(
    val serial: ProfileSerial,
    val kind: AppProfileKind,
    val available: Boolean,
    val badgeText: String?,
    apps: Collection<CatalogApp>,
) {
    val apps: List<CatalogApp> = immutableList(apps)
}

class AppCatalogSnapshot(
    val status: AppCatalogStatus,
    profiles: Collection<CatalogProfile>,
    val errorCode: String? = null,
) {
    val profiles: List<CatalogProfile> = immutableList(profiles)

    init {
        require((status == AppCatalogStatus.ERROR) == (errorCode != null)) {
            "Only an error catalog snapshot may carry an error code"
        }
        require(errorCode == null || errorCode.isNotBlank()) {
            "App catalog error code must be null or nonblank"
        }
    }

    companion object {
        val Loading: AppCatalogSnapshot = AppCatalogSnapshot(AppCatalogStatus.LOADING, emptyList())
    }
}

data class AppCatalogOverride(
    val identity: AppActivityIdentity,
    val customLabel: String? = null,
    val icon: AppIcon? = null,
    val favorite: Boolean = false,
    val collectionVisible: Boolean = true,
) {
    init {
        require(customLabel == null || customLabel.isNotBlank()) {
            "Custom app label must be null or nonblank"
        }
    }
}

fun interface AppCatalogOverrideSource {
    suspend fun read(): List<AppCatalogOverride>
}

interface AppCatalog : AutoCloseable {
    val state: StateFlow<AppCatalogSnapshot>

    suspend fun refresh()

    suspend fun launch(identity: AppActivityIdentity): AppLaunchResult
}

/** Owns catalog policy while delegating Android discovery and launching to [AppPlatform]. */
class DefaultAppCatalog(
    private val platform: AppPlatform,
    private val overrideSource: AppCatalogOverrideSource,
    parentScope: CoroutineScope,
) : AppCatalog {
    private val closed = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow(AppCatalogSnapshot.Loading)

    override val state: StateFlow<AppCatalogSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            platform.invalidations.collect {
                refresh()
            }
        }
    }

    override suspend fun refresh() {
        check(!closed.get()) { "App catalog is closed" }
        refreshMutex.withLock {
            val platformSnapshot = try {
                platform.snapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                publishError(mutableState.value.profiles, REFRESH_FAILED)
                return@withLock
            }
            val overrides = try {
                overrideSource.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                val recoverySnapshot = buildSnapshot(
                    platformSnapshot,
                    emptyList(),
                    ordinaryCollectionsAvailable = false,
                )
                publishError(recoverySnapshot.profiles, OVERRIDES_UNAVAILABLE)
                return@withLock
            }
            try {
                mutableState.value = buildSnapshot(platformSnapshot, overrides)
            } catch (_: RuntimeException) {
                publishError(mutableState.value.profiles, REFRESH_FAILED)
            }
        }
    }

    override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult {
        if (closed.get()) return AppLaunchResult.Closed
        val snapshot = mutableState.value
        val profile = snapshot.profiles.firstOrNull { it.serial == identity.profile }
            ?: return AppLaunchResult.ActivityUnavailable
        if (!profile.available) return AppLaunchResult.ProfileLocked
        if (profile.apps.none { it.identity == identity }) return AppLaunchResult.ActivityUnavailable
        return platform.launch(identity)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        platform.close()
    }

    private fun publishError(profiles: Collection<CatalogProfile>, code: String) {
        mutableState.value = AppCatalogSnapshot(
            status = AppCatalogStatus.ERROR,
            profiles = profiles,
            errorCode = code,
        )
    }

    private fun buildSnapshot(
        source: AppPlatformSnapshot,
        overrideValues: Collection<AppCatalogOverride>,
        ordinaryCollectionsAvailable: Boolean = true,
    ): AppCatalogSnapshot {
        val overrides = overrideValues.associateBy(AppCatalogOverride::identity)
        require(overrides.size == overrideValues.size) {
            "App catalog overrides must have unique activity identities"
        }
        val activitiesByProfile = source.activities
            .map { activity ->
                val override = overrides[activity.identity]
                CatalogApp(
                    identity = activity.identity,
                    label = override?.customLabel ?: activity.label,
                    icon = override?.icon ?: activity.icon,
                    favorite = override?.favorite == true,
                    collectionVisible = ordinaryCollectionsAvailable &&
                        override?.collectionVisible != false,
                )
            }
            .groupBy { it.identity.profile }
        val profiles = source.profiles
            .sortedWith(compareBy({ it.kind.ordinal }, { it.serial.value }))
            .map { profile ->
                CatalogProfile(
                    serial = profile.serial,
                    kind = profile.kind,
                    available = !profile.quiet && profile.unlocked,
                    badgeText = if (profile.kind == AppProfileKind.WORK) WORK_BADGE else null,
                    apps = activitiesByProfile[profile.serial].orEmpty().sortedWith(APP_ORDER),
                )
            }
        return AppCatalogSnapshot(AppCatalogStatus.READY, profiles)
    }

    private companion object {
        const val WORK_BADGE = "Work"
        const val REFRESH_FAILED = "app_catalog_refresh_failed"
        const val OVERRIDES_UNAVAILABLE = "app_catalog_overrides_unavailable"

        val APP_ORDER = compareBy<CatalogApp>(
            { it.label.lowercase(Locale.ROOT) },
            { it.label },
            { it.identity.packageName.value },
            { it.identity.activityName.value },
            { it.identity.profile.value },
        )
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
