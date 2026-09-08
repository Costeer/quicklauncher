package org.quicklauncher.host.editor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.ConfigurationResolver
import org.quicklauncher.host.data.store.configurationLoader

class LauncherEditorTest {
    @Test
    fun `accessible map commands rename move and select start through one editor seam`() = runTest {
        val store = seededStore()
        val editor = DefaultLauncherEditor(store, backgroundScope)
        editor.start()

        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.OpenMap))
        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.Rename(RIGHT, "Work")))
        assertEquals(
            EditorResult.Applied,
            editor.dispatch(EditorAction.Move(setOf(CENTER, RIGHT), EditorDirection.DOWN)),
        )
        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.SetStart(RIGHT)))

        val state = editor.state.value
        assertEquals(EditorScreen.MAP, state.screen)
        assertEquals("Work", state.destinations.single { it.id == RIGHT }.name)
        assertEquals(1L, state.destinations.single { it.id == RIGHT }.x)
        assertEquals(1L, state.destinations.single { it.id == RIGHT }.y)
        assertTrue(state.destinations.single { it.id == RIGHT }.start)
        assertNull(state.rejection)
        editor.close()
    }

    @Test
    fun `rejected accessible move keeps the exact prior editor snapshot`() = runTest {
        val store = seededStore()
        val editor = DefaultLauncherEditor(store, backgroundScope)
        editor.start()
        editor.dispatch(EditorAction.OpenMap)
        val before = editor.state.value

        val result = editor.dispatch(EditorAction.Move(setOf(RIGHT), EditorDirection.RIGHT))

        assertTrue(result is EditorResult.Rejected)
        assertEquals(before.revision, editor.state.value.revision)
        assertEquals(before.destinations, editor.state.value.destinations)
        assertEquals(EditorScreen.MAP, editor.state.value.screen)
        assertEquals((result as EditorResult.Rejected).reason, editor.state.value.rejection)
        editor.close()
    }

    @Test
    fun `deleting a destination requires explicit confirmation`() = runTest {
        val store = seededStore()
        val editor = DefaultLauncherEditor(store, backgroundScope)
        editor.start()

        val rejected = editor.dispatch(EditorAction.Delete(RIGHT, confirmed = false))
        assertTrue(rejected is EditorResult.Rejected)
        assertEquals(2, editor.state.value.destinations.size)

        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.Delete(RIGHT, confirmed = true)))
        assertEquals(listOf(CENTER), editor.state.value.destinations.map { it.id })
        editor.close()
    }

    @Test
    fun `configuration reset uses the registered default through the validated store path`() = runTest {
        val codec = EditorTestCodec()
        val store = seededStore(ConfigurationResolver { configurationLoader(codec) })
        val editor = DefaultLauncherEditor(
            store,
            backgroundScope,
            EditorConfigurationDefaults {
                ConfigurationDocument(codec.configType, codec.currentSchemaVersion, codec.encode(codec.default))
            },
        )
        editor.start()

        assertEquals(EditorResult.Applied, editor.dispatch(EditorAction.ResetConfiguration(RIGHT_LAYOUT)))

        val stored = store.read().configurationDocuments.single {
            it.id == ConfigurationDocumentId.parse("org.quicklauncher.configuration/right-layout")
        }
        assertEquals(EncodedConfiguration.of("default"), stored.document.encoded)
        editor.close()
    }

    @Test
    fun `invalid configuration replacement preserves the original document exactly`() = runTest {
        val codec = EditorTestCodec()
        val store = seededStore(ConfigurationResolver { configurationLoader(codec) })
        val editor = DefaultLauncherEditor(store, backgroundScope)
        editor.start()
        val before = store.read().configurationDocuments.single {
            it.id == ConfigurationDocumentId.parse("org.quicklauncher.configuration/right-layout")
        }

        val result = editor.dispatch(
            EditorAction.ReplaceConfiguration(
                RIGHT_LAYOUT,
                ConfigurationDocument(codec.configType, SchemaVersion.of(2), EncodedConfiguration.of("future")),
            ),
        )

        assertTrue(result is EditorResult.Rejected)
        assertEquals(before, store.read().configurationDocuments.single { it.id == before.id })
        editor.close()
    }

    private suspend fun seededStore(
        configurationResolver: ConfigurationResolver = ConfigurationResolver { null },
    ): InMemoryLauncherStore = InMemoryLauncherStore(configurationResolver = configurationResolver).also { store ->
        val first = install(CENTER, 0, 0)
        check(store.commit(LauncherTransaction(store.read().revision, listOf(LauncherEdit.Bootstrap(first)))) is CommitResult.Committed)
        val second = install(RIGHT, 1, 0)
        check(
            store.commit(
                LauncherTransaction(store.read().revision, listOf(LauncherEdit.InstallDestination(second))),
            ) is CommitResult.Committed,
        )
    }

    private fun install(id: DestinationId, x: Long, y: Long): DestinationInstall {
        val suffix = id.value.substringAfterLast('/')
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/$suffix-layout")
        val documentId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/$suffix-layout")
        val contributionId = ContributionId.parse("org.quicklauncher.test/layout")
        return DestinationInstall(
            DestinationRecord(id, suffix, DestinationCoordinate(x, y)),
            DestinationLayoutRecord(id, contributionId, instanceId, selected = true),
            ModuleInstanceRecord(instanceId, contributionId, documentId),
            StoredConfigurationDocument(
                documentId,
                ConfigurationDocument(
                    ConfigTypeId.parse("org.quicklauncher.test/layout-config"),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("{}"),
                ),
            ),
        )
    }

    private companion object {
        val CENTER = DestinationId.parse("org.quicklauncher.destination/center")
        val RIGHT = DestinationId.parse("org.quicklauncher.destination/right")
        val RIGHT_LAYOUT = ModuleInstanceId.parse("org.quicklauncher.instance/right-layout")
    }
}

private class EditorTestCodec : ConfigurationCodec<String> {
    override val configType = ConfigTypeId.parse("org.quicklauncher.test/layout-config")
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = "default"
    override fun encode(value: String) = EncodedConfiguration.of(value)
    override fun decode(encoded: EncodedConfiguration) = CodecResult.Decoded(encoded.value)
}
