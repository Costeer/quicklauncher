package org.quicklauncher.host.platform.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.widgets.AllocatedWidgetBinding
import org.quicklauncher.host.runtime.widgets.WidgetAllocatedIdsResult
import org.quicklauncher.host.runtime.widgets.WidgetAllocationResult
import org.quicklauncher.host.runtime.widgets.WidgetBindResult
import org.quicklauncher.host.runtime.widgets.WidgetConfigurationResult
import org.quicklauncher.host.runtime.widgets.WidgetDeletionResult
import org.quicklauncher.host.runtime.widgets.WidgetPlatform
import org.quicklauncher.host.runtime.widgets.WidgetProviderIdentity
import org.quicklauncher.host.runtime.widgets.WidgetProviderDescriptor
import org.quicklauncher.host.runtime.widgets.WidgetProviderDiscoveryResult
import org.quicklauncher.host.runtime.widgets.WidgetRenderToken
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetSurfaceResult
import org.quicklauncher.host.runtime.widgets.WidgetUpdateResult
import org.quicklauncher.host.runtime.widgets.WidgetUserAction
import org.quicklauncher.host.runtime.widgets.WidgetValidationResult

/** Production widget adapter. Framework IDs, provider objects, views, and options stay internal. */
class AndroidWidgetPlatform internal constructor(
    private val backend: WidgetFrameworkBackend,
    private val dispatcher: CoroutineDispatcher,
) : WidgetPlatform {
    private val closed = AtomicBoolean(false)
    private val surfaces = linkedMapOf<WidgetRenderToken, Long>()
    private var nextSurfaceToken = 1L

    constructor(
        context: android.content.Context,
        dispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) : this(FrameworkWidgetBackend(context.applicationContext), dispatcher)

    override suspend fun discoverProviders(profile: ProfileSerial): WidgetProviderDiscoveryResult = onBackend(
        closedResult = WidgetProviderDiscoveryResult.Failed(PLATFORM_CLOSED),
        failure = { WidgetProviderDiscoveryResult.Failed(DISCOVERY_FAILED) },
    ) {
        if (!backend.hasProfile(profile)) return@onBackend WidgetProviderDiscoveryResult.ProfileUnavailable
        WidgetProviderDiscoveryResult.Available(
            backend.providers(profile)
                .sortedWith(compareBy({ it.label.lowercase() }, { it.identity.className }))
                .map {
                    WidgetProviderDescriptor(
                        identity = it.identity,
                        label = it.label,
                        minimumSize = it.minimumSize,
                        configurationRequired = it.configurationRequired,
                    )
                },
        )
    }

    override suspend fun allocate(): WidgetAllocationResult = onBackend(
        closedResult = WidgetAllocationResult.Closed,
        failure = { WidgetAllocationResult.Failed(ALLOCATION_FAILED) },
    ) {
        WidgetAllocationResult.Allocated(backend.allocate())
    }

    override suspend fun bind(request: AllocatedWidgetBinding): WidgetBindResult = onBackend(
        closedResult = WidgetBindResult.Failed(PLATFORM_CLOSED),
        failure = { WidgetBindResult.Failed(BIND_FAILED) },
    ) {
        if (!backend.hasProfile(request.provider.profile)) return@onBackend WidgetBindResult.ProfileUnavailable
        if (!backend.hasProvider(request.provider)) return@onBackend WidgetBindResult.ProviderUnavailable
        val binding = WidgetBackendBinding(
            request.appWidgetId,
            request.provider,
            request.size,
        )
        if (backend.bind(binding)) {
            WidgetBindResult.Bound
        } else {
            WidgetBindResult.PermissionRequired(
                WidgetUserAction.BindPermission(
                    request.appWidgetId,
                    request.provider,
                    request.size,
                ),
            )
        }
    }

    override suspend fun validateBinding(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
    ): WidgetValidationResult = onBackend(
        closedResult = WidgetValidationResult.Failed(PLATFORM_CLOSED),
        failure = { WidgetValidationResult.Failed(VALIDATION_FAILED) },
    ) {
        when (backend.bindingState(appWidgetId, expectedProvider)) {
            WidgetBackendBindingState.VALID -> WidgetValidationResult.Valid
            WidgetBackendBindingState.NOT_BOUND -> WidgetValidationResult.NotBound
            WidgetBackendBindingState.PROVIDER_UNAVAILABLE -> WidgetValidationResult.ProviderUnavailable
            WidgetBackendBindingState.PROFILE_UNAVAILABLE -> WidgetValidationResult.ProfileUnavailable
        }
    }

    override suspend fun configuration(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
    ): WidgetConfigurationResult = onBackend(
        closedResult = WidgetConfigurationResult.Failed(PLATFORM_CLOSED),
        failure = { WidgetConfigurationResult.Failed(CONFIGURATION_FAILED) },
    ) {
        when (backend.bindingState(appWidgetId, expectedProvider)) {
            WidgetBackendBindingState.NOT_BOUND -> WidgetConfigurationResult.Failed(NOT_BOUND)
            WidgetBackendBindingState.PROVIDER_UNAVAILABLE -> WidgetConfigurationResult.ProviderUnavailable
            WidgetBackendBindingState.PROFILE_UNAVAILABLE -> WidgetConfigurationResult.ProfileUnavailable
            WidgetBackendBindingState.VALID -> if (backend.configurationRequired(appWidgetId)) {
                WidgetConfigurationResult.Required(WidgetUserAction.Configure(appWidgetId))
            } else {
                WidgetConfigurationResult.NotRequired
            }
        }
    }

    override suspend fun createSurface(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
        size: WidgetSize,
    ): WidgetSurfaceResult = onBackend(
        closedResult = WidgetSurfaceResult.Failed(PLATFORM_CLOSED),
        failure = { WidgetSurfaceResult.Failed(SURFACE_FAILED) },
    ) {
        when (backend.bindingState(appWidgetId, expectedProvider)) {
            WidgetBackendBindingState.NOT_BOUND -> WidgetSurfaceResult.NotBound
            WidgetBackendBindingState.PROVIDER_UNAVAILABLE -> WidgetSurfaceResult.ProviderUnavailable
            WidgetBackendBindingState.PROFILE_UNAVAILABLE -> WidgetSurfaceResult.ProfileUnavailable
            WidgetBackendBindingState.VALID -> {
                val surfaceId = backend.createSurface(appWidgetId, size)
                val token = WidgetRenderToken.of("widget-surface-${nextSurfaceToken++}")
                surfaces[token] = surfaceId
                WidgetSurfaceResult.Ready(token)
            }
        }
    }

    override suspend fun releaseSurface(token: WidgetRenderToken) {
        val surfaceId = surfaces.remove(token) ?: return
        withContext(dispatcher) { backend.releaseSurface(surfaceId) }
    }

    override suspend fun updateSize(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
        size: WidgetSize,
    ): WidgetUpdateResult = onBackend(
        closedResult = WidgetUpdateResult.Failed(PLATFORM_CLOSED),
        failure = { WidgetUpdateResult.Failed(UPDATE_FAILED) },
    ) {
        when (backend.bindingState(appWidgetId, expectedProvider)) {
            WidgetBackendBindingState.NOT_BOUND -> WidgetUpdateResult.NotBound
            WidgetBackendBindingState.PROVIDER_UNAVAILABLE -> WidgetUpdateResult.ProviderUnavailable
            WidgetBackendBindingState.PROFILE_UNAVAILABLE -> WidgetUpdateResult.ProfileUnavailable
            WidgetBackendBindingState.VALID -> {
                backend.updateSize(WidgetBackendSizeUpdate(appWidgetId, size))
                WidgetUpdateResult.Updated
            }
        }
    }

    override suspend fun delete(appWidgetId: Int): WidgetDeletionResult = onBackend(
        closedResult = WidgetDeletionResult.RetryRequired(PLATFORM_CLOSED),
        failure = { WidgetDeletionResult.RetryRequired(DELETE_FAILED) },
    ) {
        if (backend.delete(appWidgetId)) {
            WidgetDeletionResult.Deleted
        } else {
            WidgetDeletionResult.AlreadyDeleted
        }
    }

    override suspend fun allocatedIds(): WidgetAllocatedIdsResult = onBackend(
        closedResult = WidgetAllocatedIdsResult.Closed,
        failure = { WidgetAllocatedIdsResult.Failed(ENUMERATION_FAILED) },
    ) {
        WidgetAllocatedIdsResult.Available(backend.allocatedIds())
    }

    /** Reports token ownership without exposing the retained framework view. */
    fun ownsSurface(token: WidgetRenderToken): Boolean = surfaces.containsKey(token)

    /** Attaches the platform-owned host view without returning it across the adapter seam. */
    @Composable
    fun RenderSurface(token: WidgetRenderToken, modifier: Modifier = Modifier) {
        val surfaceId = surfaces[token] ?: return
        val view = backend.surfaceView(surfaceId) ?: return
        AndroidView(factory = { view }, modifier = modifier)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val retained = surfaces.values.toList()
        surfaces.clear()
        retained.forEach { surfaceId ->
            try {
                backend.releaseSurface(surfaceId)
            } catch (_: RuntimeException) {
                // One broken provider view must not retain its neighbors or the host listener.
            }
        }
        try {
            backend.close()
        } catch (_: RuntimeException) {
            // Close is idempotent and best effort during application teardown.
        }
    }

    private suspend fun <T> onBackend(
        closedResult: T,
        failure: (RuntimeException) -> T,
        block: () -> T,
    ): T = withContext(dispatcher) {
        if (closed.get()) return@withContext closedResult
        try {
            block()
        } catch (error: SecurityException) {
            failure(error)
        } catch (error: IllegalArgumentException) {
            failure(error)
        } catch (error: IllegalStateException) {
            failure(error)
        }
    }

    private companion object {
        const val ALLOCATION_FAILED = "widget_allocation_failed"
        const val BIND_FAILED = "widget_bind_failed"
        const val CONFIGURATION_FAILED = "widget_configuration_failed"
        const val DELETE_FAILED = "widget_delete_failed"
        const val DISCOVERY_FAILED = "widget_discovery_failed"
        const val ENUMERATION_FAILED = "widget_id_enumeration_failed"
        const val NOT_BOUND = "widget_not_bound"
        const val PLATFORM_CLOSED = "widget_platform_closed"
        const val SURFACE_FAILED = "widget_surface_failed"
        const val UPDATE_FAILED = "widget_update_failed"
        const val VALIDATION_FAILED = "widget_validation_failed"
    }
}

