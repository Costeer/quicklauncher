package org.quicklauncher.host.platform.widgets

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.widgets.AllocatedWidgetBinding
import org.quicklauncher.host.runtime.widgets.WidgetAllocationResult
import org.quicklauncher.host.runtime.widgets.WidgetAllocatedIdsResult
import org.quicklauncher.host.runtime.widgets.WidgetBindResult
import org.quicklauncher.host.runtime.widgets.WidgetDeletionResult
import org.quicklauncher.host.runtime.widgets.WidgetProviderIdentity
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetUserAction
import org.quicklauncher.host.runtime.widgets.WidgetValidationResult

@RunWith(AndroidJUnit4::class)
class AndroidWidgetPlatformInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val allocatedIds = linkedSetOf<Int>()

    @Before
    fun requireApi35OrNewerAndRevokeBindAuthority() {
        assertTrue("Widget host behavior requires API 35 or newer", Build.VERSION.SDK_INT >= 35)
        setBindAuthority(allowed = false)
    }

    @After
    fun removeAllocatedIdsAndBindAuthority() {
        val platform = AndroidWidgetPlatform(context, Dispatchers.Main)
        try {
            runBlocking {
                allocatedIds.toList().forEach { platform.delete(it) }
            }
        } finally {
            platform.close()
            setBindAuthority(allowed = false)
        }
    }

    @Test
    fun rejectedBindCanBeCancelledWithoutLeakingEitherUniqueAllocation() = runBlocking {
        val platform = AndroidWidgetPlatform(context, Dispatchers.Main)
        try {
            val firstId = platform.allocate().requireId()
            val secondId = platform.allocate().requireId()
            allocatedIds += firstId
            allocatedIds += secondId

            assertNotEquals("Visible placements must never share an Android widget ID", firstId, secondId)
            assertTrue(platform.allocatedIds().requireIds().containsAll(listOf(firstId, secondId)))

            val provider = fixtureProvider()
            val denied = platform.bind(AllocatedWidgetBinding(firstId, provider, FIXTURE_SIZE))
            assertTrue(
                "A host without bind authority must return the typed permission action",
                denied is WidgetBindResult.PermissionRequired,
            )
            assertEquals(WidgetValidationResult.NotBound, platform.validateBinding(firstId, provider))

            assertEquals(WidgetDeletionResult.Deleted, platform.delete(firstId))
            assertEquals(WidgetDeletionResult.Deleted, platform.delete(secondId))
            allocatedIds.remove(firstId)
            allocatedIds.remove(secondId)
            assertFalse(platform.allocatedIds().requireIds().contains(firstId))
            assertFalse(platform.allocatedIds().requireIds().contains(secondId))
        } finally {
            platform.close()
        }
    }

    @Test
    fun allowedBindCreatesAndDisposesARealHostViewAcrossHostRecreation() = runBlocking {
        val provider = fixtureProvider()
        val platform = AndroidWidgetPlatform(context, Dispatchers.Main)
        val appWidgetId = platform.allocate().requireId()
        allocatedIds += appWidgetId
        try {
            assertEquals(
                "The unapproved host must request bind permission through the typed seam",
                WidgetBindResult.PermissionRequired(
                    WidgetUserAction.BindPermission(
                        appWidgetId,
                        provider,
                        FIXTURE_SIZE,
                    ),
                ),
                platform.bind(AllocatedWidgetBinding(appWidgetId, provider, FIXTURE_SIZE)),
            )
            setBindAuthority(allowed = true)
            assertEquals(
                "Framework bind approval must make the same typed request succeed",
                WidgetBindResult.Bound,
                platform.bind(AllocatedWidgetBinding(appWidgetId, provider, FIXTURE_SIZE)),
            )
            assertEquals(WidgetValidationResult.Valid, platform.validateBinding(appWidgetId, provider))
        } finally {
            platform.close()
        }

        val firstHost = FrameworkWidgetBackend(context)
        try {
            val firstSurfaceId = instrumentation.runOnMain {
                assertEquals(
                    WidgetBackendBindingState.VALID,
                    firstHost.bindingState(appWidgetId, provider),
                )
                firstHost.createSurface(appWidgetId, FIXTURE_SIZE)
            }
            val firstView = requireNotNull(firstHost.surfaceView(firstSurfaceId))
            val parent = instrumentation.runOnMain {
                FrameLayout(context).also { it.addView(firstView) }
            }
            assertEquals(appWidgetId, firstView.appWidgetId)
            assertEquals(parent, firstView.parent)

            instrumentation.runOnMain { firstHost.releaseSurface(firstSurfaceId) }
            assertNull("Released render tokens must drop the retained host view", firstHost.surfaceView(firstSurfaceId))
            assertNull("Disposal must detach the framework view from its composition parent", firstView.parent)
            firstHost.close()

            val recreatedHost = FrameworkWidgetBackend(context)
            try {
                val recreatedSurfaceId = instrumentation.runOnMain {
                    assertEquals(
                        "The framework binding must survive host recreation",
                        WidgetBackendBindingState.VALID,
                        recreatedHost.bindingState(appWidgetId, provider),
                    )
                    recreatedHost.createSurface(appWidgetId, WidgetSize(widthDp = 240, heightDp = 160))
                }
                val recreatedView = recreatedHost.surfaceView(recreatedSurfaceId)
                assertNotNull("Host recreation must construct another real AppWidgetHostView", recreatedView)
                assertEquals(appWidgetId, recreatedView?.appWidgetId)
                assertTrue("Recreation must not reuse a disposed framework view", recreatedView !== firstView)
                instrumentation.runOnMain { recreatedHost.releaseSurface(recreatedSurfaceId) }
            } finally {
                recreatedHost.close()
            }
        } finally {
            firstHost.close()
            val cleanup = AndroidWidgetPlatform(context, Dispatchers.Main)
            try {
                cleanup.delete(appWidgetId)
                allocatedIds.remove(appWidgetId)
            } finally {
                cleanup.close()
                setBindAuthority(allowed = false)
            }
        }
    }

    private fun fixtureProvider(providerContext: android.content.Context = context): WidgetProviderIdentity {
        val provider = AppWidgetManager.getInstance(providerContext)
            .getInstalledProvidersForProfile(Process.myUserHandle())
            .singleOrNull { it.provider.className == PhaseFiveFixtureWidgetProvider::class.java.name }
        requireNotNull(provider) { "The deterministic instrumentation widget provider is not installed" }
        val serial = providerContext.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        require(serial >= 0L) { "The current profile does not have a stable serial" }
        return WidgetProviderIdentity(
            profile = ProfileSerial.of(serial),
            packageName = PackageName.parse(provider.provider.packageName),
            className = provider.provider.className,
        )
    }

    private fun WidgetAllocationResult.requireId(): Int =
        (this as? WidgetAllocationResult.Allocated)?.appWidgetId
            ?: error("Expected a framework widget ID, got $this")

    private fun setBindAuthority(allowed: Boolean) {
        // API 35 ATD images omit both the bind-confirmation activity and the appwidget shell
        // command. Exercise the same framework permission bit under the shell test identity.
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS",
        )
        try {
            val method = AppWidgetManager::class.java.declaredMethods.single {
                it.name == "setBindAppWidgetPermission" && it.parameterTypes.size == 3
            }
            method.isAccessible = true
            method.invoke(
                AppWidgetManager.getInstance(context),
                context.packageName,
                Process.myUid() / ANDROID_UIDS_PER_USER,
                allowed,
            )
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun <T> android.app.Instrumentation.runOnMain(block: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        runOnMainSync {
            try {
                value = block()
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object {
        const val ANDROID_UIDS_PER_USER = 100_000
        val FIXTURE_SIZE = WidgetSize(widthDp = 180, heightDp = 120)
    }
}

private fun WidgetAllocatedIdsResult.requireIds(): Set<Int> =
    (this as WidgetAllocatedIdsResult.Available).appWidgetIds
