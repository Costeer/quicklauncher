package org.quicklauncher.prototypes.platformprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeDecisionsTest {
    @Test
    fun homeCategoryWinsOverLauncherCategory() {
        assertEquals(
            EntryKind.HOME,
            classifyEntry(ACTION_MAIN, setOf(CATEGORY_HOME, CATEGORY_LAUNCHER)),
        )
    }

    @Test
    fun appIconEntryIsNotCountedAsHome() {
        assertEquals(
            EntryKind.APP_ICON,
            classifyEntry(ACTION_MAIN, setOf(CATEGORY_LAUNCHER)),
        )
    }

    @Test
    fun exportedRouteWithUnheldPermissionIsRejected() {
        assertEquals(
            RouteAvailability.PERMISSION_DENIED,
            decideRoute(
                RouteFacts(
                    resolvedPackage = "com.android.settings",
                    expectedPackage = "com.android.settings",
                    exported = true,
                    requiredPermission = "android.permission.MANAGE_SAFETY_CENTER",
                    requiredPermissionHeld = false,
                ),
            ),
        )
    }

    @Test
    fun routeResolvedByUnexpectedPackageIsRejected() {
        assertEquals(
            RouteAvailability.WRONG_PACKAGE,
            decideRoute(
                RouteFacts(
                    resolvedPackage = "example.interloper",
                    expectedPackage = "com.android.settings",
                    exported = true,
                    requiredPermission = null,
                    requiredPermissionHeld = true,
                ),
            ),
        )
    }

    @Test
    fun publicExportedRouteIsCallable() {
        assertEquals(
            RouteAvailability.CALLABLE,
            decideRoute(
                RouteFacts(
                    resolvedPackage = "com.android.settings",
                    expectedPackage = null,
                    exported = true,
                    requiredPermission = null,
                    requiredPermissionHeld = true,
                ),
            ),
        )
    }

    @Test
    fun privateVisibilityNeedsHomeBeforePermission() {
        assertEquals(
            HiddenProfilePrerequisite.HOME_ROLE_NOT_HELD,
            hiddenProfilePrerequisite(
                homeRoleAvailable = true,
                homeRoleHeld = false,
                accessHiddenProfilesGranted = false,
            ),
        )
    }

    @Test
    fun lockedPrivateProfileNeverUsesGeneralCatalog() {
        assertEquals(
            ProfileCatalogPolicy.PRIVATE_CONTAINER_LOCKED,
            profileCatalogPolicy(PRIVATE_PROFILE_TYPE, quietMode = true),
        )
    }

    @Test
    fun unlockedPrivateProfileStillUsesPrivateContainer() {
        assertEquals(
            ProfileCatalogPolicy.PRIVATE_CONTAINER_UNLOCKED,
            profileCatalogPolicy(PRIVATE_PROFILE_TYPE, quietMode = false),
        )
    }
}