internal data class WidgetBackendBinding(
    val appWidgetId: Int,
    val provider: WidgetProviderIdentity,
    val size: WidgetSize,
)

internal data class WidgetBackendSizeUpdate(
    val appWidgetId: Int,
    val size: WidgetSize,
)

internal data class WidgetBackendProvider(
    val identity: WidgetProviderIdentity,
    val label: String,
    val minimumSize: WidgetSize,
    val configurationRequired: Boolean,
)

internal enum class WidgetBackendBindingState {
    VALID,
    NOT_BOUND,
    PROVIDER_UNAVAILABLE,
    PROFILE_UNAVAILABLE,
}

internal interface WidgetFrameworkBackend : AutoCloseable {
    fun allocate(): Int

    fun hasProfile(profile: ProfileSerial): Boolean

    fun providers(profile: ProfileSerial): List<WidgetBackendProvider>

    fun hasProvider(provider: WidgetProviderIdentity): Boolean

    fun bind(binding: WidgetBackendBinding): Boolean

    fun bindingState(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
    ): WidgetBackendBindingState

    fun configurationRequired(appWidgetId: Int): Boolean

    fun createSurface(appWidgetId: Int, size: WidgetSize): Long

    fun surfaceView(surfaceId: Long): android.view.View? = null

    fun releaseSurface(surfaceId: Long)

    fun updateSize(update: WidgetBackendSizeUpdate)

    /** Returns false when the framework ID was already absent. */
    fun delete(appWidgetId: Int): Boolean

    fun allocatedIds(): Set<Int>
}
