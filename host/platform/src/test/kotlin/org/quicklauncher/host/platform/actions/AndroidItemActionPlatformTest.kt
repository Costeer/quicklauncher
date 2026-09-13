package org.quicklauncher.host.platform.actions

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.actions.ItemPlatformResult
import org.quicklauncher.host.runtime.actions.ItemActionEligibility
import org.quicklauncher.host.runtime.actions.ItemActionPermission
import org.quicklauncher.host.runtime.actions.ProfileItemAction

class AndroidItemActionPlatformTest {
    @Test
    fun `framework eligibility allows uninstall only for removable unrestricted apps`() {
        val removable = eligibilityFacts(systemApp = false)

        assertEquals(
            BackendItemActionEligibility(
                BackendItemPermission.ALLOWED,
                BackendItemPermission.DENIED,
            ),
            evaluateFrameworkEligibility(removable),
        )
        assertEquals(
            BackendItemPermission.DENIED,
            evaluateFrameworkEligibility(removable.copy(uninstallRestricted = true)).uninstall,
        )
        assertEquals(
            BackendItemPermission.DENIED,
            evaluateFrameworkEligibility(removable.copy(uninstallBlockedByDevicePolicy = true)).uninstall,
        )
        assertEquals(
            BackendItemActionEligibility.None,
            evaluateFrameworkEligibility(removable.copy(devicePolicyKnown = false)),
        )
    }

    @Test
    fun `framework eligibility allows disable only for controllable system apps`() {
        val systemApp = eligibilityFacts(systemApp = true)

        assertEquals(BackendItemPermission.ALLOWED, evaluateFrameworkEligibility(systemApp).disable)
        assertEquals(
            BackendItemPermission.DENIED,
            evaluateFrameworkEligibility(systemApp.copy(appsControlRestricted = true)).disable,
        )
        assertEquals(
            BackendItemPermission.DENIED,
            evaluateFrameworkEligibility(systemApp.copy(deviceAdminOrOwner = true)).disable,
        )
        assertEquals(
            BackendItemActionEligibility.None,
            evaluateFrameworkEligibility(systemApp.copy(packageAndActivityAvailable = false)),
        )
    }

    @Test
    fun `eligibility returns only permissions proven by current framework state`() = runTest {
        val backend = FakeItemActionBackend().apply {
            eligibility = BackendItemActionEligibility(
                uninstall = BackendItemPermission.ALLOWED,
                disable = BackendItemPermission.DENIED,
            )
        }
        val platform = AndroidItemActionPlatform(backend)

        assertEquals(
            ItemActionEligibility(
                uninstall = ItemActionPermission.ALLOWED,
                disable = ItemActionPermission.DENIED,
            ),
            platform.eligibility(identity()),
        )
    }

    @Test
    fun `destructive routes revalidate eligibility immediately before launch`() = runTest {
        val backend = FakeItemActionBackend().apply {
            eligibility = BackendItemActionEligibility(
                uninstall = BackendItemPermission.DENIED,
                disable = BackendItemPermission.ALLOWED,
            )
        }
        val platform = AndroidItemActionPlatform(backend)

        assertEquals(ItemPlatformResult.Denied, platform.uninstall(identity()))
        assertEquals(ItemPlatformResult.Completed, platform.disable(identity()))
        assertEquals(0, backend.uninstallRoutes)
        assertEquals(1, backend.disableRoutes)
    }

    @Test
    fun `every route revalidates the current profile and package`() = runTest {
        val backend = FakeItemActionBackend()
        val platform = AndroidItemActionPlatform(backend)

        assertEquals(ItemPlatformResult.Completed, platform.openDetails(identity()))
        backend.available = false
        assertEquals(ItemPlatformResult.Unavailable, platform.uninstall(identity()))

        assertEquals(2, backend.resolutions)
        assertEquals(0, backend.uninstallRoutes)
    }

    @Test
    fun `profile actions accept only a resolved managed profile`() = runTest {
        val backend = FakeItemActionBackend()
        val platform = AndroidItemActionPlatform(backend)

        assertEquals(
            ItemPlatformResult.Completed,
            platform.changeProfileMode(ProfileSerial.of(10), ProfileItemAction.PAUSE),
        )
        assertEquals(listOf(ProfileSerial.of(10) to true), backend.quietRequests)
        backend.managed = false
        assertEquals(
            ItemPlatformResult.Unavailable,
            platform.changeProfileMode(ProfileSerial.of(10), ProfileItemAction.RESUME),
        )
    }

    @Test
    fun `security denial and route failure become typed results`() = runTest {
        val backend = FakeItemActionBackend()
        val platform = AndroidItemActionPlatform(backend)
        backend.routeResult = BackendItemRouteResult.DENIED
        assertEquals(ItemPlatformResult.Denied, platform.disable(identity()))
        backend.failure = IllegalStateException("settings disappeared")
        assertEquals(ItemPlatformResult.Failed, platform.openDetails(identity()))
        assertEquals(
            ItemPlatformResult.Failed,
            platform.changeProfileMode(ProfileSerial.of(10), ProfileItemAction.PAUSE),
        )
    }

    private fun identity() = AppActivityIdentity(
        ProfileSerial.of(10),
        PackageName.parse("org.example.mail"),
        ActivityName.parse("org.example.mail.MainActivity"),
    )

    private fun eligibilityFacts(systemApp: Boolean) = FrameworkItemEligibilityFacts(
        packageAndActivityAvailable = true,
        devicePolicyKnown = true,
        uninstallRestricted = false,
        appsControlRestricted = false,
        uninstallBlockedByDevicePolicy = false,
        deviceAdminOrOwner = false,
        systemApp = systemApp,
        updatedSystemApp = false,
        uninstallHandlerAvailable = true,
    )

    private class FakeItemActionBackend : ItemActionBackend {
        var available = true
        var managed = true
        var routeResult = BackendItemRouteResult.COMPLETED
        var failure: RuntimeException? = null
        var resolutions = 0
        var uninstallRoutes = 0
        var disableRoutes = 0
        var eligibility = BackendItemActionEligibility(
            BackendItemPermission.ALLOWED,
            BackendItemPermission.ALLOWED,
        )
        val quietRequests = mutableListOf<Pair<ProfileSerial, Boolean>>()

        override fun resolve(identity: AppActivityIdentity): Boolean {
            resolutions++
            failure?.let { throw it }
            return available
        }

        override fun eligibility(identity: AppActivityIdentity): BackendItemActionEligibility = eligibility

        override fun openDetails(identity: AppActivityIdentity): BackendItemRouteResult = routeResult

        override fun requestUninstall(identity: AppActivityIdentity): BackendItemRouteResult {
            uninstallRoutes++
            return routeResult
        }

        override fun openDisableSettings(identity: AppActivityIdentity): BackendItemRouteResult {
            disableRoutes++
            return routeResult
        }

        override fun isManagedProfile(profile: ProfileSerial): Boolean {
            failure?.let { throw it }
            return managed
        }

        override fun requestQuietMode(profile: ProfileSerial, quiet: Boolean): BackendItemRouteResult {
            quietRequests += profile to quiet
            return routeResult
        }
    }
}
