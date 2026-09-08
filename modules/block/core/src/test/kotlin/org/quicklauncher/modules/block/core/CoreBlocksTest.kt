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
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.modules.block.core.generated.CoreBlockRegistry

class CoreBlocksTest {
    @Test
    fun `every core block owns a distinct schema and canonical default document`() {
        val codecs = listOf(
            AlphabeticalAppsCodec,
            AppGridCodec,
            FavoritesCodec,
            FolderCodec,
            ClockDateCodec,
        )

        assertEquals(codecs.size, codecs.map { it.configType }.toSet().size)
        assertEquals("{\"showSectionHeaders\":true}", AlphabeticalAppsCodec.encode(AlphabeticalAppsCodec.default).value)
        assertEquals("{\"columns\":4}", AppGridCodec.encode(AppGridCodec.default).value)
        assertEquals("{\"maximumItems\":5}", FavoritesCodec.encode(FavoritesCodec.default).value)
        assertEquals("{\"title\":\"Folder\",\"columns\":3}", FolderCodec.encode(FolderCodec.default).value)
        assertEquals("{\"showDate\":true}", ClockDateCodec.encode(ClockDateCodec.default).value)
    }

    @Test
    fun `configuration codecs round trip and reject malformed or out of range documents`() {
        assertEquals(
            CodecResult.Decoded(AppGridConfiguration(6)),
            AppGridCodec.decode(AppGridCodec.encode(AppGridConfiguration(6))),
        )
        assertEquals(
            CodecResult.Decoded(FolderConfiguration("Games", 5)),
            FolderCodec.decode(FolderCodec.encode(FolderConfiguration("Games", 5))),
        )
        listOf(
            AppGridCodec.decode(EncodedConfiguration.of("{\"columns\":0}")),
            FavoritesCodec.decode(EncodedConfiguration.of("{\"maximumItems\":101}")),
            FolderCodec.decode(EncodedConfiguration.of("{\"title\":\"\",\"columns\":3}")),
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
    }
}

private data class TestCancellationSignal(
    override val isCancelled: Boolean,
) : org.quicklauncher.contracts.contribution.CancellationSignal
