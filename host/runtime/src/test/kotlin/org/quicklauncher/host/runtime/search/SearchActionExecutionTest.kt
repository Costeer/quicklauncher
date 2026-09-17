package org.quicklauncher.host.runtime.search

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.CommandContext
import org.quicklauncher.contracts.contribution.CommandInput
import org.quicklauncher.contracts.contribution.CommandResult
import org.quicklauncher.contracts.contribution.CommandResultKind
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionMetadata
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.LauncherCommandAction
import org.quicklauncher.contracts.contribution.LauncherCommandContractTestDeclaration
import org.quicklauncher.contracts.contribution.LauncherCommandContribution
import org.quicklauncher.contracts.contribution.LauncherCommandDescriptor
import org.quicklauncher.contracts.contribution.RegisteredLauncherCommand
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PreviewScenario

@OptIn(ExperimentalCoroutinesApi::class)
class SearchActionExecutionTest {
    @Test
    fun `typed target is revalidated immediately before platform execution`() = runTest {
        val actionId = SearchActionId.parse("org.quicklauncher.search.action/current")
        var current: SearchExecutionTarget? = null
        val executed = mutableListOf<SearchExecutionTarget>()
        val commands = RegisteredCommandExecutor(emptyList(), CommandCapabilityChecker { false }) {
            SearchExecutionResult.Rejected
        }
        val executor = DefaultSearchActionExecutor(
            targets = object : EmptyExecutableCatalog() {
                override fun executionTarget(id: SearchActionId): SearchExecutionTarget? =
                    current.takeIf { id == actionId }
            },
            platform = SearchTargetPlatform { target ->
                executed += target
                SearchExecutionResult.Succeeded
            },
            commands = commands,
        )

        assertEquals(
            SearchExecutionResult.Rejected,
            executor.execute(SearchResultAction.Execute(actionId)),
        )
        assertTrue(executed.isEmpty())

        current = SearchExecutionTarget.Information(SearchRecoveryAction.OPEN_LAUNCHER_SETTINGS)
        assertEquals(
            SearchExecutionResult.Succeeded,
            executor.execute(SearchResultAction.Execute(actionId)),
        )
        assertEquals(listOf(current), executed)
    }

    @Test
    fun `registered command validates identity context capability and result kind before host action`() = runTest {
        val capability = CapabilityId.parse("org.quicklauncher.capability/search-test")
        var allowed = false
        var commandCalls = 0
        val hostActions = mutableListOf<LauncherCommandAction>()
        val valid = command(
            local = "valid",
            contexts = setOf(CommandContext.SEARCH),
            resultKinds = setOf(CommandResultKind.HOST_ACTION),
            capabilities = setOf(capability),
        ) {
            commandCalls += 1
            CommandResult.Succeeded(LauncherCommandAction.OpenLauncherSettings)
        }
        val malformed = command(
            local = "malformed",
            contexts = setOf(CommandContext.SEARCH),
            resultKinds = setOf(CommandResultKind.INFORMATION),
        ) { CommandResult.Succeeded(LauncherCommandAction.OpenSearch) }
        val executor = RegisteredCommandExecutor(
            listOf(valid, malformed),
            CommandCapabilityChecker { allowed },
            HostCommandActionExecutor { action ->
                hostActions += action
                SearchExecutionResult.Succeeded
            },
        )

        assertEquals(
            SearchExecutionResult.Rejected,
            executor.execute(ContributionId.parse("org.quicklauncher.command/missing"), CommandContext.SEARCH),
        )
        assertEquals(SearchExecutionResult.MissingAccess, executor.execute(valid.id(), CommandContext.SEARCH))
        assertEquals(0, commandCalls)

        allowed = true
        assertEquals(SearchExecutionResult.Succeeded, executor.execute(valid.id(), CommandContext.SEARCH))
        assertEquals(1, commandCalls)
        assertEquals(listOf(LauncherCommandAction.OpenLauncherSettings), hostActions)

        assertEquals(SearchExecutionResult.Rejected, executor.execute(valid.id(), CommandContext.GESTURE))
        assertEquals(SearchExecutionResult.RecoverableFailure, executor.execute(malformed.id(), CommandContext.SEARCH))
        assertEquals(1, hostActions.size)
    }

