package org.quicklauncher.host.runtime.widgets

import java.util.Collections
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial

data class WidgetProviderIdentity(
    val profile: ProfileSerial,
    val packageName: PackageName,
    val className: String,
) {
    init {
        require(className.isNotBlank()) { "Widget provider class name must not be blank" }
    }
}

/** Sanitized provider metadata. Framework provider objects and icons remain in :host:platform. */
data class WidgetProviderDescriptor(
    val identity: WidgetProviderIdentity,
    val label: String,
    val minimumSize: WidgetSize,
    val configurationRequired: Boolean,
) {
    init {
        require(label.isNotBlank()) { "Widget provider label must not be blank" }
    }
}

data class WidgetSize(
    val widthDp: Int,
    val heightDp: Int,
) {
    init {
        require(widthDp > 0 && heightDp > 0) { "Widget size must be positive" }
    }
}

data class WidgetBindingRequest(
    val moduleInstanceId: ModuleInstanceId,
    val provider: WidgetProviderIdentity,
    val size: WidgetSize,
)

data class AllocatedWidgetBinding(
    val appWidgetId: Int,
    val provider: WidgetProviderIdentity,
    val size: WidgetSize,
) {
    init {
        require(appWidgetId >= 0) { "Widget ID must not be negative" }
    }
}

@JvmInline
value class WidgetRenderToken private constructor(val value: String) {
    companion object {
        fun of(value: String): WidgetRenderToken {
            require(value.isNotBlank()) { "Widget render token must not be blank" }
            return WidgetRenderToken(value)
        }
    }
}

sealed interface WidgetAllocationResult {
    data class Allocated(val appWidgetId: Int) : WidgetAllocationResult {
        init {
            require(appWidgetId >= 0) { "Widget ID must not be negative" }
        }
    }

    data class Failed(val code: String) : WidgetAllocationResult
    data object Closed : WidgetAllocationResult
}

sealed interface WidgetAllocatedIdsResult {
    class Available(appWidgetIds: Collection<Int>) : WidgetAllocatedIdsResult {
        val appWidgetIds: Set<Int> =
            Collections.unmodifiableSet(LinkedHashSet(appWidgetIds))

        init {
            require(appWidgetIds.all { it >= 0 }) { "Widget IDs must not be negative" }
        }

        override fun equals(other: Any?): Boolean =
            other is Available && appWidgetIds == other.appWidgetIds

        override fun hashCode(): Int = appWidgetIds.hashCode()

        override fun toString(): String = "Available(appWidgetIds=$appWidgetIds)"
    }

    data class Failed(val code: String) : WidgetAllocatedIdsResult
    data object Closed : WidgetAllocatedIdsResult
}

sealed interface WidgetProviderDiscoveryResult {
    class Available(providers: Collection<WidgetProviderDescriptor>) : WidgetProviderDiscoveryResult {
        val providers: List<WidgetProviderDescriptor> =
            Collections.unmodifiableList(ArrayList(providers))

        override fun equals(other: Any?): Boolean =
            other is Available && providers == other.providers

        override fun hashCode(): Int = providers.hashCode()

        override fun toString(): String = "Available(providers=$providers)"
    }
    data object ProfileUnavailable : WidgetProviderDiscoveryResult
    data class Failed(val code: String) : WidgetProviderDiscoveryResult
}

sealed interface WidgetBindResult {
    data object Bound : WidgetBindResult
    data class PermissionRequired(val action: WidgetUserAction.BindPermission) : WidgetBindResult
    data object ProfileUnavailable : WidgetBindResult
    data object ProviderUnavailable : WidgetBindResult
    data class Failed(val code: String) : WidgetBindResult
}

sealed interface WidgetValidationResult {
    data object Valid : WidgetValidationResult
    data object NotBound : WidgetValidationResult
    data object ProviderUnavailable : WidgetValidationResult
    data object ProfileUnavailable : WidgetValidationResult
    data class Failed(val code: String) : WidgetValidationResult
}

sealed interface WidgetConfigurationResult {
    data object NotRequired : WidgetConfigurationResult
    data class Required(val action: WidgetUserAction.Configure) : WidgetConfigurationResult
    data object ProviderUnavailable : WidgetConfigurationResult
    data object ProfileUnavailable : WidgetConfigurationResult
    data class Failed(val code: String) : WidgetConfigurationResult
}

/** A system-owned activity request with no Intent, ComponentName, or UserHandle. */
sealed interface WidgetUserAction {
    val appWidgetId: Int

    data class BindPermission(
        override val appWidgetId: Int,
        val provider: WidgetProviderIdentity,
        val size: WidgetSize,
    ) : WidgetUserAction

    data class Configure(override val appWidgetId: Int) : WidgetUserAction
}

sealed interface WidgetActionLaunchResult {
    data object Launched : WidgetActionLaunchResult
    data object Busy : WidgetActionLaunchResult
    data object ProfileUnavailable : WidgetActionLaunchResult
    data object ProviderUnavailable : WidgetActionLaunchResult
    data class Failed(val code: String) : WidgetActionLaunchResult
}

data class WidgetUserActionResult(
    val action: WidgetUserAction,
    val accepted: Boolean,
)

interface WidgetUserActionLauncher {
    fun launch(action: WidgetUserAction): WidgetActionLaunchResult
}

sealed interface WidgetSurfaceResult {
    data class Ready(val token: WidgetRenderToken) : WidgetSurfaceResult
    data object NotBound : WidgetSurfaceResult
    data object ProviderUnavailable : WidgetSurfaceResult
    data object ProfileUnavailable : WidgetSurfaceResult
    data class Failed(val code: String) : WidgetSurfaceResult
}

sealed interface WidgetUpdateResult {
    data object Updated : WidgetUpdateResult
    data object NotBound : WidgetUpdateResult
    data object ProviderUnavailable : WidgetUpdateResult
    data object ProfileUnavailable : WidgetUpdateResult
    data class Failed(val code: String) : WidgetUpdateResult
}

sealed interface WidgetDeletionResult {
    data object Deleted : WidgetDeletionResult
    data object AlreadyDeleted : WidgetDeletionResult
    data class RetryRequired(val code: String) : WidgetDeletionResult
}

/** Android-free seam for the framework widget host. Every returned token is opaque to callers. */
interface WidgetPlatform : AutoCloseable {
    suspend fun discoverProviders(profile: ProfileSerial): WidgetProviderDiscoveryResult

    suspend fun allocate(): WidgetAllocationResult

    suspend fun bind(request: AllocatedWidgetBinding): WidgetBindResult

    suspend fun validateBinding(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
    ): WidgetValidationResult

    suspend fun configuration(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
    ): WidgetConfigurationResult

    suspend fun createSurface(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
        size: WidgetSize,
    ): WidgetSurfaceResult

    suspend fun releaseSurface(token: WidgetRenderToken)

    suspend fun updateSize(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
        size: WidgetSize,
    ): WidgetUpdateResult

    suspend fun delete(appWidgetId: Int): WidgetDeletionResult

    suspend fun allocatedIds(): WidgetAllocatedIdsResult
}
