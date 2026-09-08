package org.quicklauncher.host.runtime.catalog

import java.util.Collections
import kotlinx.coroutines.flow.Flow
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ProfileSerial

/** Android-free launcher activity image bytes prepared by the platform adapter. */
class AppIcon private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is AppIcon && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "AppIcon(${value.size} bytes)"

    companion object {
        fun of(bytes: ByteArray): AppIcon {
            require(bytes.isNotEmpty()) { "App icon bytes must not be empty" }
            return AppIcon(bytes)
        }
    }
}

enum class AppProfileKind {
    PERSONAL,
    WORK,
}

data class AppPlatformProfile(
    val serial: ProfileSerial,
    val kind: AppProfileKind,
    val quiet: Boolean,
    val unlocked: Boolean,
)

data class AppPlatformActivity(
    val identity: AppActivityIdentity,
    val label: String,
    val icon: AppIcon,
) {
    init {
        require(label.isNotBlank()) { "App activity label must not be blank" }
    }
}

class AppPlatformSnapshot(
    profiles: Collection<AppPlatformProfile>,
    activities: Collection<AppPlatformActivity>,
) {
    val profiles: List<AppPlatformProfile>
    val activities: List<AppPlatformActivity>

    init {
        require(profiles.map(AppPlatformProfile::serial).distinct().size == profiles.size) {
            "App platform profiles must have unique serials"
        }
        require(activities.map(AppPlatformActivity::identity).distinct().size == activities.size) {
            "App platform activities must have unique identities"
        }
        val profileBySerial = profiles.associateBy(AppPlatformProfile::serial)
        activities.forEach { activity ->
            val profile = requireNotNull(profileBySerial[activity.identity.profile]) {
                "App activity ${activity.identity} refers to an absent profile"
            }
            require(!profile.quiet && profile.unlocked) {
                "Unavailable profile ${profile.serial} must not expose app activity data"
            }
        }
        this.profiles = immutableList(profiles)
        this.activities = immutableList(activities)
    }
}

sealed interface AppPlatformInvalidation {
    data object Changed : AppPlatformInvalidation
}

sealed interface AppLaunchResult {
    data object Launched : AppLaunchResult
    data object MissingProfile : AppLaunchResult
    data object ProfileLocked : AppLaunchResult
    data object ActivityUnavailable : AppLaunchResult
    data object SecurityDenied : AppLaunchResult
    data object Closed : AppLaunchResult

    data class Failed(val code: String) : AppLaunchResult {
        init {
            require(code.isNotBlank()) { "App launch failure code must not be blank" }
        }
    }
}

/** Platform seam for complete launcher snapshots, invalidation, and profile-aware launch. */
interface AppPlatform : AutoCloseable {
    val invalidations: Flow<AppPlatformInvalidation>

    suspend fun snapshot(): AppPlatformSnapshot

    suspend fun launch(identity: AppActivityIdentity): AppLaunchResult
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
