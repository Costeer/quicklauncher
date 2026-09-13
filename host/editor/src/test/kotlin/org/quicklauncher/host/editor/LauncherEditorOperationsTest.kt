package org.quicklauncher.host.editor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ConfigurationResolver
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.PlacementPolicy
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.configurationLoader

class LauncherEditorOperationsTest {
    @Test
    fun `destination editor retains and selects layouts then adds moves copies and removes blocks`() = runTest {
        val fixture = fixture()
        val editor = fixture.editor(backgroundScope)
        editor.start()
        editor.dispatch(EditorAction.OpenDestination(HOME))

        assertEquals(
            EditorResult.Applied,
            editor.dispatch(EditorAction.RetainLayout(HOME, SECOND_LAYOUT, document("second"))),
        )
        val dormant = editor.state.value.modules.single { it.contributionId == SECOND_LAYOUT }
        assertFalse(dormant.selectedLayout)
        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.SelectLayout(HOME, dormant.id)))
        assertTrue(editor.state.value.modules.single { it.id == dormant.id }.selectedLayout)

        assertEquals(
            EditorResult.Applied,
            editor.dispatch(
                EditorAction.AddBlock(
                    dormant.id,
                    dormant.id,
                    CONTENT_SLOT,
                    BLOCK,
                    document("block"),
                    index = 0,
                    placement = EditorEncodedPlacement.of("{\"column\":2}"),
                ),
            ),
        )
        val original = editor.state.value.modules.single { it.placedBlock }
        assertEquals("{\"column\":2}", original.placement?.encoded)

        assertEquals(
            EditorResult.Applied,
            editor.dispatch(
                EditorAction.MoveBlock(
                    original.id,
                    dormant.id,
                    CONTENT_SLOT,
                    0,
                    EditorEncodedPlacement.of("{\"column\":4}"),
                ),
            ),
        )
        assertEquals("{\"column\":4}", editor.state.value.modules.single { it.id == original.id }.placement?.encoded)

        assertEquals(
            EditorResult.Applied,
            editor.dispatch(
                EditorAction.CopyBlock(
                    original.id,
                    dormant.id,
                    CONTENT_SLOT,
                    1,
                    EditorEncodedPlacement.of("{\"column\":6}"),
                ),
            ),
        )
        val copies = editor.state.value.modules.filter { it.placedBlock }
        assertEquals(2, copies.size)
        assertNotEquals(copies[0].id, copies[1].id)
        assertNotEquals(
            fixture.store.read().moduleInstances.single { it.id == copies[0].id }.configurationDocumentId,
            fixture.store.read().moduleInstances.single { it.id == copies[1].id }.configurationDocumentId,
        )

        val removal = editor.dispatch(EditorAction.RemovePlacedModule(original.id, confirmed = false))
        assertTrue(removal is EditorResult.Rejected)
        assertEquals(2, editor.state.value.modules.count { it.placedBlock })
        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.RemovePlacedModule(original.id, confirmed = true)))
        assertEquals(1, editor.state.value.modules.count { it.placedBlock })
        editor.close()
    }

    @Test
    fun `create destination uses one host transaction and publishes the installed safe layout`() = runTest {
        val fixture = fixture()
        val editor = fixture.editor(backgroundScope)
        editor.start()
        editor.dispatch(EditorAction.OpenMap)

        assertEquals(
            EditorResult.Applied,
            editor.dispatch(EditorAction.CreateDestination("Study", 0, 1)),
        )

        val created = editor.state.value.destinations.single { it.name == "Study" }
        assertEquals(DestinationCoordinate(0, 1), fixture.store.read().destinations.single { it.id == created.id }.coordinate)
        assertTrue(fixture.store.read().destinationLayouts.single { it.destinationId == created.id }.selected)
        editor.close()
    }

    @Test
    fun `settings rejection preserves editor state revision and the original encoded bytes`() = runTest {
        val fixture = fixture()
        val editor = fixture.editor(backgroundScope)
        editor.start()
        editor.dispatch(EditorAction.OpenDestination(HOME))
        val beforeState = editor.state.value
        val beforeStore = fixture.store.read()
        val original = beforeStore.configurationDocuments.single { it.id == ROOT_DOCUMENT }

        val result = editor.dispatch(
            EditorAction.ReplaceConfiguration(
                ROOT,
                ConfigurationDocument(CONFIG, SchemaVersion.of(1), EncodedConfiguration.of("rejected")),
            ),
        )

        assertTrue(result is EditorResult.Rejected)
        assertEquals(beforeState.revision, editor.state.value.revision)
        assertEquals(beforeState.destinations, editor.state.value.destinations)
        assertEquals(beforeState.modules, editor.state.value.modules)
        assertEquals(beforeStore, fixture.store.read())
        assertEquals(EncodedConfiguration.of("root-original-bytes"), original.document.encoded)
        assertEquals(original, fixture.store.read().configurationDocuments.single { it.id == ROOT_DOCUMENT })
        editor.close()
    }

    @Test
    fun `quarantined configuration resets to validated default and retained quarantine deletion needs confirmation`() = runTest {
        val fixture = fixture(quarantinedRoot = true)
        val editor = fixture.editor(backgroundScope)
        editor.start()
        editor.dispatch(EditorAction.OpenDestination(HOME))

        assertTrue(editor.state.value.modules.single { it.id == ROOT }.quarantined)
        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.ResetConfiguration(ROOT)))
        assertEquals(EncodedConfiguration.of("default"), fixture.store.read().configurationDocuments.single().document.encoded)
        assertFalse(fixture.store.read().moduleInstances.single().status is ModuleInstanceStatus.Quarantined)

        val retained = retainQuarantinedLayout(fixture.store)
        editor.dispatch(EditorAction.OpenDestination(HOME))
        val rejected = editor.dispatch(EditorAction.RemoveQuarantinedModule(retained, confirmed = false))
        assertTrue(rejected is EditorResult.Rejected)
        assertTrue(fixture.store.read().moduleInstances.any { it.id == retained })
        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.RemoveQuarantinedModule(retained, confirmed = true)))
        assertTrue(fixture.store.read().moduleInstances.none { it.id == retained })
        editor.close()
    }

    private suspend fun retainQuarantinedLayout(store: InMemoryLauncherStore): ModuleInstanceId {
        val id = ModuleInstanceId.parse("org.quicklauncher.instance/quarantined-retained")
        val documentId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/quarantined-retained")
        val result = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        DestinationLayoutRecord(HOME, SECOND_LAYOUT, id, selected = false),
                        ModuleInstanceRecord(
                            id,
                            SECOND_LAYOUT,
                            documentId,
                            ModuleInstanceStatus.Quarantined(
                                "configuration.invalid",
                                "Cannot decode",
                                ModuleQuarantineOrigin.CONFIGURATION,
                            ),
                        ),
                        StoredConfigurationDocument(documentId, document("unreadable")),
                    ),
                ),
            ),
        )
        check(result is CommitResult.Committed)
        return id
    }

    private suspend fun fixture(quarantinedRoot: Boolean = false): Fixture {
        val status = if (quarantinedRoot) {
            ModuleInstanceStatus.Quarantined(
                "configuration.invalid",
                "Cannot decode",
                ModuleQuarantineOrigin.CONFIGURATION,
            )
        } else {
            ModuleInstanceStatus.Active
        }
        val store = InMemoryLauncherStore(
            configurationResolver = ConfigurationResolver { contributionId ->
                configurationLoader(
                    EditorOperationsCodec,
                    if (contributionId == BLOCK) {
                        org.quicklauncher.contracts.contribution.ContributionTypes.BLOCK
                    } else {
                        org.quicklauncher.contracts.contribution.ContributionTypes.LAYOUT
                    },
                )
            },
            placementPolicy = PlacementPolicy { null },
        )
        val result = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.Bootstrap(
                        DestinationInstall(
                            DestinationRecord(HOME, "Home", DestinationCoordinate(0, 0)),
                            DestinationLayoutRecord(HOME, FIRST_LAYOUT, ROOT, selected = true),
                            ModuleInstanceRecord(ROOT, FIRST_LAYOUT, ROOT_DOCUMENT, status),
                            StoredConfigurationDocument(ROOT_DOCUMENT, document("root-original-bytes")),
                        ),
                    ),
                ),
            ),
        )
        check(result is CommitResult.Committed)
        return Fixture(store)
    }

    private data class Fixture(val store: InMemoryLauncherStore) {
        fun editor(scope: kotlinx.coroutines.CoroutineScope): DefaultLauncherEditor {
            val ids = ArrayDeque(
                listOf(
                    "created-destination",
                    "created-layout",
                    "created-layout-config",
                    "retained-layout",
                    "retained-config",
                    "block",
                    "block-config",
                    "block-placement",
                    "copy-block",
                    "copy-config",
                    "copy-placement",
                ),
            )
            return DefaultLauncherEditor(
                store,
                scope,
                EditorConfigurationDefaults { document("default") },
                EditorDestinationFactory { destinationId, name, x, y ->
                    val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/${ids.removeFirst()}")
                    val documentId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/${ids.removeFirst()}")
                    DestinationInstall(
                        DestinationRecord(destinationId, name, DestinationCoordinate(x, y)),
                        DestinationLayoutRecord(destinationId, FIRST_LAYOUT, instanceId, selected = true),
                        ModuleInstanceRecord(instanceId, FIRST_LAYOUT, documentId),
                        StoredConfigurationDocument(documentId, document("default")),
                    )
                },
                EditorIdentitySource { ids.removeFirst() },
            )
        }
    }

    private companion object {
        val HOME = DestinationId.parse("org.quicklauncher.destination/home")
        val ROOT = ModuleInstanceId.parse("org.quicklauncher.instance/root")
        val ROOT_DOCUMENT = ConfigurationDocumentId.parse("org.quicklauncher.configuration/root")
        val FIRST_LAYOUT = ContributionId.parse("org.quicklauncher.test/layout-first")
        val SECOND_LAYOUT = ContributionId.parse("org.quicklauncher.test/layout-second")
        val BLOCK = ContributionId.parse("org.quicklauncher.test/block")
        val CONTENT_SLOT = StableKey.parse("content")
        val CONFIG = ConfigTypeId.parse("org.quicklauncher.test/editor-config")

        fun document(value: String) = ConfigurationDocument(
            CONFIG,
            SchemaVersion.of(1),
            EncodedConfiguration.of(value),
        )
    }
}

private object EditorOperationsCodec : ConfigurationCodec<String> {
    override val configType = ConfigTypeId.parse("org.quicklauncher.test/editor-config")
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = "default"
    override fun encode(value: String) = EncodedConfiguration.of(value)
    override fun decode(encoded: EncodedConfiguration): CodecResult<String> = when (encoded.value) {
        "rejected", "unreadable" -> CodecResult.Failed("fixture rejection")
        else -> CodecResult.Decoded(encoded.value)
    }
}
