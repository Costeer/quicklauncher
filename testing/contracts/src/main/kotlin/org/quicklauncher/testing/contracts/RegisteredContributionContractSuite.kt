package org.quicklauncher.testing.contracts

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
import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.DescriptorValidator
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.RegisteredLayout
import org.quicklauncher.contracts.contribution.ValidationResult
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario

/** Reusable black-box checks that every production registry fragment must run. */
object RegisteredContributionContractSuite {
    fun verify(entries: Collection<RegisteredContribution<*>>) {
        val values = entries.toList()
        assertEquals(values.size, values.map { it.descriptor.metadata.id }.toSet().size)
        assertEquals(values.size, values.map { it.codec.configType }.toSet().size)
        values.forEach(::verifyCommonContract)
        values.filterIsInstance<RegisteredLayout<*>>().forEach(::verifyLayoutLifecycle)
        values.filterIsInstance<RegisteredBlock<*>>().forEach(::verifyBlockLifecycle)
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyCommonContract(entry: RegisteredContribution<*>) {
        assertEquals(entry.descriptor.metadata.id, entry.contractTests.contributionId)
        assertEquals(entry.descriptor.metadata.configType, entry.codec.configType)
        assertEquals(ValidationResult.Valid, DescriptorValidator.validate(entry.descriptor))
        assertEquals(PreviewScenario.entries.toSet(), entry.contractTests.scenarios)
        val codec = entry.codec as ConfigurationCodec<Any>
        val firstEncoding = codec.encode(codec.default)
        assertEquals(firstEncoding, codec.encode(codec.default))
        assertEquals(CodecResult.Decoded(codec.default), codec.decode(firstEncoding))
        assertEquals(codec.decode(firstEncoding), codec.decode(firstEncoding))
        assertTrue(codec.currentSchemaVersion.value > 0)
        codec.migrations.forEach { migration ->
            assertEquals(codec.configType, migration.configType)
            assertTrue(migration.toVersion.value > migration.fromVersion.value)
        }
        assertEquals(codec.migrations.size, codec.migrations.map { it.fromVersion }.toSet().size)
        val required = when (entry) {
            is RegisteredLayout<*>, is RegisteredBlock<*> -> setOf(
                PerformanceMetric.FIRST_RENDER,
                PerformanceMetric.ACTION_DISPATCH,
                PerformanceMetric.DISPOSAL,
            )
            else -> setOf(PerformanceMetric.DRAFT_CREATION)
        }
        assertTrue(entry.contractTests.performanceHooks.map { it.metric }.containsAll(required))
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyLayoutLifecycle(entry: RegisteredLayout<*>) {
        verifyCancellation(entry.codec.default) { context ->
            (entry.target as LayoutContribution<Any>).open(context)
        }
        verifySession(entry.descriptor.metadata.id.value.substringAfterLast('/'), entry.codec.default) { context ->
            (entry.target as LayoutContribution<Any>).open(context)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyBlockLifecycle(entry: RegisteredBlock<*>) {
        verifyCancellation(entry.codec.default) { context ->
            (entry.target as BlockContribution<Any>).open(context)
        }
        verifySession(entry.descriptor.metadata.id.value.substringAfterLast('/'), entry.codec.default) { context ->
            (entry.target as BlockContribution<Any>).open(context)
        }
    }

    private fun verifySession(
        instanceKey: String,
        configuration: Any,
        open: (ContributionContext<Any>) -> AutoCloseable,
    ) = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        val session = open(
            ContributionContext(
                ModuleInstanceId.parse("org.quicklauncher.contract/$instanceKey"),
                configuration,
                ActiveSignal,
                scope,
            ),
        )
        assertTrue(parent.children.any())
        session.close()
        session.close()
        withTimeout(1_000) {
            while (parent.children.any()) yield()
        }
        assertFalse(parent.children.any())
        scope.cancel()
    }

    private fun verifyCancellation(
        configuration: Any,
        open: (ContributionContext<Any>) -> AutoCloseable,
    ) = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        assertThrows(CancellationException::class.java) {
            open(
                ContributionContext(
                    ModuleInstanceId.parse("org.quicklauncher.contract/cancelled"),
                    configuration,
                    CancelledSignal,
                    scope,
                ),
            )
        }
        assertFalse(parent.children.any())
        scope.cancel()
    }

    private object ActiveSignal : org.quicklauncher.contracts.contribution.CancellationSignal {
        override val isCancelled = false
    }

    private object CancelledSignal : org.quicklauncher.contracts.contribution.CancellationSignal {
        override val isCancelled = true
    }
}
