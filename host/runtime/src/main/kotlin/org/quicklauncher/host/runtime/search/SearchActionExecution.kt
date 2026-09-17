package org.quicklauncher.host.runtime.search

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import org.quicklauncher.contracts.contribution.CancellationSignal
import org.quicklauncher.contracts.contribution.CommandContext
import org.quicklauncher.contracts.contribution.CommandInput
import org.quicklauncher.contracts.contribution.CommandParameters
import org.quicklauncher.contracts.contribution.CommandResult
import org.quicklauncher.contracts.contribution.CommandResultKind
import org.quicklauncher.contracts.contribution.LauncherCommandAction
import org.quicklauncher.contracts.contribution.RegisteredLauncherCommand
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.CommandInvocationId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity

sealed interface SearchExecutionTarget {
    data class App(val identity: AppActivityIdentity) : SearchExecutionTarget
    data class Shortcut(val identity: ShortcutTargetIdentity) : SearchExecutionTarget
    class Contact(
        val profile: org.quicklauncher.contracts.domain.ProfileSerial,
        val contactId: Long,
        val lookupKey: String,
    ) : SearchExecutionTarget {
        init {
            require(contactId >= 0L)
            require(lookupKey.isNotBlank())
            require(lookupKey.length <= 1_024)
        }

        override fun toString(): String = "Contact(redacted)"
    }
    data class File(
        val sessionId: Long,
        val authorizationId: StableKey,
        val documentId: StableKey,
    ) : SearchExecutionTarget {
        init {
            require(sessionId > 0L) { "File search target must belong to an active session" }
        }
    }
    data class Setting(
        val route: SearchSettingsRoute,
        val packageName: PackageName? = null,
        val fallbackRoute: SearchSettingsRoute? = null,
    ) : SearchExecutionTarget {
        init {
            require(fallbackRoute == null || fallbackRoute != route)
            require(
                fallbackRoute == null ||
                    fallbackRoute !in setOf(
                        SearchSettingsRoute.GRAPHENE_NATIVE_DEBUGGING,
                        SearchSettingsRoute.GRAPHENE_HARDENED_MALLOC,
                    ),
            ) { "A Settings fallback must use a public Android route" }
        }
    }
    class Web(val provider: WebProviderId, val query: SearchQuery) : SearchExecutionTarget {
        override fun toString(): String = "Web(provider=$provider, query=redacted)"
    }
    data class Information(val recovery: SearchRecoveryAction) : SearchExecutionTarget
}

enum class SearchSettingsRoute {
    ROOT,
    APPLICATION_DETAILS,
    WIFI,
    BLUETOOTH,
    SECURITY,
    PRIVACY,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    GRAPHENE_NATIVE_DEBUGGING,
    GRAPHENE_HARDENED_MALLOC,
}

enum class WebProviderId {
    DUCKDUCKGO,
    BRAVE,
}

enum class SearchRecoveryAction {
    ENABLE_CONTACTS,
    AUTHORIZE_FILES,
    OPEN_LAUNCHER_SETTINGS,
    OPEN_APP_DETAILS,
}

fun interface SearchSettingsAvailability {
    fun isCallable(target: SearchExecutionTarget.Setting): Boolean
}

interface ExecutableSearchTargetCatalog : SearchTargetCatalog {
    /** Re-resolves current eligibility and returns no stale or inaccessible target. */
    fun executionTarget(id: SearchActionId): SearchExecutionTarget?
}

fun interface SearchTargetPlatform {
    suspend fun execute(target: SearchExecutionTarget): SearchExecutionResult
}

fun interface HostCommandActionExecutor {
    suspend fun execute(action: LauncherCommandAction): SearchExecutionResult
}

fun interface CommandCapabilityChecker {
    fun has(capability: CapabilityId): Boolean
}

class RegisteredCommandExecutor(
    commands: Collection<RegisteredLauncherCommand<*>>,
    private val capabilities: CommandCapabilityChecker,
    private val hostActions: HostCommandActionExecutor,
) {
    private val entries = commands.associateBy { it.descriptor.metadata.id }

    init {
        require(entries.size == commands.size) { "Registered command identities must be unique" }
    }

    fun contains(id: ContributionId, context: CommandContext): Boolean {
        val entry = entries[id] ?: return false
        return context in entry.descriptor.contexts &&
            entry.descriptor.metadata.requiredCapabilities.all(capabilities::has)
    }

    suspend fun execute(id: ContributionId, context: CommandContext): SearchExecutionResult {
        currentCoroutineContext().ensureActive()
        val entry = entries[id] ?: return SearchExecutionResult.Rejected
        if (context !in entry.descriptor.contexts) return SearchExecutionResult.Rejected
        if (!entry.descriptor.metadata.requiredCapabilities.all(capabilities::has)) {
            return SearchExecutionResult.MissingAccess
        }
        val result = try {
            executeRegistered(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return SearchExecutionResult.RecoverableFailure
        }
        return mapResult(entry, result)
    }

    private suspend fun <C : Any> executeRegistered(
        entry: RegisteredLauncherCommand<C>,
    ): CommandResult {
        val job = currentCoroutineContext().job
        return entry.target.execute(
            CommandInput(
                invocationId = CommandInvocationId.parse(
                    "org.quicklauncher.command.invocation/i-${UUID.randomUUID()}",
                ),
                configuration = entry.codec.default,
                parameters = CommandParameters(emptyMap()),
                cancellation = object : CancellationSignal {
                    override val isCancelled: Boolean get() = !job.isActive
                },
            ),
        )
    }

    private suspend fun mapResult(
        entry: RegisteredLauncherCommand<*>,
        result: CommandResult,
    ): SearchExecutionResult = when (result) {
        is CommandResult.Succeeded -> {
            val action = result.action
            when {
                action != null && CommandResultKind.HOST_ACTION !in entry.descriptor.resultKinds ->
                    SearchExecutionResult.RecoverableFailure
                action == null && CommandResultKind.INFORMATION !in entry.descriptor.resultKinds ->
                    SearchExecutionResult.RecoverableFailure
                action != null -> hostActions.execute(action)
                else -> SearchExecutionResult.Succeeded
            }
        }
        CommandResult.Cancelled -> SearchExecutionResult.Cancelled
        is CommandResult.MissingAccess -> {
            if (result.capability !in entry.descriptor.metadata.requiredCapabilities) {
                SearchExecutionResult.RecoverableFailure
            } else {
                SearchExecutionResult.MissingAccess
            }
        }
        is CommandResult.Unavailable -> SearchExecutionResult.Unavailable
        is CommandResult.RecoverableFailure -> SearchExecutionResult.RecoverableFailure
    }
}

class DefaultSearchActionExecutor(
    private val targets: ExecutableSearchTargetCatalog,
    private val platform: SearchTargetPlatform,
    private val commands: RegisteredCommandExecutor,
) : SearchActionExecutor {
    override suspend fun execute(action: SearchResultAction): SearchExecutionResult {
        currentCoroutineContext().ensureActive()
        return when (action) {
            is SearchResultAction.Execute -> {
                // This second lookup is intentionally adjacent to the side effect.
                val target = targets.executionTarget(action.id) ?: return SearchExecutionResult.Rejected
                platform.execute(target)
            }
            is SearchResultAction.InvokeCommand -> commands.execute(
                action.contributionId,
                CommandContext.SEARCH,
            )
        }
    }
}
