package org.quicklauncher.host.platform.shortcuts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.runtime.shortcuts.ShortcutInvalidation
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutOwner
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinRequest
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutQueryResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity
import org.quicklauncher.host.runtime.shortcuts.ShortcutUnavailableReason

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidShortcutPlatformTest {
    @Test
    fun `unknown profile types fail closed before shortcut access`() {
        assertEquals(
            AndroidShortcutProfileAccess.DENIED,
            shortcutProfileAccess(
                userType = "android.os.usertype.profile.CLONE",
                unlocked = true,
                quiet = false,
                hasHostPermission = true,
            ),
        )
        assertEquals(
            AndroidShortcutProfileAccess.AVAILABLE,
            shortcutProfileAccess(
                userType = "android.os.usertype.full.SECONDARY",
                unlocked = true,
                quiet = false,
                hasHostPermission = true,
            ),
        )
    }

    @Test
    fun `private and locked profiles are rejected before shortcut metadata reads`() = runTest {
        val backend = FakeAndroidShortcutBackend().apply {
            access[10] = AndroidShortcutProfileAccess.PRIVATE
            access[11] = AndroidShortcutProfileAccess.LOCKED
        }
        val platform = AndroidShortcutPlatform(backend, StandardTestDispatcher(testScheduler))

        assertEquals(
            ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PRIVATE_PROFILE),
            platform.query(owner(10)),
        )
        assertEquals(
            ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PROFILE_LOCKED),
            platform.query(owner(11)),
        )
        assertTrue(backend.shortcutReads.isEmpty())
        platform.close()
    }

    @Test
    fun `query returns immutable android-free shortcut values`() = runTest {
        val owner = owner(0)
        val icon = byteArrayOf(1, 2, 3)
        val backend = FakeAndroidShortcutBackend().apply {
            shortcuts[owner] = listOf(record("compose", "Compose", icon = icon))
        }
        val platform = AndroidShortcutPlatform(backend, StandardTestDispatcher(testScheduler))

        val result = platform.query(owner) as ShortcutQueryResult.Available
        icon[0] = 9
        val first = requireNotNull(result.shortcuts.single().icon).bytes()
        first[1] = 9

        assertEquals(listOf<Byte>(1, 2, 3), requireNotNull(result.shortcuts.single().icon).bytes().toList())
        assertTrue(runCatching { (result.shortcuts as MutableList<*>).clear() }.isFailure)
        platform.close()
    }

    @Test
    fun `pin sends the complete set for one profile package`() = runTest {
        val owner = owner(0)
        val backend = FakeAndroidShortcutBackend()
        val platform = AndroidShortcutPlatform(backend, StandardTestDispatcher(testScheduler))
        val mutableIds = mutableSetOf(ShortcutId.parse("compose"), ShortcutId.parse("inbox"))
        val request = ShortcutPinRequest(
            owner,
            mutableIds,
        )
        mutableIds.clear()

        val result = platform.pin(request)

        assertEquals(ShortcutPinResult.Applied, result)
        assertEquals(listOf(request), backend.pinRequests)
        assertEquals(2, backend.pinRequests.single().shortcutIds.size)
        assertTrue(runCatching { (request.shortcutIds as MutableSet<*>).clear() }.isFailure)
        platform.close()
    }

    @Test
    fun `launch revalidates current shortcut availability`() = runTest {
        val target = target(0)
        val backend = FakeAndroidShortcutBackend().apply {
            shortcuts[target.owner()] = listOf(record("compose", "Compose", enabled = false))
        }
        val platform = AndroidShortcutPlatform(backend, StandardTestDispatcher(testScheduler))

        val disabled = platform.launch(target)
        backend.shortcuts[target.owner()] = emptyList()
        val missing = platform.launch(target)

        assertEquals(
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.DISABLED),
            disabled,
        )
        assertEquals(
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.MISSING),
            missing,
        )
        assertTrue(backend.launches.isEmpty())
        platform.close()
    }

    @Test
    fun `framework callbacks invalidate and close releases registrations once`() = runTest {
        val backend = FakeAndroidShortcutBackend()
        val platform = AndroidShortcutPlatform(backend, StandardTestDispatcher(testScheduler))
        val change = async { platform.invalidations.first() }
        runCurrent()

        backend.invalidate()

        assertEquals(ShortcutInvalidation.Changed, change.await())
        platform.close()
        platform.close()
        assertEquals(1, backend.closeCalls)
        assertEquals(ShortcutLaunchResult.Closed, platform.launch(target(0)))
    }

    private fun owner(serial: Long) = ShortcutOwner(
        ProfileSerial.of(serial),
        PackageName.parse("org.example.mail"),
    )

    private fun target(serial: Long) = ShortcutTargetIdentity(
        ProfileSerial.of(serial),
        PackageName.parse("org.example.mail"),
        ShortcutId.parse("compose"),
    )

    private fun ShortcutTargetIdentity.owner() = ShortcutOwner(profile, packageName)

    private fun record(
        id: String,
        label: String,
        enabled: Boolean = true,
        icon: ByteArray? = byteArrayOf(1),
    ) = AndroidShortcutRecord(
        ShortcutId.parse(id),
        label,
        dynamic = true,
        pinned = false,
        enabled = enabled,
        iconBytes = icon,
    )

    private class FakeAndroidShortcutBackend : AndroidShortcutBackend {
        private var invalidated: (() -> Unit)? = null
        val access = mutableMapOf<Long, AndroidShortcutProfileAccess>()
        val shortcuts = mutableMapOf<ShortcutOwner, List<AndroidShortcutRecord>>()
        val shortcutReads = mutableListOf<ShortcutOwner>()
        val pinRequests = mutableListOf<ShortcutPinRequest>()
        val launches = mutableListOf<ShortcutTargetIdentity>()
        var closeCalls = 0

        override fun start(onInvalidated: () -> Unit) {
            invalidated = onInvalidated
        }

        override fun access(profileSerial: Long): AndroidShortcutProfileAccess =
            access[profileSerial] ?: AndroidShortcutProfileAccess.AVAILABLE

        override fun shortcuts(owner: ShortcutOwner): List<AndroidShortcutRecord> {
            shortcutReads += owner
            return shortcuts[owner].orEmpty()
        }

        override fun pin(owner: ShortcutOwner, shortcutIds: Set<ShortcutId>) {
            pinRequests += ShortcutPinRequest(owner, shortcutIds)
        }

        override fun launch(target: ShortcutTargetIdentity) {
            launches += target
        }

        fun invalidate() {
            invalidated?.invoke()
        }

        override fun close() {
            closeCalls += 1
            invalidated = null
        }
    }
}
