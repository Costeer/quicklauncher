package org.quicklauncher.host.runtime.shortcuts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.StoreRejectionCode

@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutCoordinatorTest {
    @Test
    fun `eligible visible owners are discovered before a shortcut placement exists`() = runTest {
        val compose = target(10, "org.example.mail", "compose")
        val lockedOwner = ShortcutOwner(ProfileSerial.of(11), PackageName.parse("org.example.work"))
        val privateOwner = ShortcutOwner(ProfileSerial.of(12), PackageName.parse("org.example.private"))
        val platform = FakeShortcutPlatform(
            mapOf(
                compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose"))),
                lockedOwner to ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PROFILE_LOCKED),
                privateOwner to ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PRIVATE_PROFILE),
            ),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(emptyList()),
            backgroundScope,
        )

        coordinator.refresh(listOf(compose.owner, lockedOwner, privateOwner))

        assertEquals(
            listOf(compose),
            coordinator.state.value.availableShortcuts.map(DiscoveredShortcut::target),
        )
        assertEquals(
            mapOf(
                lockedOwner to ShortcutUnavailableReason.PROFILE_LOCKED,
                privateOwner to ShortcutUnavailableReason.PRIVATE_PROFILE,
            ),
            coordinator.state.value.unavailableOwners,
        )
        assertEquals(
            setOf(compose.owner, lockedOwner, privateOwner),
            platform.queriedOwners.toSet(),
        )
        coordinator.close()
    }

    @Test
    fun `platform invalidation refreshes discovery when no placement exists`() = runTest {
        val compose = target(10, "org.example.mail", "compose")
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Old")))),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(emptyList()),
            backgroundScope,
        )
        coordinator.refresh(listOf(compose.owner))
        platform.queries[compose.owner] =
            ShortcutQueryResult.Available(listOf(shortcut(compose, "New")))

        platform.invalidate()
        runCurrent()

        assertEquals("New", coordinator.state.value.availableShortcuts.single().label)
        assertEquals(2, platform.queriedOwners.size)
        coordinator.close()
    }

    @Test
    fun `creating duplicate placements persists distinct identities and recomputes the complete pinned set`() = runTest {
        val compose = target(10, "org.example.mail", "compose")
        val store = InMemoryLauncherStore()
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose")))),
        )
        val identities = ArrayDeque(
            listOf(
                ContentItemId.parse("org.quicklauncher.content/shortcut-one"),
                ContentItemId.parse("org.quicklauncher.content/shortcut-two"),
            ),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform = platform,
            placements = StoreShortcutPlacementSource(store),
            parentScope = backgroundScope,
            identities = ShortcutPlacementIdentitySource { identities.removeFirst() },
        )
        coordinator.refresh(listOf(compose.owner))

        val first = coordinator.createPlacement(compose)
        val second = coordinator.createPlacement(compose)

        assertTrue(first is ShortcutPlacementCreation.Created)
        assertTrue(second is ShortcutPlacementCreation.Created)
        val records = store.read().contentItems
        assertEquals(2, records.size)
        assertEquals(2, records.map { it.id }.distinct().size)
        assertTrue(records.all { it.kind == ContentItemKind.SHORTCUT })
        assertEquals(1, records.map { it.shortcutTarget }.distinct().size)
        assertEquals(
            listOf(
                emptySet(),
                setOf(ShortcutId.parse("compose")),
                setOf(ShortcutId.parse("compose")),
            ),
            platform.pinRequests.map { it.shortcutIds },
        )
        coordinator.close()
    }

    @Test
    fun `placement creation rejects a target hidden by current profile policy without writing`() = runTest {
        val private = target(12, "org.example.private", "secret")
        val store = InMemoryLauncherStore()
        val platform = FakeShortcutPlatform(
            mapOf(
                private.owner to ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PRIVATE_PROFILE),
            ),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform,
            StoreShortcutPlacementSource(store),
            backgroundScope,
        )
        coordinator.refresh(listOf(private.owner))

        val result = coordinator.createPlacement(private)

        assertEquals(
            ShortcutPlacementCreation.Unavailable(ShortcutUnavailableReason.PRIVATE_PROFILE),
            result,
        )
        assertTrue(store.read().contentItems.isEmpty())
        coordinator.close()
    }

    @Test
    fun `store rejection remains typed and does not change the prior shortcut snapshot`() = runTest {
        val compose = target(10, "org.example.mail", "compose")
        val store = InMemoryLauncherStore()
        val duplicateId = ContentItemId.parse("org.quicklauncher.content/shortcut-one")
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose")))),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform = platform,
            placements = StoreShortcutPlacementSource(store),
            parentScope = backgroundScope,
            identities = ShortcutPlacementIdentitySource { duplicateId },
        )
        coordinator.refresh(listOf(compose.owner))
        assertTrue(coordinator.createPlacement(compose) is ShortcutPlacementCreation.Created)
        val before = store.read()

        val rejected = coordinator.createPlacement(compose)

        assertTrue(rejected is ShortcutPlacementCreation.Rejected)
        rejected as ShortcutPlacementCreation.Rejected
        assertEquals(StoreRejectionCode.DUPLICATE_ID, rejected.reason.code)
        assertEquals(before, store.read())
        coordinator.close()
    }

    @Test
    fun `refresh pins the complete distinct set for each profile package while retaining duplicate placements`() = runTest {
        val mail = target(10, "org.example.mail", "compose")
        val inbox = target(10, "org.example.mail", "inbox")
        val platform = FakeShortcutPlatform(
            mapOf(
                mail.owner to ShortcutQueryResult.Available(
                    listOf(shortcut(mail, "Compose"), shortcut(inbox, "Inbox")),
                ),
            ),
        )
        val placements = FakeShortcutPlacementSource(
            listOf(
                placement("org.quicklauncher.content/one", mail),
                placement("org.quicklauncher.content/two", mail),
                placement("org.quicklauncher.content/three", inbox),
            ),
        )
        val coordinator = DefaultShortcutCoordinator(platform, placements, backgroundScope)

        coordinator.refresh()

        assertEquals(
            listOf(setOf(ShortcutId.parse("compose"), ShortcutId.parse("inbox"))),
            platform.pinRequests.map { it.shortcutIds },
        )
        assertEquals(3, coordinator.state.value.placements.size)
        assertEquals(
            listOf("Compose", "Compose", "Inbox"),
            coordinator.state.value.placements.map { (it as ResolvedShortcutPlacement.Available).label },
        )
        coordinator.close()
    }

    @Test
    fun `host restart reloads duplicate placements and reconciles one semantic pinned id`() = runTest {
        val compose = target(0, "org.example.mail", "compose")
        val placements = FakeShortcutPlacementSource(
            listOf(
                placement("org.quicklauncher.content/first", compose),
                placement("org.quicklauncher.content/second", compose),
            ),
        )
        fun platform() = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose")))),
        )
        val firstPlatform = platform()
        DefaultShortcutCoordinator(firstPlatform, placements, backgroundScope).also {
            it.refresh()
            it.close()
        }
        val restartedPlatform = platform()
        val restarted = DefaultShortcutCoordinator(restartedPlatform, placements, backgroundScope)

        restarted.refresh()

        assertEquals(2, restarted.state.value.placements.size)
        assertEquals(
            listOf(setOf(ShortcutId.parse("compose"))),
            restartedPlatform.pinRequests.map { it.shortcutIds },
        )
        restarted.close()
    }

    @Test
    fun `missing and disabled shortcuts degrade without removing durable placements`() = runTest {
        val missing = target(0, "org.example.mail", "missing")
        val disabled = target(0, "org.example.mail", "disabled")
        val platform = FakeShortcutPlatform(
            mapOf(
                missing.owner to ShortcutQueryResult.Available(
                    listOf(shortcut(disabled, "Disabled", enabled = false)),
                ),
            ),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(
                listOf(
                    placement("org.quicklauncher.content/missing", missing),
                    placement("org.quicklauncher.content/disabled", disabled),
                ),
            ),
            backgroundScope,
        )

        coordinator.refresh()

        val unavailable = coordinator.state.value.placements.associate {
            it.id to (it as ResolvedShortcutPlacement.Unavailable).reason
        }
        assertEquals(ShortcutUnavailableReason.MISSING, unavailable[id("missing")])
        assertEquals(ShortcutUnavailableReason.DISABLED, unavailable[id("disabled")])
        coordinator.close()
    }

    @Test
    fun `launch re-queries profile and shortcut state before dispatch`() = runTest {
        val compose = target(10, "org.example.mail", "compose")
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose")))),
        )
        val id = ContentItemId.parse("org.quicklauncher.content/compose")
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(listOf(ShortcutPlacement(id, compose))),
            backgroundScope,
        )
        coordinator.refresh()
        platform.queries[compose.owner] =
            ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PROFILE_LOCKED)

        val result = coordinator.launch(id)

        assertEquals(
            ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.PROFILE_LOCKED),
            result,
        )
        assertTrue(platform.launches.isEmpty())
        coordinator.close()
    }

    @Test
    fun `platform callback refreshes shortcut state and pinned set`() = runTest {
        val compose = target(0, "org.example.mail", "compose")
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Old")))),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(
                listOf(placement("org.quicklauncher.content/compose", compose)),
            ),
            backgroundScope,
        )
        runCurrent()
        coordinator.refresh()
        platform.queries[compose.owner] =
            ShortcutQueryResult.Available(listOf(shortcut(compose, "New")))

        platform.invalidate()
        runCurrent()

        assertEquals(
            "New",
            (coordinator.state.value.placements.single() as ResolvedShortcutPlacement.Available).label,
        )
        assertEquals(2, platform.pinRequests.size)
        coordinator.close()
    }

    @Test
    fun `refresh clears the pinned set after the owner's last placement is removed`() = runTest {
        val compose = target(0, "org.example.mail", "compose")
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose")))),
        )
        val placements = FakeShortcutPlacementSource(
            listOf(placement("org.quicklauncher.content/compose", compose)),
        )
        val coordinator = DefaultShortcutCoordinator(platform, placements, backgroundScope)
        coordinator.refresh()
        placements.placements = emptyList()

        coordinator.refresh()

        assertEquals(
            listOf(setOf(ShortcutId.parse("compose")), emptySet()),
            platform.pinRequests.map { it.shortcutIds },
        )
        assertTrue(coordinator.state.value.placements.isEmpty())
        coordinator.close()
    }

    @Test
    fun `failed final unpin remains owned and is retried until it succeeds`() = runTest {
        val compose = target(0, "org.example.mail", "compose")
        val platform = FakeShortcutPlatform(
            mapOf(compose.owner to ShortcutQueryResult.Available(listOf(shortcut(compose, "Compose")))),
        )
        val placements = FakeShortcutPlacementSource(
            listOf(placement("org.quicklauncher.content/compose", compose)),
        )
        val coordinator = DefaultShortcutCoordinator(platform, placements, backgroundScope)
        coordinator.refresh()
        placements.placements = emptyList()
        platform.pinResults += ShortcutPinResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)

        coordinator.refresh()
        coordinator.refresh()
        coordinator.refresh()

        assertEquals(
            listOf(
                setOf(ShortcutId.parse("compose")),
                emptySet(),
                emptySet(),
            ),
            platform.pinRequests.map { it.shortcutIds },
        )
        coordinator.close()
    }

    @Test
    fun `shortcut snapshots and icons are immutable`() = runTest {
        val compose = target(0, "org.example.mail", "compose")
        val sourceIcon = byteArrayOf(1, 2, 3)
        val platform = FakeShortcutPlatform(
            mapOf(
                compose.owner to ShortcutQueryResult.Available(
                    listOf(shortcut(compose, "Compose", icon = sourceIcon)),
                ),
            ),
        )
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(
                listOf(placement("org.quicklauncher.content/compose", compose)),
            ),
            backgroundScope,
        )

        coordinator.refresh()
        sourceIcon[0] = 9
        val resolved = coordinator.state.value.placements.single() as ResolvedShortcutPlacement.Available
        val first = requireNotNull(resolved.icon).bytes()
        first[1] = 9
        val second = requireNotNull(resolved.icon).bytes()

        assertEquals(listOf<Byte>(1, 2, 3), second.toList())
        assertNotSame(first, second)
        assertTrue(runCatching { (coordinator.state.value.placements as MutableList<*>).clear() }.isFailure)
        coordinator.close()
    }

    @Test
    fun `refresh propagates cancellation`() = runTest {
        val compose = target(0, "org.example.mail", "compose")
        val platform = FakeShortcutPlatform(emptyMap()).apply {
            queryFailure = CancellationException("cancelled")
        }
        val coordinator = DefaultShortcutCoordinator(
            platform,
            FakeShortcutPlacementSource(
                listOf(placement("org.quicklauncher.content/compose", compose)),
            ),
            backgroundScope,
        )

        val failure = runCatching { coordinator.refresh() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        coordinator.close()
    }

    private fun target(serial: Long, packageName: String, id: String) = ShortcutTargetIdentity(
        profile = ProfileSerial.of(serial),
        packageName = PackageName.parse(packageName),
        shortcutId = ShortcutId.parse(id),
    )

    private fun shortcut(
        target: ShortcutTargetIdentity,
        label: String,
        enabled: Boolean = true,
        icon: ByteArray = byteArrayOf(1, 2, 3),
    ) = DiscoveredShortcut(
        target = target,
        label = label,
        icon = ShortcutIcon.of(icon),
        dynamic = true,
        pinned = false,
        enabled = enabled,
    )

    private fun placement(id: String, target: ShortcutTargetIdentity) = ShortcutPlacement(
        id = ContentItemId.parse(id),
        target = target,
    )

    private fun id(name: String) = ContentItemId.parse("org.quicklauncher.content/$name")

    private class FakeShortcutPlacementSource(
        var placements: List<ShortcutPlacement>,
    ) : ShortcutPlacementSource {
        override suspend fun read(): List<ShortcutPlacement> = placements
    }

    private class FakeShortcutPlatform(
        queries: Map<ShortcutOwner, ShortcutQueryResult>,
    ) : ShortcutPlatform {
        private val changes = MutableSharedFlow<ShortcutInvalidation>(extraBufferCapacity = 1)
        override val invalidations: Flow<ShortcutInvalidation> = changes
        val queries = queries.toMutableMap()
        val pinRequests = mutableListOf<ShortcutPinRequest>()
        val pinResults = ArrayDeque<ShortcutPinResult>()
        val launches = mutableListOf<ShortcutTargetIdentity>()
        val queriedOwners = mutableListOf<ShortcutOwner>()
        var queryFailure: CancellationException? = null

        override suspend fun query(owner: ShortcutOwner): ShortcutQueryResult {
            queryFailure?.let { throw it }
            queriedOwners += owner
            return queries[owner] ?: ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.MISSING)
        }

        override suspend fun pin(request: ShortcutPinRequest): ShortcutPinResult {
            pinRequests += request
            return pinResults.removeFirstOrNull() ?: ShortcutPinResult.Applied
        }

        override suspend fun launch(target: ShortcutTargetIdentity): ShortcutLaunchResult {
            launches += target
            return ShortcutLaunchResult.Launched
        }

        fun invalidate() {
            changes.tryEmit(ShortcutInvalidation.Changed)
        }

        override fun close() = Unit
    }
}
