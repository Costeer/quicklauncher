package org.quicklauncher.host.data.store

import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ConfigurationMigration
import org.quicklauncher.contracts.contribution.ConfigurationLoadResult
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.MigrationResult
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.spatial.DestinationVector

class InMemoryLauncherStoreTest {
    @Test
    fun `theme profile and destination background edits are atomic and reference safe`() = runTest {
        val store = bootstrappedStore()
        val profileId = ThemeProfileId.parse("org.quicklauncher.theme/store-test")
        val profile = ThemeProfileRecord(profileId, "Store test", "opaque-profile", 1)
        val destinationId = requireNotNull(store.read().startDestinationId)
        val background = DestinationBackgroundRecord(
            destinationId,
            profileId,
            "opaque-background",
            1,
        )
        val before = store.read()

        val inserted = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(LauncherEdit.PutThemeProfile(profile), LauncherEdit.PutDestinationBackground(background)),
            ),
        ).committedState()
        assertEquals(listOf(profile), inserted.themeProfiles)
        assertEquals(listOf(background), inserted.destinationBackgrounds)

        val rejected = store.commit(
            LauncherTransaction(
                inserted.revision,
                listOf(LauncherEdit.DeleteThemeProfile(profileId, confirmed = true)),
            ),
        ) as CommitResult.Rejected
        assertSame(inserted, rejected.current)
        assertSame(inserted, store.read())

        val deleted = store.commit(
            LauncherTransaction(
                inserted.revision,
                listOf(
                    LauncherEdit.DeleteDestinationBackground(destinationId),
                    LauncherEdit.DeleteThemeProfile(profileId, confirmed = true),
                ),
            ),
        ).committedState()
        assertTrue(deleted.themeProfiles.isEmpty())
        assertTrue(deleted.destinationBackgrounds.isEmpty())
    }

    @Test
    fun `onboarding plan replaces pristine safe state atomically and rejects invalid plans unchanged`() = runTest {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val before = store.read()
        val optional = sampleInstall("onboarded", 0, 0)
        val installation = LauncherPlanInstallation(
            optional.destination.id,
            listOf(optional.destination),
            listOf(optional.layout),
            listOf(optional.layoutInstance),
            listOf(optional.configuration),
            emptyList(),
        )

        val rejected = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.InstallLauncherPlan(
                        LauncherPlanInstallation(
                            destinationId("missing"),
                            installation.destinations,
                            installation.layouts,
                            installation.moduleInstances,
                            installation.configurations,
                            installation.placements,
                        ),
                    ),
                ),
            ),
        ) as CommitResult.Rejected
        assertSame(before, rejected.current)
        assertSame(before, store.read())

        val committed = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(LauncherEdit.InstallLauncherPlan(installation)),
            ),
        ).committedState()
        assertEquals(optional.destination.id, committed.startDestinationId)
        assertEquals(listOf(optional.destination), committed.destinations)
    }

    @Test
    fun `app override policy has an immutable narrow read independent of map decoding`() = runTest {
        val hidden = AppOverrideRecord(
            profile = ProfileSerial.of(0),
            packageName = PackageName.parse("org.example.hidden"),
            activityName = "org.example.hidden.MainActivity",
            customLabel = null,
            encodedIcon = null,
            favorite = false,
            collectionVisible = false,
            searchVisible = false,
        )
        val store: LauncherStore = InMemoryLauncherStore(
            LauncherSnapshot(
                revision = StoreRevision.ZERO,
                startDestinationId = null,
                destinations = emptyList(),
                destinationLayouts = emptyList(),
                moduleInstances = emptyList(),
                configurationDocuments = emptyList(),
                placements = emptyList(),
                appOverrides = listOf(hidden),
            ),
        )

        val overrides = store.readAppOverrides()

        assertEquals(listOf(hidden), overrides)
        assertTrue(runCatching { (overrides as MutableList<AppOverrideRecord>).clear() }.isFailure)
    }

    @Test
    fun `bootstrap atomically creates the start destination and selected layout`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        val initial = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = initial.revision,
                edits = listOf(LauncherEdit.Bootstrap(sampleInstall("start", 0, 0))),
            ),
        )

        val committed = result as CommitResult.Committed
        assertEquals(StoreRevision.of(1), committed.state.revision)
        assertEquals(destinationId("start"), committed.state.startDestinationId)
        assertEquals(1, committed.state.destinations.size)
        assertEquals(1, committed.state.destinationLayouts.size)
        assertEquals(1, committed.state.moduleInstances.size)
        assertEquals(1, committed.state.configurationDocuments.size)
        assertEquals(true, committed.state.destinationLayouts.single().selected)
        assertSame(committed.state, store.read())
    }

    @Test
    fun `deleting the last destination is rejected without changing state`() = runTest {
        val store: LauncherStore = bootstrappedStore()
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.DeleteDestination(destinationId("start"), confirmed = true),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.START_DESTINATION_REQUIRED, result.reason.code)
        assertSame(before, result.current)
        assertSame(before, store.read())
    }

    @Test
    fun `moving destinations before bootstrap returns a typed empty-store rejection`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.MoveDestinations(
                        destinationIds = listOf(destinationId("missing")),
                        vector = DestinationVector(1, 0),
                    ),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.STORE_EMPTY, result.reason.code)
        assertSame(before, result.current)
        assertSame(before, store.read())
    }

    @Test
    fun `deleting a destination before bootstrap returns a typed empty-store rejection`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.DeleteDestination(destinationId("missing"), confirmed = true),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.STORE_EMPTY, result.reason.code)
        assertSame(before, result.current)
        assertSame(before, store.read())
    }

    @Test
    fun `disconnected destination install is rejected without changing state`() = runTest {
        val store = bootstrappedStore()
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.InstallDestination(sampleInstall("island", 2, 0)),
                ),
            ),
        )

        assertTrue(result is CommitResult.Rejected)
        val rejected = result as CommitResult.Rejected
        assertEquals(StoreRejectionCode.DISCONNECTED_MAP, rejected.reason.code)
        assertSame(before, rejected.current)
        assertSame(before, store.read())
    }

    @Test
    fun `selecting another layout preserves the dormant layout and restores its identities`() = runTest {
        val store = bootstrappedStore()
        val original = store.read()
        val retained = sampleInstall("start", 0, 0, layoutLocal = "list")

        val withSecondLayout = store.commit(
            LauncherTransaction(
                expectedRevision = original.revision,
                edits = listOf(
                    LauncherEdit.RetainLayout(
                        layout = retained.layout,
                        layoutInstance = retained.layoutInstance,
                        configuration = retained.configuration,
                    ),
                    LauncherEdit.SelectLayout(destinationId("start"), retained.layoutInstance.id),
                ),
            ),
        ).committedState()

        assertEquals(2, withSecondLayout.destinationLayouts.size)
        assertEquals(
            retained.layoutInstance.id,
            withSecondLayout.destinationLayouts.single { it.selected }.layoutInstanceId,
        )
        assertEquals(
            "{ layout: 'start' }",
            withSecondLayout.configurationDocuments
                .single { it.id == configurationId("start-layout") }
                .document.encoded.value,
        )

        val restored = store.commit(
            LauncherTransaction(
                expectedRevision = withSecondLayout.revision,
                edits = listOf(
                    LauncherEdit.SelectLayout(destinationId("start"), instanceId("start-layout")),
                ),
            ),
        ).committedState()

        assertEquals(instanceId("start-layout"), restored.destinationLayouts.single { it.selected }.layoutInstanceId)
        assertEquals(2, restored.configurationDocuments.size)
    }

    @Test
    fun `confirmed dormant layout removal deletes its complete tree and preserves the selected layout`() =
        runTest {
            val store = bootstrappedStore(PlacementPolicy { null })
            val retained = sampleInstall("start", 0, 0, layoutLocal = "list")
            val child = samplePlacedModule("dormant-child", 0).let { module ->
                module.copy(
                    placement = module.placement.copy(
                        layoutInstanceId = retained.layoutInstance.id,
                        parentInstanceId = retained.layoutInstance.id,
                    ),
                )
            }
            val withDormantTree = store.commit(
                LauncherTransaction(
                    store.read().revision,
                    listOf(
                        LauncherEdit.RetainLayout(
                            retained.layout,
                            retained.layoutInstance,
                            retained.configuration,
                        ),
                        LauncherEdit.CommitDrop(child),
                    ),
                ),
            ).committedState()

            val confirmation = store.commit(
                LauncherTransaction(
                    withDormantTree.revision,
                    listOf(
                        LauncherEdit.RemoveRetainedLayout(
                            retained.layoutInstance.id,
                            confirmed = false,
                        ),
                    ),
                ),
            ) as CommitResult.Rejected
            assertEquals(StoreRejectionCode.CONFIRMATION_REQUIRED, confirmation.reason.code)
            assertSame(withDormantTree, confirmation.current)

            val removed = store.commit(
                LauncherTransaction(
                    withDormantTree.revision,
                    listOf(
                        LauncherEdit.RemoveRetainedLayout(
                            retained.layoutInstance.id,
                            confirmed = true,
                        ),
                    ),
                ),
            ).committedState()

            assertEquals(1, removed.destinationLayouts.size)
            assertEquals(instanceId("start-layout"), removed.destinationLayouts.single().layoutInstanceId)
            assertTrue(removed.destinationLayouts.single().selected)
            assertTrue(removed.moduleInstances.none { it.id == retained.layoutInstance.id || it.id == child.instance.id })
            assertTrue(removed.configurationDocuments.none {
                it.id == retained.configuration.id || it.id == child.configuration.id
            })
            assertTrue(removed.placements.none { it.childInstanceId == child.instance.id })
        }

    @Test
    fun `valid drop commits its configuration instance and placement together`() = runTest {
        val store = bootstrappedStore(PlacementPolicy { null })
        val before = store.read()
        val block = samplePlacedModule("clock", index = 0)

        val committed = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(LauncherEdit.CommitDrop(block)),
            ),
        ).committedState()

        assertEquals(StoreRevision.of(before.revision.value + 1), committed.revision)
        assertEquals(block.instance, committed.moduleInstances.single { it.id == block.instance.id })
        assertEquals(block.configuration, committed.configurationDocuments.single {
            it.id == block.configuration.id
        })
        assertEquals(block.placement, committed.placements.single())
    }

    @Test
    fun `moving a drop atomically reorders both siblings`() = runTest {
        val store = bootstrappedStore(PlacementPolicy { null })
        val first = samplePlacedModule("clock", index = 0)
        val second = samplePlacedModule("weather", index = 1)
        val placed = store.commit(
            LauncherTransaction(
                expectedRevision = store.read().revision,
                edits = listOf(
                    LauncherEdit.CommitDrop(first),
                    LauncherEdit.CommitDrop(second),
                ),
            ),
        ).committedState()

        val moved = store.commit(
            LauncherTransaction(
                expectedRevision = placed.revision,
                edits = listOf(
                    LauncherEdit.MoveDrop(
                        placementId = first.placement.id,
                        parentInstanceId = first.placement.parentInstanceId,
                        parentSlotId = first.placement.parentSlotId,
                        index = 1,
                        data = EncodedPlacementData.of("{ moved: true }"),
                        placementSchemaVersion = 2,
                    ),
                ),
            ),
        ).committedState()

        assertEquals(
            listOf(second.placement.id, first.placement.id),
            moved.placements.sortedBy(PlacementRecord::index).map(PlacementRecord::id),
        )
        assertEquals(
            "{ moved: true }",
            moved.placements.single { it.id == first.placement.id }.data.value,
        )
    }

    @Test
    fun `moving a subtree across retained layouts updates every descendant tree identity`() = runTest {
        val store = bootstrappedStore(PlacementPolicy { null })
        val alternate = sampleInstall("start", 0, 0, layoutLocal = "list")
        val parent = samplePlacedModule("parent", 0)
        val childBase = samplePlacedModule("child", 0)
        val child = childBase.copy(
            placement = childBase.placement.copy(parentInstanceId = parent.instance.id),
        )
        val initial = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        alternate.layout,
                        alternate.layoutInstance,
                        alternate.configuration,
                    ),
                    LauncherEdit.CommitDrop(parent),
                    LauncherEdit.CommitDrop(child),
                ),
            ),
        ).committedState()

        val moved = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.MoveDrop(
                        placementId = parent.placement.id,
                        parentInstanceId = alternate.layoutInstance.id,
                        parentSlotId = StableKey.parse("main"),
                        index = 0,
                        data = EncodedPlacementData.of("{ target: 'list' }"),
                        placementSchemaVersion = 2,
                        descendantPlacementDocuments = listOf(
                            PlacementDocumentUpdate(
                                child.placement.id,
                                EncodedPlacementData.of("{ target: 'list-child' }"),
                                3,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        assertEquals(
            setOf(alternate.layoutInstance.id),
            moved.placements
                .filter { it.childInstanceId == parent.instance.id || it.childInstanceId == child.instance.id }
                .map(PlacementRecord::layoutInstanceId)
                .toSet(),
        )
        assertEquals(
            "{ target: 'list-child' }" to 3,
            moved.placements.single { it.childInstanceId == child.instance.id }
                .let { it.data.value to it.placementSchemaVersion },
        )
    }

    @Test
    fun `moving a nested subtree across layouts rejects missing target placement documents`() = runTest {
        val store = bootstrappedStore(PlacementPolicy { null })
        val alternate = sampleInstall("start", 0, 0, layoutLocal = "list")
        val parent = samplePlacedModule("move-parent", 0)
        val childBase = samplePlacedModule("move-child", 0)
        val child = childBase.copy(
            placement = childBase.placement.copy(parentInstanceId = parent.instance.id),
        )
        val initial = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        alternate.layout,
                        alternate.layoutInstance,
                        alternate.configuration,
                    ),
                    LauncherEdit.CommitDrop(parent),
                    LauncherEdit.CommitDrop(child),
                ),
            ),
        ).committedState()

        val result = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.MoveDrop(
                        placementId = parent.placement.id,
                        parentInstanceId = alternate.layoutInstance.id,
                        parentSlotId = StableKey.parse("main"),
                        index = 0,
                        data = EncodedPlacementData.of("{ target: 'list' }"),
                        placementSchemaVersion = 2,
                    ),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.INVALID_REFERENCE, result.reason.code)
        assertSame(initial, result.current)
        assertSame(initial, store.read())
    }

    @Test
    fun `rejected second drop rolls back the complete transaction`() = runTest {
        val policy = PlacementPolicy { request ->
            if (request.child.contributionId.value.endsWith("/weather")) {
                StoreRejection(
                    StoreRejectionCode.INCOMPATIBLE_PLACEMENT,
                    "Weather is incompatible in this fixture",
                )
            } else {
                null
            }
        }
        val store = bootstrappedStore(policy)
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.CommitDrop(samplePlacedModule("clock", 0)),
                    LauncherEdit.CommitDrop(samplePlacedModule("weather", 1)),
                ),
            ),
        )

        assertTrue(result is CommitResult.Rejected)
        assertEquals(
            StoreRejectionCode.INCOMPATIBLE_PLACEMENT,
            (result as CommitResult.Rejected).reason.code,
        )
        assertSame(before, result.current)
        assertSame(before, store.read())
    }

    @Test
    fun `final-state validation rejects an existing resolved placement that became incompatible`() = runTest {
        val seed = bootstrappedStore(PlacementPolicy { null })
        val block = samplePlacedModule("clock", 0)
        val initial = seed.commit(
            LauncherTransaction(seed.read().revision, listOf(LauncherEdit.CommitDrop(block))),
        ).committedState()
        val resolver = ConfigurationResolver { contribution ->
            when (contribution) {
                contributionId("grid") -> configurationLoader(simpleCodec("grid"), ContributionTypes.LAYOUT)
                block.instance.contributionId -> configurationLoader(simpleCodec("clock"), ContributionTypes.BLOCK)
                else -> null
            }
        }
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = resolver,
            placementPolicy = PlacementPolicy {
                StoreRejection(StoreRejectionCode.INCOMPATIBLE_PLACEMENT, "Registry no longer accepts the block")
            },
        )

        val result = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Renamed")),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.INCOMPATIBLE_PLACEMENT, result.reason.code)
        assertSame(initial, result.current)
        assertSame(initial, store.read())
    }

    @Test
    fun `final-state validation preserves placements whose contributions remain unavailable`() = runTest {
        val seed = bootstrappedStore(PlacementPolicy { null })
        val block = samplePlacedModule("clock", 0)
        val initial = seed.commit(
            LauncherTransaction(seed.read().revision, listOf(LauncherEdit.CommitDrop(block))),
        ).committedState()
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { null },
            placementPolicy = PlacementPolicy { error("Unknown placements must not reach policy validation") },
        )

        val committed = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Renamed")),
            ),
        ).committedState()

        assertEquals("Renamed", committed.destinations.single().name)
        assertEquals(initial.placements, committed.placements)
    }

    @Test
    fun `copy creates independent instance placement and configuration identities`() = runTest {
        val store = bootstrappedStore(
            placementPolicy = PlacementPolicy { null },
            configurationResolver = ConfigurationResolver { contribution ->
                when (contribution) {
                    contributionId("grid") -> configurationLoader(simpleCodec("grid"), ContributionTypes.LAYOUT)
                    ContributionId.parse("org.quicklauncher.block/clock") ->
                        configurationLoader(simpleCodec("clock"), ContributionTypes.BLOCK)
                    else -> null
                }
            },
        )
        val source = samplePlacedModule("clock", 0)
        val placed = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.CommitDrop(source)),
            ),
        ).committedState()
        val copiedInstanceId = instanceId("clock-copy")
        val copiedConfigurationId = configurationId("clock-copy")

        val copied = store.commit(
            LauncherTransaction(
                placed.revision,
                listOf(
                    LauncherEdit.CopySubtree(
                        sourcePlacementId = source.placement.id,
                        targetParentInstanceId = source.placement.parentInstanceId,
                        targetSlotId = source.placement.parentSlotId,
                        targetIndex = 1,
                        identities = listOf(
                            ModuleCopyIdentity(
                                sourceInstanceId = source.instance.id,
                                copiedInstanceId = copiedInstanceId,
                                copiedConfigurationDocumentId = copiedConfigurationId,
                                copiedPlacementId = placementId("clock-copy"),
                                copiedPlacementData = EncodedPlacementData.of("{ target: 'grid' }"),
                                copiedPlacementSchemaVersion = 1,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        assertNotEquals(source.instance.id, copiedInstanceId)
        assertNotEquals(source.configuration.id, copiedConfigurationId)
        assertEquals(2, copied.placements.size)
        assertEquals(
            source.configuration.document,
            copied.configurationDocuments.single { it.id == copiedConfigurationId }.document,
        )
        assertEquals(
            "{ target: 'grid' }",
            copied.placements.single { it.childInstanceId == copiedInstanceId }.data.value,
        )

        val changedCopy = source.configuration.document.copy(
            encoded = EncodedConfiguration.of("{ block: 'changed-copy' }"),
        )
        val changed = store.commit(
            LauncherTransaction(
                copied.revision,
                listOf(LauncherEdit.ReplaceConfiguration(copiedConfigurationId, changedCopy)),
            ),
        ).committedState()
        assertEquals(source.configuration.document, changed.configurationDocuments.single {
            it.id == source.configuration.id
        }.document)
        assertEquals(changedCopy, changed.configurationDocuments.single {
            it.id == copiedConfigurationId
        }.document)
    }

    @Test
    fun `cross-layout copy requires target placement documents for the complete subtree`() = runTest {
        val store = bootstrappedStore(PlacementPolicy { null })
        val alternate = sampleInstall("start", 0, 0, layoutLocal = "list")
        val parent = samplePlacedModule("copy-parent", 0)
        val childBase = samplePlacedModule("copy-child", 0)
        val child = childBase.copy(
            placement = childBase.placement.copy(parentInstanceId = parent.instance.id),
        )
        val source = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        alternate.layout,
                        alternate.layoutInstance,
                        alternate.configuration,
                    ),
                    LauncherEdit.CommitDrop(parent),
                    LauncherEdit.CommitDrop(child),
                ),
            ),
        ).committedState()
        val parentCopy = instanceId("copy-parent-target")
        val childCopy = instanceId("copy-child-target")

        val copied = store.commit(
            LauncherTransaction(
                source.revision,
                listOf(
                    LauncherEdit.CopySubtree(
                        sourcePlacementId = parent.placement.id,
                        targetParentInstanceId = alternate.layoutInstance.id,
                        targetSlotId = StableKey.parse("main"),
                        targetIndex = 0,
                        identities = listOf(
                            ModuleCopyIdentity(
                                parent.instance.id,
                                parentCopy,
                                configurationId("copy-parent-target"),
                                placementId("copy-parent-target"),
                                EncodedPlacementData.of("{ target: 'list-root' }"),
                                2,
                            ),
                            ModuleCopyIdentity(
                                child.instance.id,
                                childCopy,
                                configurationId("copy-child-target"),
                                placementId("copy-child-target"),
                                EncodedPlacementData.of("{ target: 'list-child' }"),
                                3,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        val targetPlacements = copied.placements.filter { it.childInstanceId == parentCopy || it.childInstanceId == childCopy }
        assertEquals(setOf(alternate.layoutInstance.id), targetPlacements.map(PlacementRecord::layoutInstanceId).toSet())
        assertEquals(
            "{ target: 'list-root' }" to 2,
            targetPlacements.single { it.childInstanceId == parentCopy }.let { it.data.value to it.placementSchemaVersion },
        )
        assertEquals(
            "{ target: 'list-child' }" to 3,
            targetPlacements.single { it.childInstanceId == childCopy }.let { it.data.value to it.placementSchemaVersion },
        )
    }

    @Test
    fun `copying a widget placement creates an independent pending binding`() = runTest {
        val seed = bootstrappedStore(PlacementPolicy { null })
        val source = samplePlacedModule("widget", 0)
        val placed = seed.commit(
            LauncherTransaction(seed.read().revision, listOf(LauncherEdit.CommitDrop(source))),
        ).committedState()
        val boundWidget = WidgetPlacementRecord(
            moduleInstanceId = source.instance.id,
            appWidgetId = 42,
            providerPackage = PackageName.parse("org.example.widget"),
            providerClassName = "org.example.widget.Provider",
            profile = ProfileSerial.of(7),
            intendedWidthDp = 200,
            intendedHeightDp = 120,
            bindState = WidgetBindState.BOUND,
            restoreState = WidgetRestoreState.READY,
        )
        val initial = copySnapshot(placed, widgetPlacements = listOf(boundWidget))
        val store: LauncherStore = InMemoryLauncherStore(initial, placementPolicy = PlacementPolicy { null })
        val copiedInstance = instanceId("widget-copy")

        val copied = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.CopySubtree(
                        source.placement.id,
                        source.placement.parentInstanceId,
                        source.placement.parentSlotId,
                        1,
                        listOf(
                            ModuleCopyIdentity(
                                source.instance.id,
                                copiedInstance,
                                configurationId("widget-copy"),
                                placementId("widget-copy"),
                                EncodedPlacementData.of("{}"),
                                1,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        val copiedWidget = copied.widgetPlacements.single { it.moduleInstanceId == copiedInstance }
        assertSame(null, copiedWidget.appWidgetId)
        assertEquals(WidgetBindState.PENDING, copiedWidget.bindState)
        assertEquals(WidgetRestoreState.REBIND_REQUIRED, copiedWidget.restoreState)
        assertEquals(boundWidget.providerPackage, copiedWidget.providerPackage)
        assertEquals(boundWidget.profile, copiedWidget.profile)
    }

    @Test
    fun `copying a configuration-quarantined block preserves its error and raw bytes`() = runTest {
        val seed = bootstrappedStore(PlacementPolicy { null })
        val source = samplePlacedModule("quarantined-copy", 0)
        val placed = seed.commit(
            LauncherTransaction(seed.read().revision, listOf(LauncherEdit.CommitDrop(source))),
        ).committedState()
        val quarantine = ModuleInstanceStatus.Quarantined(
            code = "configuration.decode-failed",
            message = "Saved configuration could not be decoded",
            origin = ModuleQuarantineOrigin.CONFIGURATION,
        )
        val raw = EncodedConfiguration.of("{ definitely-not-valid")
        val initial = copySnapshot(
            placed,
            moduleInstances = placed.moduleInstances.map { instance ->
                if (instance.id == source.instance.id) instance.copy(status = quarantine) else instance
            },
            configurationDocuments = placed.configurationDocuments.map { stored ->
                if (stored.id == source.configuration.id) {
                    stored.copy(
                        document = stored.document.copy(encoded = raw),
                        lastMigrationErrorCode = quarantine.code,
                    )
                } else {
                    stored
                }
            },
        )
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            placementPolicy = PlacementPolicy { null },
        )
        val copiedInstanceId = instanceId("quarantined-copy-target")
        val copiedConfigurationId = configurationId("quarantined-copy-target")

        val committed = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.CopySubtree(
                        sourcePlacementId = source.placement.id,
                        targetParentInstanceId = source.placement.parentInstanceId,
                        targetSlotId = source.placement.parentSlotId,
                        targetIndex = 1,
                        identities = listOf(
                            ModuleCopyIdentity(
                                source.instance.id,
                                copiedInstanceId,
                                copiedConfigurationId,
                                placementId("quarantined-copy-target"),
                                EncodedPlacementData.of("{}"),
                                1,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        val copiedInstance = committed.moduleInstances.single { it.id == copiedInstanceId }
        val copiedConfiguration = committed.configurationDocuments.single { it.id == copiedConfigurationId }
        assertEquals(quarantine, copiedInstance.status)
        assertEquals(raw, copiedConfiguration.document.encoded)
        assertEquals(quarantine.code, copiedConfiguration.lastMigrationErrorCode)
        assertEquals(
            raw,
            committed.configurationDocuments.single { it.id == source.configuration.id }.document.encoded,
        )
        assertEquals(quarantine, committed.moduleInstances.single { it.id == source.instance.id }.status)
    }

    @Test
    fun `copying a renderer-quarantined block starts a fresh active instance`() = runTest {
        val seed = bootstrappedStore(PlacementPolicy { null })
        val source = samplePlacedModule("renderer-copy", 0)
        val placed = seed.commit(
            LauncherTransaction(seed.read().revision, listOf(LauncherEdit.CommitDrop(source))),
        ).committedState()
        val quarantine = ModuleInstanceStatus.Quarantined(
            code = "renderer.crashed",
            message = "Source renderer crashed",
            origin = ModuleQuarantineOrigin.RENDERER,
        )
        val initial = copySnapshot(
            placed,
            moduleInstances = placed.moduleInstances.map { instance ->
                if (instance.id == source.instance.id) instance.copy(status = quarantine) else instance
            },
        )
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            placementPolicy = PlacementPolicy { null },
        )
        val copiedInstanceId = instanceId("renderer-copy-target")

        val committed = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.CopySubtree(
                        source.placement.id,
                        source.placement.parentInstanceId,
                        source.placement.parentSlotId,
                        1,
                        listOf(
                            ModuleCopyIdentity(
                                source.instance.id,
                                copiedInstanceId,
                                configurationId("renderer-copy-target"),
                                placementId("renderer-copy-target"),
                                EncodedPlacementData.of("{}"),
                                1,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        assertEquals(
            ModuleInstanceStatus.Active,
            committed.moduleInstances.single { it.id == copiedInstanceId }.status,
        )
        assertEquals(quarantine, committed.moduleInstances.single { it.id == source.instance.id }.status)
    }

    @Test
    fun `copied migration error takes precedence over source renderer quarantine`() = runTest {
        val seed = bootstrappedStore(PlacementPolicy { null })
        val source = samplePlacedModule("mixed-quarantine-copy", 0)
        val placed = seed.commit(
            LauncherTransaction(seed.read().revision, listOf(LauncherEdit.CommitDrop(source))),
        ).committedState()
        val rendererQuarantine = ModuleInstanceStatus.Quarantined(
            code = "renderer.crashed",
            message = "Source renderer crashed",
            origin = ModuleQuarantineOrigin.RENDERER,
        )
        val migrationError = "configuration.migration-failed"
        val raw = EncodedConfiguration.of("unmigrated bytes")
        val initial = copySnapshot(
            placed,
            moduleInstances = placed.moduleInstances.map { instance ->
                if (instance.id == source.instance.id) {
                    instance.copy(status = rendererQuarantine)
                } else {
                    instance
                }
            },
            configurationDocuments = placed.configurationDocuments.map { stored ->
                if (stored.id == source.configuration.id) {
                    stored.copy(
                        document = stored.document.copy(encoded = raw),
                        lastMigrationErrorCode = migrationError,
                    )
                } else {
                    stored
                }
            },
        )
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            placementPolicy = PlacementPolicy { null },
        )
        val copiedInstanceId = instanceId("mixed-quarantine-copy-target")
        val copiedConfigurationId = configurationId("mixed-quarantine-copy-target")

        val committed = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.CopySubtree(
                        source.placement.id,
                        source.placement.parentInstanceId,
                        source.placement.parentSlotId,
                        1,
                        listOf(
                            ModuleCopyIdentity(
                                source.instance.id,
                                copiedInstanceId,
                                copiedConfigurationId,
                                placementId("mixed-quarantine-copy-target"),
                                EncodedPlacementData.of("{}"),
                                1,
                            ),
                        ),
                    ),
                ),
            ),
        ).committedState()

        val copiedStatus = committed.moduleInstances.single { it.id == copiedInstanceId }.status
            as ModuleInstanceStatus.Quarantined
        assertEquals(ModuleQuarantineOrigin.CONFIGURATION, copiedStatus.origin)
        assertEquals(migrationError, copiedStatus.code)
        assertTrue(copiedStatus.message.isNotBlank())
        val copiedConfiguration = committed.configurationDocuments.single { it.id == copiedConfigurationId }
        assertEquals(raw, copiedConfiguration.document.encoded)
        assertEquals(migrationError, copiedConfiguration.lastMigrationErrorCode)
        assertEquals(
            rendererQuarantine,
            committed.moduleInstances.single { it.id == source.instance.id }.status,
        )
    }

    @Test
    fun `future replacement configuration is rejected without clearing prior failure state`() = runTest {
        val initial = configurationQuarantinedSnapshot()
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { contribution ->
                if (contribution == contributionId("grid")) {
                    configurationLoader(simpleCodec("grid"), ContributionTypes.LAYOUT)
                } else {
                    null
                }
            },
        )
        val replacement = initial.configurationDocuments.single().document.copy(
            schemaVersion = SchemaVersion.of(2),
            encoded = EncodedConfiguration.of("future"),
        )

        val result = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.ReplaceConfiguration(
                        initial.configurationDocuments.single().id,
                        replacement,
                    ),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONFIGURATION_INVALID, result.reason.code)
        assertSame(initial, result.current)
        assertSame(initial, store.read())
    }

    @Test
    fun `malformed replacement configuration is rejected without clearing prior failure state`() = runTest {
        val initial = configurationQuarantinedSnapshot()
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { contribution ->
                if (contribution == contributionId("grid")) {
                    configurationLoader(failingDecodeCodec(), ContributionTypes.LAYOUT)
                } else {
                    null
                }
            },
        )
        val replacement = initial.configurationDocuments.single().document.copy(
            encoded = EncodedConfiguration.of("malformed"),
        )

        val result = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(LauncherEdit.ReplaceConfiguration(configurationId("start-layout"), replacement)),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONFIGURATION_INVALID, result.reason.code)
        assertSame(initial, result.current)
        assertSame(initial, store.read())
    }

    @Test
    fun `failed replacement migration preserves original configuration and quarantine`() = runTest {
        val initial = configurationQuarantinedSnapshot()
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { contribution ->
                if (contribution == contributionId("grid")) {
                    configurationLoader(migrationCodec(fail = true), ContributionTypes.LAYOUT)
                } else {
                    null
                }
            },
        )

        val result = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.ReplaceConfiguration(
                        configurationId("start-layout"),
                        initial.configurationDocuments.single().document,
                    ),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONFIGURATION_INVALID, result.reason.code)
        assertSame(initial, result.current)
        assertSame(initial, store.read())
    }

    @Test
    fun `replacement migration cancellation propagates without changing state`() = runTest {
        val initial = configurationQuarantinedSnapshot()
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { contribution ->
                if (contribution == contributionId("grid")) {
                    configurationLoader(migrationCodec(cancel = true), ContributionTypes.LAYOUT)
                } else {
                    null
                }
            },
        )

        try {
            store.commit(
                LauncherTransaction(
                    initial.revision,
                    listOf(
                        LauncherEdit.ReplaceConfiguration(
                            configurationId("start-layout"),
                            initial.configurationDocuments.single().document,
                        ),
                    ),
                ),
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected control flow.
        }

        assertSame(initial, store.read())
    }

    @Test
    fun `valid replacement migrates before clearing configuration quarantine`() = runTest {
        val initial = configurationQuarantinedSnapshot()
        val store: LauncherStore = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { contribution ->
                if (contribution == contributionId("grid")) {
                    configurationLoader(migrationCodec(), ContributionTypes.LAYOUT)
                } else {
                    null
                }
            },
        )

        val committed = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.ReplaceConfiguration(
                        configurationId("start-layout"),
                        initial.configurationDocuments.single().document.copy(
                            encoded = EncodedConfiguration.of("replacement"),
                        ),
                    ),
                ),
            ),
        ).committedState()

        val stored = committed.configurationDocuments.single()
        assertEquals(SchemaVersion.of(2), stored.document.schemaVersion)
        assertEquals("migrated:replacement", stored.document.encoded.value)
        assertSame(null, stored.lastMigrationErrorCode)
        assertEquals(ModuleInstanceStatus.Active, committed.moduleInstances.single().status)
    }

    @Test
    fun `failed configuration migration preserves raw bytes and quarantines only its instance`() = runTest {
        val resolver = ConfigurationResolver { contribution ->
            if (contribution == contributionId("grid")) configurationLoader(migrationCodec(fail = true)) else null
        }
        val store = bootstrappedStore(configurationResolver = resolver)
        val before = store.read()
        val original = before.configurationDocuments.single().document

        val reconciliation = store.reconcileConfigurations()

        assertEquals(1, reconciliation.failures.size)
        assertEquals(original, reconciliation.state.configurationDocuments.single().document)
        assertTrue(reconciliation.state.moduleInstances.single().status is ModuleInstanceStatus.Quarantined)
        assertEquals(StoreRevision.of(before.revision.value + 1), reconciliation.state.revision)
    }

    @Test
    fun `successful configuration migration replaces the document after decode`() = runTest {
        val resolver = ConfigurationResolver { contribution ->
            if (contribution == contributionId("grid")) configurationLoader(migrationCodec()) else null
        }
        val store = bootstrappedStore(configurationResolver = resolver)

        val reconciliation = store.reconcileConfigurations()

        val stored = reconciliation.state.configurationDocuments.single()
        assertEquals(SchemaVersion.of(2), stored.document.schemaVersion)
        assertEquals("migrated:{ layout: 'start' }", stored.document.encoded.value)
        assertEquals(listOf(stored.id), reconciliation.migrated)
        assertEquals(ModuleInstanceStatus.Active, reconciliation.state.moduleInstances.single().status)
    }

    @Test
    fun `known layout with a different registered configuration type is rejected`() = runTest {
        val resolver = ConfigurationResolver { contribution ->
            if (contribution == contributionId("grid")) {
                configurationLoader(simpleCodec("other"), ContributionTypes.LAYOUT)
            } else {
                null
            }
        }
        val store = InMemoryLauncherStore(configurationResolver = resolver)

        val rejected = store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(LauncherEdit.Bootstrap(sampleInstall("start", 0, 0))),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH, rejected.reason.code)
        assertEquals(LauncherSnapshot.empty(), store.read())
    }

    @Test
    fun `known block cannot be retained as a layout`() = runTest {
        val resolver = ConfigurationResolver { contribution ->
            if (contribution == contributionId("grid")) {
                configurationLoader(simpleCodec("grid"), ContributionTypes.BLOCK)
            } else {
                null
            }
        }
        val store = InMemoryLauncherStore(configurationResolver = resolver)

        val rejected = store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(LauncherEdit.Bootstrap(sampleInstall("start", 0, 0))),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONTRIBUTION_CATEGORY_MISMATCH, rejected.reason.code)
    }

    @Test
    fun `known placed block with a different registered configuration type is rejected`() = runTest {
        val blockContribution = ContributionId.parse("org.quicklauncher.block/clock")
        val resolver = ConfigurationResolver { contribution ->
            when (contribution) {
                contributionId("grid") -> configurationLoader(
                    simpleCodec("grid"),
                    ContributionTypes.LAYOUT,
                )
                blockContribution -> configurationLoader(
                    simpleCodec("different-block"),
                    ContributionTypes.BLOCK,
                )
                else -> null
            }
        }
        val store = bootstrappedStore(PlacementPolicy { null }, resolver)
        val before = store.read()

        val rejected = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(LauncherEdit.CommitDrop(samplePlacedModule("clock", 0))),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH, rejected.reason.code)
        assertSame(before, rejected.current)
    }

    @Test
    fun `configuration success never clears renderer quarantine`() = runTest {
        val resolver = ConfigurationResolver { contribution ->
            if (contribution == contributionId("grid")) configurationLoader(migrationCodec()) else null
        }
        val install = sampleInstall("start", 0, 0)
        val rendererStatus = ModuleInstanceStatus.Quarantined(
            code = "renderer.crashed",
            message = "Renderer failed during restore",
            origin = ModuleQuarantineOrigin.RENDERER,
        )
        val store = InMemoryLauncherStore(configurationResolver = resolver)
        store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(
                    LauncherEdit.Bootstrap(
                        install.copy(layoutInstance = install.layoutInstance.copy(status = rendererStatus)),
                    ),
                ),
            ),
        )

        val reconciled = store.reconcileConfigurations().state
        assertEquals(rendererStatus, reconciled.moduleInstances.single().status)

        val replaced = reconciled.configurationDocuments.single().document.copy(
            encoded = EncodedConfiguration.of("migrated:settings-edit"),
        )
        val committed = store.commit(
            LauncherTransaction(
                reconciled.revision,
                listOf(
                    LauncherEdit.ReplaceConfiguration(
                        reconciled.configurationDocuments.single().id,
                        replaced,
                    ),
                ),
            ),
        ).committedState()
        assertEquals(rendererStatus, committed.moduleInstances.single().status)
    }

    @Test
    fun `loader returning a different type preserves original data and quarantines configuration`() = runTest {
        val malicious = object : ConfigurationLoader {
            override val contributionTypeId = ContributionTypes.LAYOUT
            override val configType = ConfigTypeId.parse("org.quicklauncher.config/grid")
            override val currentSchemaVersion = SchemaVersion.of(1)

            override fun load(document: ConfigurationDocument): ConfigurationLoadResult<*> =
                ConfigurationLoadResult.Loaded(
                    "bad",
                    document.copy(configType = ConfigTypeId.parse("org.quicklauncher.config/other")),
                )
        }
        val store = bootstrappedStore(
            configurationResolver = ConfigurationResolver { malicious },
        )
        val before = store.read()

        val reconciled = store.reconcileConfigurations()

        assertEquals(before.configurationDocuments.single().document, reconciled.state.configurationDocuments.single().document)
        assertEquals(
            "configuration.loader-type-mismatch",
            reconciled.state.configurationDocuments.single().lastMigrationErrorCode,
        )
        val quarantine = reconciled.state.moduleInstances.single().status as ModuleInstanceStatus.Quarantined
        assertEquals(ModuleQuarantineOrigin.CONFIGURATION, quarantine.origin)
    }

    @Test
    fun `unknown reference prevents mutation of a shared configuration document`() = runTest {
        val base = bootstrappedStore().read()
        val root = base.moduleInstances.single()
        val unknown = root.copy(
            id = instanceId("unknown-clone"),
            contributionId = ContributionId.parse("org.quicklauncher.layout/missing"),
        )
        val initial = copySnapshot(base, moduleInstances = base.moduleInstances + unknown)
        val store = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { contribution ->
                if (contribution == root.contributionId) configurationLoader(migrationCodec()) else null
            },
        )

        val reconciliation = store.reconcileConfigurations()

        assertSame(initial, reconciliation.state)
        assertEquals(listOf(initial.configurationDocuments.single().id), reconciliation.unknown)
    }

    @Test
    fun `same contribution clones migrate one shared configuration and keep their stable reference`() = runTest {
        val base = bootstrappedStore().read()
        val root = base.moduleInstances.single()
        val clone = root.copy(id = instanceId("grid-clone"))
        val initial = copySnapshot(base, moduleInstances = base.moduleInstances + clone)
        val store = InMemoryLauncherStore(
            initialState = initial,
            configurationResolver = ConfigurationResolver { configurationLoader(migrationCodec()) },
        )

        val reconciliation = store.reconcileConfigurations()

        assertEquals(1, reconciliation.migrated.size)
        assertEquals(SchemaVersion.of(2), reconciliation.state.configurationDocuments.single().document.schemaVersion)
        assertEquals(
            setOf(root.configurationDocumentId),
            reconciliation.state.moduleInstances.map(ModuleInstanceRecord::configurationDocumentId).toSet(),
        )
    }

    @Test
    fun `different contributions cannot share one configuration document`() = runTest {
        val base = bootstrappedStore().read()
        val root = base.moduleInstances.single()
        val invalid = root.copy(
            id = instanceId("different-clone"),
            contributionId = ContributionId.parse("org.quicklauncher.layout/different"),
        )
        val initial = copySnapshot(base, moduleInstances = base.moduleInstances + invalid)
        val store = InMemoryLauncherStore(initialState = initial)

        val rejected = store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Still start")),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH, rejected.reason.code)
        assertSame(initial, rejected.current)
    }

    @Test
    fun `configuration cancellation propagates without changing state`() = runTest {
        val resolver = ConfigurationResolver { contribution ->
            if (contribution == contributionId("grid")) {
                configurationLoader(migrationCodec(cancel = true))
            } else {
                null
            }
        }
        val store = bootstrappedStore(configurationResolver = resolver)
        val before = store.read()

        try {
            store.reconcileConfigurations()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected control flow.
        }

        assertSame(before, store.read())
    }

    @Test
    fun `concurrent writers using one revision produce one commit and one stale rejection`() = runTest {
        val store = bootstrappedStore()
        val before = store.read()

        val results = listOf("First", "Second").map { name ->
            async {
                store.commit(
                    LauncherTransaction(
                        before.revision,
                        listOf(LauncherEdit.RenameDestination(destinationId("start"), name)),
                    ),
                )
            }
        }.awaitAll()

        assertEquals(1, results.count { it is CommitResult.Committed })
        assertEquals(1, results.count {
            it is CommitResult.Rejected && it.reason.code == StoreRejectionCode.STALE_REVISION
        })
        assertEquals(StoreRevision.of(before.revision.value + 1), store.read().revision)
    }

    @Test
    fun `group move and confirmed start deletion validate their final map state`() = runTest {
        val store = bootstrappedStore()
        val installed = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.InstallDestination(sampleInstall("right", 1, 0))),
            ),
        ).committedState()

        val moved = store.commit(
            LauncherTransaction(
                installed.revision,
                listOf(
                    LauncherEdit.MoveDestinations(
                        destinationIds = listOf(destinationId("start"), destinationId("right")),
                        vector = DestinationVector(10, -5),
                    ),
                ),
            ),
        ).committedState()
        assertEquals(
            DestinationCoordinate(11, -5),
            moved.destinations.single { it.id == destinationId("right") }.coordinate,
        )

        val deleted = store.commit(
            LauncherTransaction(
                moved.revision,
                listOf(
                    LauncherEdit.SetStartDestination(destinationId("right")),
                    LauncherEdit.DeleteDestination(destinationId("start"), confirmed = true),
                ),
            ),
        ).committedState()
        assertEquals(destinationId("right"), deleted.startDestinationId)
        assertEquals(listOf(destinationId("right")), deleted.destinations.map(DestinationRecord::id))
    }

    @Test
    fun `persisted group move uses connected rigid spatial semantics and checked coordinates`() = runTest {
        val store = bootstrappedStore()
        val three = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.InstallDestination(sampleInstall("middle", 1, 0)),
                    LauncherEdit.InstallDestination(sampleInstall("right", 2, 0)),
                ),
            ),
        ).committedState()

        val disconnected = store.commit(
            LauncherTransaction(
                three.revision,
                listOf(
                    LauncherEdit.MoveDestinations(
                        listOf(destinationId("start"), destinationId("right")),
                        DestinationVector(0, 1),
                    ),
                ),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.DESTINATION_GROUP_DISCONNECTED, disconnected.reason.code)
        assertSame(three, disconnected.current)

        val overflowInstall = sampleInstall("overflow", Long.MAX_VALUE, 0)
        val overflowState = LauncherSnapshot(
            revision = StoreRevision.ZERO,
            startDestinationId = overflowInstall.destination.id,
            destinations = listOf(overflowInstall.destination),
            destinationLayouts = listOf(overflowInstall.layout),
            moduleInstances = listOf(overflowInstall.layoutInstance),
            configurationDocuments = listOf(overflowInstall.configuration),
            placements = emptyList(),
        )
        val overflowStore = InMemoryLauncherStore(initialState = overflowState)
        val overflow = overflowStore.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(
                    LauncherEdit.MoveDestinations(
                        listOf(overflowInstall.destination.id),
                        DestinationVector(1, 0),
                    ),
                ),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.COORDINATE_OVERFLOW, overflow.reason.code)
    }

    @Test
    fun `repeated store transactions preserve spatial invariants through the shared kernel`() = runTest {
        val store = bootstrappedStore()
        var state = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.InstallDestination(sampleInstall("middle", 1, 0)),
                    LauncherEdit.InstallDestination(sampleInstall("right", 2, 0)),
                ),
            ),
        ).committedState()
        val allIds = state.destinations.map(DestinationRecord::id)

        repeat(250) { step ->
            val vector = DestinationVector(
                deltaX = if (step % 2 == 0) 1 else -1,
                deltaY = if (step % 3 == 0) 1 else -1,
            )
            state = store.commit(
                LauncherTransaction(
                    state.revision,
                    listOf(LauncherEdit.MoveDestinations(allIds, vector)),
                ),
            ).committedState()
            assertEquals(state.destinations.size, state.destinations.map(DestinationRecord::coordinate).toSet().size)
            val coordinates = state.destinations.sortedBy { it.coordinate.x }
            assertTrue(coordinates.zipWithNext().all { (left, right) ->
                left.coordinate.isCardinalNeighborOf(right.coordinate)
            })
        }
    }

    @Test
    fun `kernel rejects duplicate folder positions members and widget instances before persistence`() = runTest {
        val base = bootstrappedStore().read()
        val folderId = ContentItemId.parse("org.quicklauncher.content/folder")
        val firstId = ContentItemId.parse("org.quicklauncher.content/first")
        val secondId = ContentItemId.parse("org.quicklauncher.content/second")
        val content = listOf(
            ContentItemRecord(folderId, ContentItemKind.FOLDER, "folder"),
            ContentItemRecord(firstId, ContentItemKind.FAVORITE, "first"),
            ContentItemRecord(secondId, ContentItemKind.FAVORITE, "second"),
        )
        val duplicatePosition = copySnapshot(
            base,
            contentItems = content,
            folderMembers = listOf(
                FolderMemberRecord(folderId, firstId, 0),
                FolderMemberRecord(folderId, secondId, 0),
            ),
        )
        val positionResult = InMemoryLauncherStore(duplicatePosition).commit(
            LauncherTransaction(
                duplicatePosition.revision,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Start renamed")),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.DUPLICATE_ID, positionResult.reason.code)

        val duplicateMember = copySnapshot(
            base,
            contentItems = content,
            folderMembers = listOf(
                FolderMemberRecord(folderId, firstId, 0),
                FolderMemberRecord(folderId, firstId, 1),
            ),
        )
        val memberResult = InMemoryLauncherStore(duplicateMember).commit(
            LauncherTransaction(
                duplicateMember.revision,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Start renamed")),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.DUPLICATE_ID, memberResult.reason.code)

        val widget = WidgetPlacementRecord(
            moduleInstanceId = base.moduleInstances.single().id,
            appWidgetId = 1,
            providerPackage = PackageName.parse("org.example.widget"),
            providerClassName = "org.example.widget.Provider",
            profile = ProfileSerial.of(10),
            intendedWidthDp = 100,
            intendedHeightDp = 100,
            bindState = WidgetBindState.BOUND,
            restoreState = WidgetRestoreState.READY,
        )
        val duplicateWidget = copySnapshot(base, widgetPlacements = listOf(widget, widget.copy(appWidgetId = 2)))
        val widgetResult = InMemoryLauncherStore(duplicateWidget).commit(
            LauncherTransaction(
                duplicateWidget.revision,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Start renamed")),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.DUPLICATE_ID, widgetResult.reason.code)
    }

    @Test
    fun `destructive removal requires confirmation and deletes a placed subtree atomically`() = runTest {
        val store = bootstrappedStore(PlacementPolicy { null })
        val block = samplePlacedModule("clock", 0)
        val placed = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.CommitDrop(block)),
            ),
        ).committedState()

        val rejected = store.commit(
            LauncherTransaction(
                placed.revision,
                listOf(LauncherEdit.RemovePlacedSubtree(block.placement.id, confirmed = false)),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.CONFIRMATION_REQUIRED, rejected.reason.code)
        assertSame(placed, rejected.current)

        val removed = store.commit(
            LauncherTransaction(
                placed.revision,
                listOf(LauncherEdit.RemovePlacedSubtree(block.placement.id, confirmed = true)),
            ),
        ).committedState()
        assertTrue(removed.placements.isEmpty())
        assertTrue(removed.moduleInstances.none { it.id == block.instance.id })
        assertTrue(removed.configurationDocuments.none { it.id == block.configuration.id })
    }

    @Test
    fun `unknown contribution configuration remains byte exact and does not advance revision`() = runTest {
        val store = bootstrappedStore()
        val before = store.read()

        val reconciliation = store.reconcileConfigurations()

        assertSame(before, reconciliation.state)
        assertEquals(listOf(before.configurationDocuments.single().id), reconciliation.unknown)
        assertEquals("{ layout: 'start' }", reconciliation.state.configurationDocuments.single().document.encoded.value)
    }

    @Test
    fun `snapshot and transaction collections reject caller mutation`() = runTest {
        val edits = mutableListOf<LauncherEdit>(LauncherEdit.Bootstrap(sampleInstall("start", 0, 0)))
        val transaction = LauncherTransaction(StoreRevision.ZERO, edits)
        edits.clear()

        val store = InMemoryLauncherStore()
        val committed = store.commit(transaction).committedState()

        assertEquals(1, transaction.edits.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (committed.destinations as MutableList).clear()
        }
        assertEquals(1, store.read().destinations.size)

        val affected = mutableListOf(instanceId("mutable"))
        val failure = ConfigurationFailure(
            configurationId("mutable"),
            affected,
            org.quicklauncher.contracts.contribution.ConfigurationError("test.failed", "Failure"),
        )
        affected.clear()
        assertEquals(1, failure.affectedInstances.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (failure.affectedInstances as MutableList).clear()
        }
    }

    @Test
    fun `stale transaction returns the exact current state`() = runTest {
        val store = bootstrappedStore()
        val current = store.read()

        val rejected = store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(LauncherEdit.RenameDestination(destinationId("start"), "Stale")),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.STALE_REVISION, rejected.reason.code)
        assertSame(current, rejected.current)
        assertSame(current, store.read())
    }

    @Test
    fun `widget binding edits preserve one id and remove failed allocations durably`() = runTest {
        val placed = bootstrappedStore(placementPolicy = PlacementPolicy { null })
        val withBlock = placed.commit(
            LauncherTransaction(placed.read().revision, listOf(LauncherEdit.CommitDrop(samplePlacedModule("widget", 0)))),
        ).committedState()
        val pending = WidgetPlacementRecord(
            moduleInstanceId = instanceId("widget"),
            appWidgetId = 41,
            providerPackage = PackageName.parse("org.example.widgets"),
            providerClassName = "org.example.widgets.Clock",
            profile = ProfileSerial.of(0),
            intendedWidthDp = 180,
            intendedHeightDp = 120,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.READY,
        )

        val begun = placed.commit(
            LauncherTransaction(withBlock.revision, listOf(LauncherEdit.BeginWidgetBinding(pending))),
        ).committedState()
        val bound = placed.commit(
            LauncherTransaction(
                begun.revision,
                listOf(LauncherEdit.CompleteWidgetBinding(instanceId("widget"))),
            ),
        ).committedState()
        assertEquals(WidgetBindState.BOUND, bound.widgetPlacements.single().bindState)

        val resized = placed.commit(
            LauncherTransaction(
                bound.revision,
                listOf(LauncherEdit.UpdateWidgetSize(instanceId("widget"), 240, 160)),
            ),
        ).committedState()
        assertEquals(240, resized.widgetPlacements.single().intendedWidthDp)

        val cleanupPending = placed.commit(
            LauncherTransaction(
                resized.revision,
                listOf(LauncherEdit.BeginWidgetDeletion(instanceId("widget"))),
            ),
        ).committedState()
        val deleted = placed.commit(
            LauncherTransaction(
                cleanupPending.revision,
                listOf(LauncherEdit.CompleteWidgetDeletion(instanceId("widget"))),
            ),
        ).committedState()
        assertTrue(deleted.widgetPlacements.isEmpty())
    }

    @Test
    fun `allocated widget ids cannot bypass coordinated cleanup`() = runTest {
        val store = bootstrappedStore(placementPolicy = PlacementPolicy { null })
        val block = samplePlacedModule("widget", 0)
        val withBlock = store.commit(
            LauncherTransaction(store.read().revision, listOf(LauncherEdit.CommitDrop(block))),
        ).committedState()
        val pending = WidgetPlacementRecord(
            moduleInstanceId = block.instance.id,
            appWidgetId = 91,
            providerPackage = PackageName.parse("org.example.widgets"),
            providerClassName = "org.example.widgets.Clock",
            profile = ProfileSerial.of(0),
            intendedWidthDp = 180,
            intendedHeightDp = 120,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.READY,
        )
        val begun = store.commit(
            LauncherTransaction(withBlock.revision, listOf(LauncherEdit.BeginWidgetBinding(pending))),
        ).committedState()

        listOf<LauncherEdit>(
            LauncherEdit.DeleteWidgetPlacement(block.instance.id),
            LauncherEdit.RemovePlacedSubtree(block.placement.id, confirmed = true),
        ).forEach { edit ->
            val rejected = store.commit(
                LauncherTransaction(begun.revision, listOf(edit)),
            ) as CommitResult.Rejected
            assertEquals(StoreRejectionCode.RECOVERY_STATE_MISMATCH, rejected.reason.code)
            assertSame(begun, rejected.current)
            assertSame(begun, store.read())
        }

        val cleanupPending = store.commit(
            LauncherTransaction(begun.revision, listOf(LauncherEdit.BeginWidgetDeletion(block.instance.id))),
        ).committedState()
        listOf<LauncherEdit>(
            LauncherEdit.CompleteWidgetBinding(block.instance.id),
            LauncherEdit.CancelWidgetBinding(block.instance.id),
            LauncherEdit.DeleteWidgetPlacement(block.instance.id),
        ).forEach { edit ->
            val rejected = store.commit(
                LauncherTransaction(cleanupPending.revision, listOf(edit)),
            ) as CommitResult.Rejected
            assertEquals(StoreRejectionCode.RECOVERY_STATE_MISMATCH, rejected.reason.code)
            assertSame(cleanupPending, rejected.current)
            assertSame(cleanupPending, store.read())
        }
    }

    @Test
    fun `destination and retained layout deletion reject allocated descendant widgets`() = runTest {
        suspend fun addAllocatedWidget(
            store: InMemoryLauncherStore,
            block: NewPlacedModule,
        ): LauncherSnapshot {
            val placed = store.commit(
                LauncherTransaction(store.read().revision, listOf(LauncherEdit.CommitDrop(block))),
            ).committedState()
            return store.commit(
                LauncherTransaction(
                    placed.revision,
                    listOf(
                        LauncherEdit.BeginWidgetBinding(
                            WidgetPlacementRecord(
                                moduleInstanceId = block.instance.id,
                                appWidgetId = 92,
                                providerPackage = PackageName.parse("org.example.widgets"),
                                providerClassName = "org.example.widgets.Clock",
                                profile = ProfileSerial.of(0),
                                intendedWidthDp = 180,
                                intendedHeightDp = 120,
                                bindState = WidgetBindState.PENDING,
                                restoreState = WidgetRestoreState.READY,
                            ),
                        ),
                    ),
                ),
            ).committedState()
        }

        val destinationStore = bootstrappedStore(placementPolicy = PlacementPolicy { null })
        val installed = destinationStore.commit(
            LauncherTransaction(
                destinationStore.read().revision,
                listOf(LauncherEdit.InstallDestination(sampleInstall("right", 1, 0))),
            ),
        ).committedState()
        val destinationBlockFixture = samplePlacedModule("destination-widget", 0)
        val destinationBlock = NewPlacedModule(
            destinationBlockFixture.instance,
            destinationBlockFixture.configuration,
            destinationBlockFixture.placement.copy(
                layoutInstanceId = instanceId("right-layout"),
                parentInstanceId = instanceId("right-layout"),
            ),
        )
        val withDestinationWidget = addAllocatedWidget(destinationStore, destinationBlock)
        val destinationRejected = destinationStore.commit(
            LauncherTransaction(
                withDestinationWidget.revision,
                listOf(LauncherEdit.DeleteDestination(destinationId("right"), confirmed = true)),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.RECOVERY_STATE_MISMATCH, destinationRejected.reason.code)
        assertSame(withDestinationWidget, destinationRejected.current)
        assertSame(withDestinationWidget, destinationStore.read())
        assertEquals(installed.destinations.size, 2)

        val retainedStore = bootstrappedStore(placementPolicy = PlacementPolicy { null })
        val retainedFixture = sampleInstall("start", 0, 0, layoutLocal = "list")
        retainedStore.commit(
            LauncherTransaction(
                retainedStore.read().revision,
                listOf(LauncherEdit.RetainLayout(
                    retainedFixture.layout,
                    retainedFixture.layoutInstance,
                    retainedFixture.configuration,
                )),
            ),
        ).committedState()
        val retainedBlockFixture = samplePlacedModule("retained-widget", 0)
        val retainedBlock = NewPlacedModule(
            retainedBlockFixture.instance,
            retainedBlockFixture.configuration,
            retainedBlockFixture.placement.copy(
                layoutInstanceId = retainedFixture.layoutInstance.id,
                parentInstanceId = retainedFixture.layoutInstance.id,
            ),
        )
        val withRetainedWidget = addAllocatedWidget(retainedStore, retainedBlock)
        val retainedRejected = retainedStore.commit(
            LauncherTransaction(
                withRetainedWidget.revision,
                listOf(LauncherEdit.RemoveRetainedLayout(retainedFixture.layoutInstance.id, confirmed = true)),
            ),
        ) as CommitResult.Rejected
        assertEquals(StoreRejectionCode.RECOVERY_STATE_MISMATCH, retainedRejected.reason.code)
        assertSame(withRetainedWidget, retainedRejected.current)
        assertSame(withRetainedWidget, retainedStore.read())
    }

    @Test
    fun `widget deletion remains durable until framework cleanup completes`() = runTest {
        val store = bootstrappedStore(placementPolicy = PlacementPolicy { null })
        val withBlock = store.commit(
            LauncherTransaction(store.read().revision, listOf(LauncherEdit.CommitDrop(samplePlacedModule("widget", 0)))),
        ).committedState()
        val pending = WidgetPlacementRecord(
            moduleInstanceId = instanceId("widget"),
            appWidgetId = 73,
            providerPackage = PackageName.parse("org.example.widgets"),
            providerClassName = "org.example.widgets.Clock",
            profile = ProfileSerial.of(0),
            intendedWidthDp = 180,
            intendedHeightDp = 120,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.READY,
        )
        val begun = store.commit(
            LauncherTransaction(withBlock.revision, listOf(LauncherEdit.BeginWidgetBinding(pending))),
        ).committedState()
        val bound = store.commit(
            LauncherTransaction(begun.revision, listOf(LauncherEdit.CompleteWidgetBinding(instanceId("widget")))),
        ).committedState()

        val cleanupPending = store.commit(
            LauncherTransaction(bound.revision, listOf(LauncherEdit.BeginWidgetDeletion(instanceId("widget")))),
        ).committedState()

        assertEquals(73, cleanupPending.widgetPlacements.single().appWidgetId)
        assertEquals(WidgetCleanupState.DELETE_PENDING, cleanupPending.widgetPlacements.single().cleanupState)

        val cleaned = store.commit(
            LauncherTransaction(
                cleanupPending.revision,
                listOf(LauncherEdit.CompleteWidgetDeletion(instanceId("widget"))),
            ),
        ).committedState()
        assertTrue(cleaned.widgetPlacements.isEmpty())
    }

    @Test
    fun `duplicate shortcut targets and explicit folder order survive task edits`() = runTest {
        val store = bootstrappedStore()
        val target = ShortcutTarget(
            ProfileSerial.of(10),
            PackageName.parse("org.example.calendar"),
            ShortcutId.parse("next-event"),
        )
        val first = ContentItemRecord.shortcut(
            ContentItemId.parse("org.quicklauncher.content/shortcut-one"),
            "{}",
            target,
        )
        val second = ContentItemRecord.shortcut(
            ContentItemId.parse("org.quicklauncher.content/shortcut-two"),
            "{}",
            target,
        )
        val folder = ContentItemRecord(
            ContentItemId.parse("org.quicklauncher.content/folder"),
            ContentItemKind.FOLDER,
            "{\"name\":\"Work\"}",
        )

        val committed = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.CreateShortcutPlacement(first),
                    LauncherEdit.CreateShortcutPlacement(second),
                    LauncherEdit.CreateFolder(folder, listOf(first.id, second.id)),
                ),
            ),
        ).committedState()

        assertEquals(2, committed.contentItems.count { it.shortcutTarget == target })
        assertEquals(listOf(first.id, second.id), committed.folderMembers.map { it.memberId })

        val removed = store.commit(
            LauncherTransaction(
                committed.revision,
                listOf(LauncherEdit.RemoveShortcutPlacement(first.id)),
            ),
        ).committedState()
        assertEquals(listOf(FolderMemberRecord(folder.id, second.id, 0)), removed.folderMembers)

        val readded = store.commit(
            LauncherTransaction(
                removed.revision,
                listOf(LauncherEdit.SetFolderMembership(folder.id, first, included = true)),
            ),
        ).committedState()
        assertEquals(listOf(second.id, first.id), readded.folderMembers.map { it.memberId })
        assertEquals(listOf(0, 1), readded.folderMembers.map { it.index })
    }

    private suspend fun bootstrappedStore(
        placementPolicy: PlacementPolicy = PlacementPolicy {
            StoreRejection(
                StoreRejectionCode.PLACEMENT_POLICY_UNAVAILABLE,
                "No test placement policy",
            )
        },
        configurationResolver: ConfigurationResolver = ConfigurationResolver { null },
    ): InMemoryLauncherStore {
        val store = InMemoryLauncherStore(
            placementPolicy = placementPolicy,
            configurationResolver = configurationResolver,
        )
        store.commit(
            LauncherTransaction(
                expectedRevision = StoreRevision.ZERO,
                edits = listOf(LauncherEdit.Bootstrap(sampleInstall("start", 0, 0))),
            ),
        )
        return store
    }

    private fun samplePlacedModule(local: String, index: Int): NewPlacedModule {
        val instanceId = instanceId(local)
        val configurationId = configurationId(local)
        return NewPlacedModule(
            instance = ModuleInstanceRecord(
                id = instanceId,
                contributionId = ContributionId.parse("org.quicklauncher.block/$local"),
                configurationDocumentId = configurationId,
            ),
            configuration = StoredConfigurationDocument(
                id = configurationId,
                document = ConfigurationDocument(
                    configType = ConfigTypeId.parse("org.quicklauncher.config/$local"),
                    schemaVersion = SchemaVersion.of(1),
                    encoded = EncodedConfiguration.of("{ block: '$local' }"),
                ),
            ),
            placement = PlacementRecord(
                id = PlacementId.parse("org.quicklauncher.placement/$local"),
                layoutInstanceId = instanceId("start-layout"),
                parentInstanceId = instanceId("start-layout"),
                parentSlotId = StableKey.parse("main"),
                childInstanceId = instanceId,
                index = index,
            ),
        )
    }

    private fun sampleInstall(
        local: String,
        x: Long,
        y: Long,
        layoutLocal: String = "grid",
    ): DestinationInstall {
        val identitySuffix = if (layoutLocal == "grid") "$local-layout" else "$local-$layoutLocal"
        val configurationId = configurationId(identitySuffix)
        val instanceId = instanceId(identitySuffix)
        val contributionId = contributionId(layoutLocal)
        return DestinationInstall(
            destination = DestinationRecord(
                id = destinationId(local),
                name = local.replaceFirstChar(Char::uppercase),
                coordinate = DestinationCoordinate(x, y),
            ),
            layout = DestinationLayoutRecord(
                destinationId = destinationId(local),
                layoutContributionId = contributionId,
                layoutInstanceId = instanceId,
                selected = true,
            ),
            layoutInstance = ModuleInstanceRecord(
                id = instanceId,
                contributionId = contributionId,
                configurationDocumentId = configurationId,
            ),
            configuration = StoredConfigurationDocument(
                id = configurationId,
                document = ConfigurationDocument(
                    configType = ConfigTypeId.parse("org.quicklauncher.config/$layoutLocal"),
                    schemaVersion = SchemaVersion.of(1),
                    encoded = EncodedConfiguration.of("{ layout: '$local' }"),
                ),
            ),
        )
    }

    private fun destinationId(local: String): DestinationId =
        DestinationId.parse("org.quicklauncher.destination/$local")

    private fun instanceId(local: String): ModuleInstanceId =
        ModuleInstanceId.parse("org.quicklauncher.instance/$local")

    private fun configurationId(local: String): ConfigurationDocumentId =
        ConfigurationDocumentId.parse("org.quicklauncher.configuration/$local")

    private fun contributionId(local: String): ContributionId =
        ContributionId.parse("org.quicklauncher.layout/$local")

    private fun placementId(local: String): PlacementId =
        PlacementId.parse("org.quicklauncher.placement/$local")

    private fun migrationCodec(
        fail: Boolean = false,
        cancel: Boolean = false,
    ): ConfigurationCodec<String> = object : ConfigurationCodec<String> {
        override val configType: ConfigTypeId = ConfigTypeId.parse("org.quicklauncher.config/grid")
        override val currentSchemaVersion: SchemaVersion = SchemaVersion.of(2)
        override val default: String = "default"
        override val migrations: List<ConfigurationMigration> = listOf(
            object : ConfigurationMigration {
                override val configType: ConfigTypeId =
                    ConfigTypeId.parse("org.quicklauncher.config/grid")
                override val fromVersion: SchemaVersion = SchemaVersion.of(1)
                override val toVersion: SchemaVersion = SchemaVersion.of(2)

                override fun migrate(encoded: EncodedConfiguration): MigrationResult {
                    if (cancel) throw CancellationException("cancel migration")
                    if (fail) return MigrationResult.Failed("fixture rejected the document")
                    return MigrationResult.Migrated(
                        EncodedConfiguration.of("migrated:${encoded.value}"),
                    )
                }
            },
        )

        override fun encode(value: String): EncodedConfiguration = EncodedConfiguration.of(value)

        override fun decode(encoded: EncodedConfiguration): CodecResult<String> =
            if (encoded.value.startsWith("migrated:")) {
                CodecResult.Decoded(encoded.value)
            } else {
                CodecResult.Failed("missing migration marker")
            }
    }

    private fun simpleCodec(local: String): ConfigurationCodec<String> =
        object : ConfigurationCodec<String> {
            override val configType: ConfigTypeId = ConfigTypeId.parse("org.quicklauncher.config/$local")
            override val currentSchemaVersion: SchemaVersion = SchemaVersion.of(1)
            override val default: String = "default"

            override fun encode(value: String): EncodedConfiguration = EncodedConfiguration.of(value)

            override fun decode(encoded: EncodedConfiguration): CodecResult<String> =
                CodecResult.Decoded(encoded.value)
        }

    private fun failingDecodeCodec(): ConfigurationCodec<String> =
        object : ConfigurationCodec<String> {
            override val configType: ConfigTypeId = ConfigTypeId.parse("org.quicklauncher.config/grid")
            override val currentSchemaVersion: SchemaVersion = SchemaVersion.of(1)
            override val default: String = "default"

            override fun encode(value: String): EncodedConfiguration = EncodedConfiguration.of(value)

            override fun decode(encoded: EncodedConfiguration): CodecResult<String> =
                CodecResult.Failed("malformed fixture")
        }

    private suspend fun configurationQuarantinedSnapshot(): LauncherSnapshot {
        val base = bootstrappedStore().read()
        val quarantine = ModuleInstanceStatus.Quarantined(
            code = "configuration.previous-failure",
            message = "Previous configuration failed",
            origin = ModuleQuarantineOrigin.CONFIGURATION,
        )
        return copySnapshot(
            base,
            moduleInstances = listOf(base.moduleInstances.single().copy(status = quarantine)),
            configurationDocuments = listOf(
                base.configurationDocuments.single().copy(
                    lastMigrationErrorCode = "configuration.previous-failure",
                ),
            ),
        )
    }

    private fun copySnapshot(
        source: LauncherSnapshot,
        moduleInstances: Collection<ModuleInstanceRecord> = source.moduleInstances,
        configurationDocuments: Collection<StoredConfigurationDocument> = source.configurationDocuments,
        contentItems: Collection<ContentItemRecord> = source.contentItems,
        folderMembers: Collection<FolderMemberRecord> = source.folderMembers,
        widgetPlacements: Collection<WidgetPlacementRecord> = source.widgetPlacements,
    ): LauncherSnapshot = LauncherSnapshot(
        revision = source.revision,
        startDestinationId = source.startDestinationId,
        destinations = source.destinations,
        destinationLayouts = source.destinationLayouts,
        moduleInstances = moduleInstances,
        configurationDocuments = configurationDocuments,
        placements = source.placements,
        contentItems = contentItems,
        folderMembers = folderMembers,
        appOverrides = source.appOverrides,
        widgetPlacements = widgetPlacements,
        themeProfiles = source.themeProfiles,
        destinationBackgrounds = source.destinationBackgrounds,
        crashMarkers = source.crashMarkers,
    )

    private fun CommitResult.committedState(): LauncherSnapshot {
        assertTrue(this is CommitResult.Committed)
        return (this as CommitResult.Committed).state
    }
}
