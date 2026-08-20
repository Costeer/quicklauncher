package org.quicklauncher.prototypes.platformprobe

internal const val ACTION_MAIN = "android.intent.action.MAIN"
internal const val CATEGORY_HOME = "android.intent.category.HOME"
internal const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
internal const val PRIVATE_PROFILE_TYPE = "android.os.usertype.profile.PRIVATE"

internal enum class EntryKind {
    HOME,
    APP_ICON,
    OTHER,
}

internal fun classifyEntry(action: String?, categories: Set<String>): EntryKind = when {
    action != ACTION_MAIN -> EntryKind.OTHER
    CATEGORY_HOME in categories -> EntryKind.HOME
    CATEGORY_LAUNCHER in categories -> EntryKind.APP_ICON
    else -> EntryKind.OTHER
}

internal enum class RouteAvailability {
    UNRESOLVED,
    WRONG_PACKAGE,
    NOT_EXPORTED,
    PERMISSION_DENIED,
    CALLABLE,
}

internal data class RouteFacts(
    val resolvedPackage: String?,
    val expectedPackage: String?,
    val exported: Boolean,
    val requiredPermission: String?,
    val requiredPermissionHeld: Boolean,
)

internal fun decideRoute(facts: RouteFacts): RouteAvailability = when {
    facts.resolvedPackage == null -> RouteAvailability.UNRESOLVED
    facts.expectedPackage != null && facts.resolvedPackage != facts.expectedPackage -> {
        RouteAvailability.WRONG_PACKAGE
    }
    !facts.exported -> RouteAvailability.NOT_EXPORTED
    facts.requiredPermission != null && !facts.requiredPermissionHeld -> {
        RouteAvailability.PERMISSION_DENIED
    }
    else -> RouteAvailability.CALLABLE
}

internal enum class HiddenProfilePrerequisite {
    HOME_ROLE_UNAVAILABLE,
    HOME_ROLE_NOT_HELD,
    PERMISSION_NOT_GRANTED,
    READY,
}

internal fun hiddenProfilePrerequisite(
    homeRoleAvailable: Boolean,
    homeRoleHeld: Boolean,
    accessHiddenProfilesGranted: Boolean,
): HiddenProfilePrerequisite = when {
    !homeRoleAvailable -> HiddenProfilePrerequisite.HOME_ROLE_UNAVAILABLE
    !homeRoleHeld -> HiddenProfilePrerequisite.HOME_ROLE_NOT_HELD
    !accessHiddenProfilesGranted -> HiddenProfilePrerequisite.PERMISSION_NOT_GRANTED
    else -> HiddenProfilePrerequisite.READY
}

internal enum class ProfileCatalogPolicy {
    GENERAL_CATALOG,
    PRIVATE_CONTAINER_LOCKED,
    PRIVATE_CONTAINER_UNLOCKED,
}

internal fun profileCatalogPolicy(userType: String, quietMode: Boolean): ProfileCatalogPolicy = when {
    userType != PRIVATE_PROFILE_TYPE -> ProfileCatalogPolicy.GENERAL_CATALOG
    quietMode -> ProfileCatalogPolicy.PRIVATE_CONTAINER_LOCKED
    else -> ProfileCatalogPolicy.PRIVATE_CONTAINER_UNLOCKED
}

