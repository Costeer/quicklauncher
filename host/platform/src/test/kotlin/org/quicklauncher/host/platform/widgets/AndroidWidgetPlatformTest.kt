package org.quicklauncher.host.platform.widgets

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.widgets.AllocatedWidgetBinding
import org.quicklauncher.host.runtime.widgets.WidgetAllocatedIdsResult
import org.quicklauncher.host.runtime.widgets.WidgetBindResult
import org.quicklauncher.host.runtime.widgets.WidgetConfigurationResult
import org.quicklauncher.host.runtime.widgets.WidgetDeletionResult
import org.quicklauncher.host.runtime.widgets.WidgetProviderIdentity
import org.quicklauncher.host.runtime.widgets.WidgetProviderDescriptor
import org.quicklauncher.host.runtime.widgets.WidgetProviderDiscoveryResult
import org.quicklauncher.host.runtime.widgets.WidgetRenderToken
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetSurfaceResult
import org.quicklauncher.host.runtime.widgets.WidgetUpdateResult
import org.quicklauncher.host.runtime.widgets.WidgetUserAction
import org.quicklauncher.host.runtime.widgets.WidgetValidationResult

class AndroidWidgetPlatformTest {
    @Test
    fun `provider discovery returns sorted sanitized metadata`() = runTest {
        val backend = FakeWidgetBackend().apply {
            providers = listOf(
                WidgetBackendProvider(
                    provider().copy(className = "org.example.widget.Zulu"),
                    "Zulu",
                    WidgetSize(120, 80),
                    configurationRequired = false,
                ),
                WidgetBackendProvider(
                    provider().copy(className = "org.example.widget.Alpha"),
                    "Alpha",
                    WidgetSize(80, 60),
                    configurationRequired = true,
                ),
            )
        }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        val result = platform.discoverProviders(ProfileSerial.of(10))

        assertEquals(
            WidgetProviderDiscoveryResult.Available(
                listOf(
                    WidgetProviderDescriptor(
                        provider().copy(className = "org.example.widget.Alpha"),
                        "Alpha",
                        WidgetSize(80, 60),
                        configurationRequired = true,
                    ),
                    WidgetProviderDescriptor(
                        provider().copy(className = "org.example.widget.Zulu"),
                        "Zulu",
                        WidgetSize(120, 80),
                        configurationRequired = false,
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `provider discovery reads no metadata for an unavailable profile`() = runTest {
        val backend = FakeWidgetBackend().apply { profileAvailable = false }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        assertEquals(
            WidgetProviderDiscoveryResult.ProfileUnavailable,
            platform.discoverProviders(ProfileSerial.of(10)),
        )
        assertEquals(0, backend.discoveryQueries)
    }

    @Test
    fun `bind resolves the current profile handle and requests permission when needed`() = runTest {
        val backend = FakeWidgetBackend().apply { bindAllowed = false }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        val result = platform.bind(AllocatedWidgetBinding(41, provider(), WidgetSize(200, 100)))

        assertEquals(
            WidgetBindResult.PermissionRequired(
                WidgetUserAction.BindPermission(41, provider(), WidgetSize(200, 100)),
            ),
            result,
        )
        assertEquals(WidgetBackendBinding(41, provider(), WidgetSize(200, 100)), backend.lastBinding)
    }

    @Test
    fun `missing profile fails before provider metadata is queried`() = runTest {
        val backend = FakeWidgetBackend().apply { profileAvailable = false }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        val result = platform.bind(AllocatedWidgetBinding(41, provider(), WidgetSize(200, 100)))

        assertEquals(WidgetBindResult.ProfileUnavailable, result)
        assertEquals(0, backend.providerQueries)
    }

    @Test
    fun `surface tokens are unique and releasing one does not dispose its neighbor`() = runTest {
        val backend = FakeWidgetBackend()
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        val first = assertType<WidgetSurfaceResult.Ready>(
            platform.createSurface(41, provider(), WidgetSize(200, 100)),
        )
        val second = assertType<WidgetSurfaceResult.Ready>(
            platform.createSurface(42, provider(), WidgetSize(200, 100)),
        )
        platform.releaseSurface(first.token)

        assertEquals(WidgetRenderToken.of("widget-surface-1"), first.token)
        assertEquals(WidgetRenderToken.of("widget-surface-2"), second.token)
        assertEquals(listOf(1L), backend.releasedSurfaceIds)
    }

    @Test
    fun `size update sends complete dp options to the framework`() = runTest {
        val backend = FakeWidgetBackend()
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        val result = platform.updateSize(41, provider(), WidgetSize(320, 180))

        assertEquals(WidgetUpdateResult.Updated, result)
        assertEquals(WidgetBackendSizeUpdate(41, WidgetSize(320, 180)), backend.lastSizeUpdate)
    }

    @Test
    fun `allocated id enumeration failure remains typed instead of resembling an empty host`() = runTest {
        val backend = FakeWidgetBackend().apply { enumerationFails = true }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        assertEquals(
            WidgetAllocatedIdsResult.Failed("widget_id_enumeration_failed"),
            platform.allocatedIds(),
        )
    }

    @Test
    fun `provider removal degrades validation and configuration without a framework object`() = runTest {
        val backend = FakeWidgetBackend().apply { bindingState = WidgetBackendBindingState.PROVIDER_UNAVAILABLE }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))

        assertEquals(WidgetValidationResult.ProviderUnavailable, platform.validateBinding(41, provider()))
        assertEquals(WidgetConfigurationResult.ProviderUnavailable, platform.configuration(41, provider()))
    }

    @Test
    fun `close disposes every live surface even when one disposal fails`() = runTest {
        val backend = FakeWidgetBackend().apply { failingSurfaceId = 1L }
        val platform = AndroidWidgetPlatform(backend, StandardTestDispatcher(testScheduler))
        assertType<WidgetSurfaceResult.Ready>(platform.createSurface(41, provider(), WidgetSize(200, 100)))
        assertType<WidgetSurfaceResult.Ready>(platform.createSurface(42, provider(), WidgetSize(200, 100)))

        platform.close()

        assertEquals(listOf(1L, 2L), backend.releaseAttempts)
        assertEquals(true, backend.closed)
    }

    private fun provider() = WidgetProviderIdentity(
        ProfileSerial.of(10),
        PackageName.parse("org.example.widget"),
        "org.example.widget.Provider",
    )

    private class FakeWidgetBackend : WidgetFrameworkBackend {
        var nextId = 41
        var profileAvailable = true
        var providers: List<WidgetBackendProvider> = emptyList()
        var discoveryQueries = 0
        var providerQueries = 0
        var bindAllowed = true
        var bindingState = WidgetBackendBindingState.VALID
        var configurationRequired = false
        var lastBinding: WidgetBackendBinding? = null
        var lastSizeUpdate: WidgetBackendSizeUpdate? = null
        var nextSurfaceId = 1L
        var failingSurfaceId: Long? = null
        val releasedSurfaceIds = mutableListOf<Long>()
        val releaseAttempts = mutableListOf<Long>()
        var closed = false
        var enumerationFails = false

        override fun allocate(): Int = nextId++

        override fun hasProfile(profile: ProfileSerial): Boolean = profileAvailable

        override fun providers(profile: ProfileSerial): List<WidgetBackendProvider> {
            discoveryQueries += 1
            return providers
        }

        override fun hasProvider(provider: WidgetProviderIdentity): Boolean {
            providerQueries++
            return true
        }

        override fun bind(binding: WidgetBackendBinding): Boolean {
            lastBinding = binding
            return bindAllowed
        }

        override fun bindingState(
            appWidgetId: Int,
            expectedProvider: WidgetProviderIdentity,
        ): WidgetBackendBindingState = bindingState

        override fun configurationRequired(appWidgetId: Int): Boolean = configurationRequired

        override fun createSurface(appWidgetId: Int, size: WidgetSize): Long = nextSurfaceId++

        override fun releaseSurface(surfaceId: Long) {
            releaseAttempts += surfaceId
            if (surfaceId == failingSurfaceId) error("injected release failure")
            releasedSurfaceIds += surfaceId
        }

        override fun updateSize(update: WidgetBackendSizeUpdate) {
            lastSizeUpdate = update
        }

        override fun delete(appWidgetId: Int): Boolean = true

        override fun allocatedIds(): Set<Int> {
            if (enumerationFails) error("injected enumeration failure")
            return emptySet()
        }

        override fun close() {
            closed = true
        }
    }
}

private inline fun <reified T> assertType(value: Any?): T {
    assertTrue("Expected ${T::class.java.simpleName}, received $value", value is T)
    return value as T
}