    @Test
    fun `command rejects an unrelated missing access capability as malformed output`() = runTest {
        val declared = CapabilityId.parse("org.quicklauncher.capability/declared")
        val unrelated = CapabilityId.parse("org.quicklauncher.capability/unrelated")
        val entry = command(
            local = "unrelated-access",
            contexts = setOf(CommandContext.SEARCH),
            resultKinds = setOf(CommandResultKind.INFORMATION),
            capabilities = setOf(declared),
        ) { CommandResult.MissingAccess(unrelated) }
        val executor = RegisteredCommandExecutor(
            listOf(entry),
            CommandCapabilityChecker { true },
            HostCommandActionExecutor { SearchExecutionResult.Succeeded },
        )

        assertEquals(
            SearchExecutionResult.RecoverableFailure,
            executor.execute(entry.id(), CommandContext.SEARCH),
        )
    }

    @Test
    fun `command coroutine cancellation reaches the contribution and performs no host action`() = runTest {
        var contributionCancelled = false
        val entry = command(
            local = "cancel",
            contexts = setOf(CommandContext.SEARCH),
            resultKinds = setOf(CommandResultKind.HOST_ACTION),
        ) {
            try {
                awaitCancellation()
            } finally {
                contributionCancelled = true
            }
        }
        var hostCalls = 0
        val executor = RegisteredCommandExecutor(
            listOf(entry),
            CommandCapabilityChecker { true },
            HostCommandActionExecutor {
                hostCalls += 1
                SearchExecutionResult.Succeeded
            },
        )
        val invocation = async { executor.execute(entry.id(), CommandContext.SEARCH) }
        runCurrent()

        invocation.cancelAndJoin()

        assertTrue(contributionCancelled)
        assertEquals(0, hostCalls)
    }
}

private open class EmptyExecutableCatalog : ExecutableSearchTargetCatalog {
    override val invalidations = kotlinx.coroutines.flow.emptyFlow<Unit>()
    override fun resolve(id: SearchActionId): ResolvedSearchTarget? = null
    override fun executionTarget(id: SearchActionId): SearchExecutionTarget? = null
}

private data object TestCommandConfiguration

private object TestCommandCodec : ConfigurationCodec<TestCommandConfiguration> {
    override val configType = ConfigTypeId.parse("org.quicklauncher.command.test/config")
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = TestCommandConfiguration
    override fun encode(value: TestCommandConfiguration) = EncodedConfiguration.of("{}")
    override fun decode(encoded: EncodedConfiguration): CodecResult<TestCommandConfiguration> =
        CodecResult.Decoded(default)
}

private fun command(
    local: String,
    contexts: Set<CommandContext>,
    resultKinds: Set<CommandResultKind>,
    capabilities: Set<CapabilityId> = emptySet(),
    behavior: suspend (CommandInput<TestCommandConfiguration>) -> CommandResult,
): RegisteredLauncherCommand<TestCommandConfiguration> {
    val id = ContributionId.parse("org.quicklauncher.command/$local")
    return RegisteredLauncherCommand(
        LauncherCommandDescriptor(
            ContributionMetadata(
                id,
                ContributionTypes.LAUNCHER_COMMAND,
                ContractMajor.of(1),
                DisplayText.of("Test command"),
                DisplayText.of("Synthetic command used by the host seam test"),
                emptySet(),
                capabilities,
                null,
                TestCommandCodec.configType,
            ),
            contexts,
            resultKinds,
        ),
        object : LauncherCommandContribution<TestCommandConfiguration> {
            override suspend fun execute(
                input: CommandInput<TestCommandConfiguration>,
            ): CommandResult = behavior(input)
        },
        TestCommandCodec,
        object : LauncherCommandContractTestDeclaration<TestCommandConfiguration> {
            override val contributionId = id
            override val scenarios: Set<PreviewScenario> = emptySet()
            override val performanceHooks: List<PerformanceHookDeclaration> = emptyList()
        },
    )
}

private fun RegisteredLauncherCommand<*>.id(): ContributionId = descriptor.metadata.id
