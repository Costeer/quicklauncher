package org.quicklauncher.modules.block.core

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ContentSelectorSetting
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.modules.block.core.generated.CoreBlockRegistry
import org.quicklauncher.testing.contracts.RegisteredContributionContractSuite

class CoreBlocksTest {
    @Test
    fun `every block satisfies the reusable public contribution contract suite`() {
        RegisteredContributionContractSuite.verify(CoreBlockRegistry.entries)
    }

    @Test
    fun `every core block owns a distinct schema and canonical default document`() {
        val codecs = listOf(
            AlphabeticalAppsCodec,
            AppGridCodec,
            FavoritesCodec,
            FolderCodec,
            WidgetCodec,
            ClockDateCodec,
            SearchCodec,
        )

        assertEquals(codecs.size, codecs.map { it.configType }.toSet().size)
        assertEquals("{\"showSectionHeaders\":true}", AlphabeticalAppsCodec.encode(AlphabeticalAppsCodec.default).value)
        assertEquals("{\"columns\":4}", AppGridCodec.encode(AppGridCodec.default).value)
        assertEquals("{\"maximumItems\":5}", FavoritesCodec.encode(FavoritesCodec.default).value)
        assertEquals("{\"title\":\"Folder\",\"columns\":3}", FolderCodec.encode(FolderCodec.default).value)
        assertEquals(
            "{\"showUnavailableExplanation\":true}",
            WidgetCodec.encode(WidgetCodec.default).value,
        )
        assertEquals("{\"showDate\":true}", ClockDateCodec.encode(ClockDateCodec.default).value)
        assertEquals("{\"minimumCharacters\":0}", SearchCodec.encode(SearchCodec.default).value)
    }

    @Test
    fun `configuration codecs round trip and reject malformed or out of range documents`() {
        assertEquals(
            CodecResult.Decoded(AppGridConfiguration(6)),
            AppGridCodec.decode(AppGridCodec.encode(AppGridConfiguration(6))),
        )
        assertEquals(
            CodecResult.Decoded(
                FolderConfiguration(
                    "Games",
                    5,
                ),
            ),
            FolderCodec.decode(
                FolderCodec.encode(
                    FolderConfiguration(
                        "Games",
                        5,
                    ),
                ),
            ),
        )
        assertEquals(
            CodecResult.Decoded(FolderConfiguration("Legacy", 4)),
            FolderCodec.decode(EncodedConfiguration.of("{\"title\":\"Legacy\",\"columns\":4}")),
        )
        listOf(
            AppGridCodec.decode(EncodedConfiguration.of("{\"columns\":0}")),
            FavoritesCodec.decode(EncodedConfiguration.of("{\"maximumItems\":101}")),
            FolderCodec.decode(EncodedConfiguration.of("{\"title\":\"\",\"columns\":3}")),
            FolderCodec.decode(
                EncodedConfiguration.of(
                    "{\"title\":\"Folder\",\"columns\":3,\"folderId\":\"org.quicklauncher.content/legacy\"}",
                ),
            ),
            ClockDateCodec.decode(EncodedConfiguration.of("not-json")),
        ).forEach { assertTrue(it is CodecResult.Failed) }
    }

    @Test
    fun `generated registry exposes stable leaf descriptors axes and complete contracts`() {
        val entries = CoreBlockRegistry.entries.map { it as RegisteredBlock<*> }
        assertEquals(
            listOf(
                CoreBlockIds.ALPHABETICAL,
                CoreBlockIds.APP_GRID,
                CoreBlockIds.CLOCK,
                CoreBlockIds.FAVORITES,
                CoreBlockIds.FOLDER,
                CoreBlockIds.SEARCH,
                CoreBlockIds.WIDGET,
            ),
            entries.map { it.descriptor.metadata.id.value },
        )
        assertEquals(
            listOf(
                setOf(ScrollAxis.VERTICAL),
                setOf(ScrollAxis.VERTICAL),
                emptySet(),
                setOf(ScrollAxis.HORIZONTAL),
                setOf(ScrollAxis.VERTICAL),
                setOf(ScrollAxis.VERTICAL),
                emptySet(),
            ),
            entries.map { it.descriptor.occupiedScrollAxes },
        )
        entries.forEach { entry ->
            assertTrue(entry.descriptor.childSlots.isEmpty())
            assertEquals(2, entry.descriptor.compatibleSlotTypes.size)
            assertEquals(PreviewScenario.entries.toSet(), entry.contractTests.scenarios)
            assertEquals(
                setOf(PerformanceMetric.FIRST_RENDER, PerformanceMetric.ACTION_DISPATCH, PerformanceMetric.DISPOSAL),
                entry.contractTests.performanceHooks.map { it.metric }.toSet(),
            )
        }
        val folder = entries.single { it.descriptor.metadata.id.value == CoreBlockIds.FOLDER }
        assertTrue(folder.descriptor.metadata.settings?.fields?.none { it is ContentSelectorSetting } == true)
    }

    @Test
    fun `open honors cancellation and close terminates session owned work`() = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        val session = AppGridBlock.open(
            ContributionContext(
                ModuleInstanceId.parse("org.quicklauncher.instance/app-grid-test"),
                AppGridCodec.default,
                TestCancellationSignal(false),
                scope,
            ),
        )
        assertTrue(parent.children.any())

        session.close()
        session.close()
        withTimeout(1_000) {
            while (parent.children.any()) yield()
        }
        assertTrue(session.isClosed)
        assertFalse(parent.children.any())
        scope.cancel()

        assertThrows(CancellationException::class.java) {
            AppGridBlock.open(
                ContributionContext(
                    ModuleInstanceId.parse("org.quicklauncher.instance/cancelled-grid-test"),
                    AppGridCodec.default,
                    TestCancellationSignal(true),
                    CoroutineScope(Job()),
                ),
            )
        }
        Unit
    }
}

private data class TestCancellationSignal(
    override val isCancelled: Boolean,
) : org.quicklauncher.contracts.contribution.CancellationSignal
