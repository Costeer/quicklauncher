package org.quicklauncher.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.data.preferences.OnboardingState
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.preferences.HistoryPolicy
import org.quicklauncher.host.data.preferences.NotificationStyle
import org.quicklauncher.host.data.preferences.PrivateSpaceVisibility
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.RegistryPlacementPolicy
import org.quicklauncher.host.data.store.registryConfigurationResolver
import org.quicklauncher.host.editor.OnboardingResult
import org.quicklauncher.host.editor.OnboardingSafeLayoutFactory
import org.quicklauncher.host.editor.SafeLayoutDraft
import org.quicklauncher.host.editor.TemplateOnboarding
import org.quicklauncher.host.platform.persistence.AndroidLauncherStoreFactory
import org.quicklauncher.host.runtime.SafeLayoutIds
import org.quicklauncher.host.runtime.catalog.AppCatalogOverrideSource
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.catalog.AppPlatform
import org.quicklauncher.host.runtime.catalog.AppPlatformActivity
import org.quicklauncher.host.runtime.catalog.AppPlatformInvalidation
import org.quicklauncher.host.runtime.catalog.AppPlatformProfile
import org.quicklauncher.host.runtime.catalog.AppPlatformSnapshot
import org.quicklauncher.host.runtime.catalog.AppProfileKind
import org.quicklauncher.host.runtime.catalog.DefaultAppCatalog
import org.quicklauncher.registry.production.productionRegistry

/** Exists only in the locally signed benchmark variant. Release and preview APKs cannot include it. */
class BenchmarkFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_SEED -> seedFiveDestinationFixture(context)
                ACTION_CATALOG -> runSyntheticCatalog()
                else -> error("Unknown benchmark fixture action")
            }
            resultCode = Activity.RESULT_OK
            resultData = FIXTURE_ID
        } catch (failure: RuntimeException) {
            resultCode = Activity.RESULT_CANCELED
            resultData = failure::class.java.simpleName
        }
    }

    private fun seedFiveDestinationFixture(context: Context) {
        check(context.deleteDatabase(MainActivity.DATABASE_NAME) ||
            !context.getDatabasePath(MainActivity.DATABASE_NAME).exists()) {
            "Could not reset benchmark database"
        }
        val store = AndroidLauncherStoreFactory(context).open(
            MainActivity.DATABASE_NAME,
            registryConfigurationResolver(productionRegistry),
            RegistryPlacementPolicy(productionRegistry),
        )
        try {
            val onboarding = TemplateOnboarding(
                productionRegistry,
                store,
                OnboardingSafeLayoutFactory { destinationId ->
                    SafeLayoutIds.recordsFor(destinationId, selected = false).let { safe ->
                        SafeLayoutDraft(safe.layout, safe.instance, safe.configuration)
                    }
                },
            )
            val preview = onboarding.previewDefault(MODULAR_TEMPLATE)
                as OnboardingResult.Previewed
            check(runBlocking { onboarding.install(preview.preview) } == OnboardingResult.Installed) {
                "Could not install benchmark launcher plan"
            }
            runBlocking {
                installSafeDestination(
                    store,
                    DestinationId.parse("org.quicklauncher.destination/benchmark-left"),
                    "Benchmark left",
                    DestinationCoordinate(-1, 0),
                )
                installSafeDestination(
                    store,
                    DestinationId.parse("org.quicklauncher.destination/benchmark-down"),
                    "Benchmark down",
                    DestinationCoordinate(0, 1),
                )
                val preferences = requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ) { "Benchmark preferences are unavailable" }
                // Reset every persisted launcher preference. Search is deliberately initialized
                // with only the deterministic public Settings provider used by the fixed query.
                preferences.setGestureMode(GestureMode.CONTENT_HANDOFF)
                preferences.setEnabledSearchProviders(setOf(PUBLIC_SETTINGS_PROVIDER))
                preferences.setHistoryPolicy(HistoryPolicy.DISABLED)
                preferences.setThemeProfile(null)
                preferences.setNotificationStyle(NotificationStyle.HIDDEN)
                preferences.setOnboardingState(OnboardingState.COMPLETED)
                preferences.setPrivateSpaceVisibility(PrivateSpaceVisibility.VISIBLE)
            }
        } finally {
            (store as AutoCloseable).close()
        }
    }

    private suspend fun installSafeDestination(
        store: LauncherStore,
        destinationId: DestinationId,
        name: String,
        coordinate: DestinationCoordinate,
    ) {
        val snapshot = store.read()
        val safe = SafeLayoutIds.recordsFor(destinationId, selected = true)
        val result = store.commit(
            LauncherTransaction(
                snapshot.revision,
                listOf(
                    LauncherEdit.InstallDestination(
                        DestinationInstall(
                            DestinationRecord(destinationId, name, coordinate),
                            safe.layout,
                            safe.instance,
                            safe.configuration,
                        ),
                    ),
                ),
            ),
        )
        check(result is CommitResult.Committed) { "Could not install $name: $result" }
    }

    private fun runSyntheticCatalog() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val profile = AppPlatformProfile(PERSONAL_PROFILE, AppProfileKind.PERSONAL, false, true)
        val activities = (0 until CATALOG_SIZE).map { index ->
            val packageName = PackageName.parse("org.quicklauncher.fixture.app$index")
            AppPlatformActivity(
                AppActivityIdentity(
                    PERSONAL_PROFILE,
                    packageName,
                    ActivityName.parse("${packageName.value}.MainActivity"),
                ),
                "Fixture app $index",
                AppIcon.of(byteArrayOf(index.toByte())),
            )
        }
        val platform = SyntheticAppPlatform(AppPlatformSnapshot(listOf(profile), activities))
        val catalog = DefaultAppCatalog(platform, AppCatalogOverrideSource { emptyList() }, scope)
        try {
            runBlocking { catalog.refresh() }
            check(catalog.state.value.profiles.single().apps.size == CATALOG_SIZE) {
                "Synthetic catalog did not publish all bounded fixtures"
            }
        } finally {
            catalog.close()
            scope.cancel()
        }
    }

    private class SyntheticAppPlatform(
        private val fixture: AppPlatformSnapshot,
    ) : AppPlatform {
        override val invalidations: Flow<AppPlatformInvalidation> = emptyFlow()

        override suspend fun snapshot(): AppPlatformSnapshot = fixture

        override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult =
            AppLaunchResult.ActivityUnavailable

        override fun close() = Unit
    }

    companion object {
        const val PERMISSION = "org.quicklauncher.permission.CONTROL_BENCHMARK_FIXTURE"
        const val ACTION_SEED = "org.quicklauncher.benchmark.SEED_FIVE_DESTINATIONS"
        const val ACTION_CATALOG = "org.quicklauncher.benchmark.RUN_CATALOG"
        const val FIXTURE_ID = "phase9-five-destination-v1"
        private const val CATALOG_SIZE = 128
        private val PUBLIC_SETTINGS_PROVIDER =
            ContributionId.parse("org.quicklauncher.search/settings")
        private val MODULAR_TEMPLATE = ContributionId.parse("org.quicklauncher.template/modular")
        private val PERSONAL_PROFILE = ProfileSerial.of(0L)
    }
}
