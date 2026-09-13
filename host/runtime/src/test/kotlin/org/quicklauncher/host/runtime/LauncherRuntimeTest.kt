package org.quicklauncher.host.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ConfigurationReconciliation
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.runtime.catalog.AppCatalog
import org.quicklauncher.host.runtime.catalog.AppCatalogSnapshot
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.catalog.AppPlatform
import org.quicklauncher.host.runtime.catalog.AppPlatformActivity
import org.quicklauncher.host.runtime.catalog.AppPlatformInvalidation
import org.quicklauncher.host.runtime.catalog.AppPlatformProfile
import org.quicklauncher.host.runtime.catalog.AppPlatformSnapshot
import org.quicklauncher.host.runtime.catalog.AppProfileKind
import org.quicklauncher.host.runtime.catalog.CatalogApp
import org.quicklauncher.host.runtime.catalog.CatalogProfile
import org.quicklauncher.host.runtime.catalog.DefaultAppCatalog
import org.quicklauncher.host.runtime.catalog.StoreAppCatalogOverrideSource
import org.quicklauncher.host.runtime.diagnostics.BoundedDiagnosticLog
import org.quicklauncher.host.runtime.diagnostics.DiagnosticRecord
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStorage
import org.quicklauncher.host.runtime.permissions.HomeRoleGateway
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestDispatch
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestOutcome
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.permissions.HomeSettingsOpenResult

class LauncherRuntimeTest {
    @Test
    fun `startup provisions the safe surface and never prompts for Home automatically`() = runTest {
        val catalog = FakeCatalog()
        val role = FakeHomeRoleGateway()
        val runtime = runtime(catalog, role)

        runtime.start(LauncherEntry.HOME)

        assertEquals(LauncherRuntimeStatus.READY, runtime.state.value.status)
        assertEquals(SafeLayoutIds.START_DESTINATION, runtime.state.value.currentDestinationId)
        assertEquals(LauncherSurface.SAFE_LAYOUT, runtime.state.value.surface)
        assertEquals(listOf("Alpha", "Zeta"), runtime.state.value.visibleApps.map { it.label })
        assertEquals(HomeRoleState.AVAILABLE_NOT_HELD, runtime.state.value.homeRoleState)
        assertEquals(0, role.requests)
        assertEquals(1, catalog.refreshes)
        runtime.close()
    }

    @Test
    fun `repeated Home closes overlays clears local query and rereads the start destination`() = runTest {
        val runtime = runtime(FakeCatalog(), FakeHomeRoleGateway())
        runtime.start(LauncherEntry.APP_ICON)
        runtime.open(LauncherSurface.SETTINGS)
        runtime.setLocalQuery("alp")

        runtime.onHomeIntent()

        assertEquals(LauncherSurface.SAFE_LAYOUT, runtime.state.value.surface)
        assertEquals("", runtime.state.value.localQuery)
        assertEquals(SafeLayoutIds.START_DESTINATION, runtime.state.value.currentDestinationId)
        runtime.close()
    }

    @Test
    fun `safe layout search is local and app launch remains typed`() = runTest {
        val catalog = FakeCatalog()
        val runtime = runtime(catalog, FakeHomeRoleGateway())
        runtime.start(LauncherEntry.HOME)

        runtime.setLocalQuery("zet")
        val visible = runtime.state.value.visibleApps.single()
        val launch = runtime.launch(visible.identity)

        assertEquals("Zeta", visible.label)
        assertEquals(AppLaunchResult.Launched, launch)
        assertEquals(listOf(visible.identity), catalog.launches)
        runtime.close()
    }

    @Test
    fun `Home role request requires an explicit contextual action and rechecks ownership`() = runTest {
        val role = FakeHomeRoleGateway()
        val runtime = runtime(FakeCatalog(), role)
        runtime.start(LauncherEntry.HOME)
        role.grantOnRequest = true

        val outcome = runtime.requestHomeRole(HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED)

        assertEquals(HomeRoleRequestOutcome.Granted, outcome)
        assertEquals(HomeRoleState.HELD, runtime.state.value.homeRoleState)
        assertEquals(1, role.requests)
        runtime.close()
    }

    @Test
    fun `startup renderer failure quarantines optional layout and selects safe layout`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        SafeLayoutProvisioner(store).ensureAvailable()
        val before = store.read()
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/crashing-layout")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.test/crashing-config")
        val contributionId = ContributionId.parse("org.quicklauncher.test/crashing-layout")
        val retained = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        DestinationLayoutRecord(
                            SafeLayoutIds.START_DESTINATION,
                            contributionId,
                            instanceId,
                            selected = false,
                        ),
                        ModuleInstanceRecord(instanceId, contributionId, configurationId),
                        StoredConfigurationDocument(
                            configurationId,
                            ConfigurationDocument(
                                ConfigTypeId.parse("org.quicklauncher.test/crashing-config"),
                                SchemaVersion.of(1),
                                EncodedConfiguration.of("{}"),
                            ),
                        ),
                    ),
                    LauncherEdit.SelectLayout(SafeLayoutIds.START_DESTINATION, instanceId),
                ),
            ),
        ) as CommitResult.Committed
        val runtime = runtime(
            catalog = FakeCatalog(),
            role = FakeHomeRoleGateway(),
            store = store,
            selectedLayoutRestorer = SelectedLayoutRestorer {
                throw IllegalStateException("deliberate renderer failure")
            },
        )

        runtime.start(LauncherEntry.RESTORE, SafeLayoutIds.START_DESTINATION)

        val after = store.read()
        assertEquals(LauncherRuntimeStatus.READY, runtime.state.value.status)
        assertEquals(
            SafeLayoutIds.START_INSTANCE,
            after.destinationLayouts.single { it.selected }.layoutInstanceId,
        )
        assertTrue(
            after.moduleInstances.single { it.id == instanceId }.status is
                ModuleInstanceStatus.Quarantined,
        )
        assertEquals(retained.state.configurationDocuments, after.configurationDocuments)
        assertEquals(listOf(instanceId), runtime.state.value.recoveryInstances.map { it.id })
        runtime.close()
    }

    @Test
    fun `renderer retry reselects and invokes the quarantined layout before clearing recovery`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        SafeLayoutProvisioner(store).ensureAvailable()
        val before = store.read()
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/retry-layout")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.test/retry-config")
        val contributionId = ContributionId.parse("org.quicklauncher.test/retry-layout")
        store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        DestinationLayoutRecord(
                            SafeLayoutIds.START_DESTINATION,
                            contributionId,
                            instanceId,
                            selected = false,
                        ),
                        ModuleInstanceRecord(instanceId, contributionId, configurationId),
                        StoredConfigurationDocument(
                            configurationId,
                            ConfigurationDocument(
                                ConfigTypeId.parse("org.quicklauncher.test/retry-config"),
                                SchemaVersion.of(1),
                                EncodedConfiguration.of("{}"),
                            ),
                        ),
                    ),
                    LauncherEdit.SelectLayout(SafeLayoutIds.START_DESTINATION, instanceId),
                ),
            ),
        )
        var attempts = 0
        val runtime = runtime(
            catalog = FakeCatalog(),
            role = FakeHomeRoleGateway(),
            store = store,
            selectedLayoutRestorer = SelectedLayoutRestorer {
                attempts += 1
                if (attempts == 1) error("first attempt fails")
            },
        )
        runtime.start(LauncherEntry.RESTORE, SafeLayoutIds.START_DESTINATION)

        val rejection = runtime.retryRenderer(instanceId)

        assertEquals(null, rejection)
        assertEquals(2, attempts)
        assertEquals(
            instanceId,
            store.read().destinationLayouts.single { it.selected }.layoutInstanceId,
        )
        assertTrue(runtime.state.value.recoveryInstances.isEmpty())
        runtime.close()
    }

    @Test
    fun `live renderer failure publishes the committed revision and safe home surface`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        SafeLayoutProvisioner(store).ensureAvailable()
        val before = store.read()
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/live-failing-layout")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.test/live-failing-config")
        val contributionId = ContributionId.parse("org.quicklauncher.test/live-failing-layout")
        require(
            store.commit(
                LauncherTransaction(
                    before.revision,
                    listOf(
                        LauncherEdit.RetainLayout(
                            DestinationLayoutRecord(
                                SafeLayoutIds.START_DESTINATION,
                                contributionId,
                                instanceId,
                                selected = false,
                            ),
                            ModuleInstanceRecord(instanceId, contributionId, configurationId),
                            StoredConfigurationDocument(
                                configurationId,
                                ConfigurationDocument(
                                    ConfigTypeId.parse("org.quicklauncher.test/live-failing-config"),
                                    SchemaVersion.of(1),
                                    EncodedConfiguration.of("{}"),
                                ),
                            ),
                        ),
                    ),
                ),
            ) is CommitResult.Committed,
        )
        val retained = store.read()
        require(
            store.commit(
                LauncherTransaction(
                    retained.revision,
                    listOf(LauncherEdit.SelectLayout(SafeLayoutIds.START_DESTINATION, instanceId)),
                ),
            ) is CommitResult.Committed,
        )
        assertEquals(
            instanceId,
            store.read().destinationLayouts.single { it.selected }.layoutInstanceId,
        )
        val runtime = runtime(
            catalog = FakeCatalog(),
            role = FakeHomeRoleGateway(),
            store = store,
            selectedLayoutRestorer = SelectedLayoutRestorer {},
        )
        val publishedBeforeFailure = runtime.state.value.storeRevision

        assertEquals(null, runtime.reportRendererFailure(instanceId, IllegalStateException("failed")))

        assertTrue(runtime.state.value.storeRevision.value > publishedBeforeFailure.value)
        assertEquals(LauncherSurface.SAFE_LAYOUT, runtime.state.value.surface)
        runtime.close()
    }

    @Test
    fun `store failure keeps app launching and Settings usable across Home and resume`() = runTest {
        val runtime = runtime(
            catalog = FakeCatalog(),
            role = FakeHomeRoleGateway(),
            store = FailingStore(),
        )

        runtime.start(LauncherEntry.HOME)

        assertEquals(LauncherRuntimeStatus.ERROR, runtime.state.value.status)
        assertEquals(listOf("Alpha", "Zeta"), runtime.state.value.visibleApps.map { it.label })
        runtime.open(LauncherSurface.SETTINGS)
        assertEquals(LauncherSurface.SETTINGS, runtime.state.value.surface)
        runtime.closeOverlay()
        runtime.setLocalQuery("alp")
        assertEquals(listOf("Alpha"), runtime.state.value.visibleApps.map { it.label })

        runtime.onResume()
        runtime.onHomeIntent()

        assertEquals(LauncherRuntimeStatus.ERROR, runtime.state.value.status)
        assertEquals(LauncherSurface.SAFE_LAYOUT, runtime.state.value.surface)
        assertEquals("", runtime.state.value.localQuery)
        assertEquals(listOf("Alpha", "Zeta"), runtime.state.value.visibleApps.map { it.label })
        runtime.close()
    }

    @Test
    fun `store backed override failure cannot hide platform apps during cold startup failure`() = runTest {
        val store = FailingStore()
        val profile = ProfileSerial.of(0)
        val identity = AppActivityIdentity(
            profile,
            PackageName.parse("org.example.alpha"),
            ActivityName.parse("org.example.alpha.MainActivity"),
        )
        val catalog = DefaultAppCatalog(
            platform = StaticPlatform(profile, identity),
            overrideSource = StoreAppCatalogOverrideSource(store),
            parentScope = backgroundScope,
        )
        val runtime = runtime(catalog, FakeHomeRoleGateway(), store)

        runtime.start(LauncherEntry.HOME)

        assertEquals(LauncherRuntimeStatus.ERROR, runtime.state.value.status)
        assertEquals(AppCatalogStatus.READY, runtime.state.value.catalogStatus)
        assertEquals(listOf("Alpha"), runtime.state.value.visibleApps.map { it.label })
        assertEquals(AppLaunchResult.Launched, runtime.launch(identity))
        runtime.close()
    }

    @Test
    fun `complete policy failure fails ordinary apps closed but retains typed Settings recovery launch`() = runTest {
        val store = FailingStore(policyAvailable = false)
        val profile = ProfileSerial.of(0)
        val identity = AppActivityIdentity(
            profile,
            PackageName.parse("org.example.alpha"),
            ActivityName.parse("org.example.alpha.MainActivity"),
        )
        val catalog = DefaultAppCatalog(
            platform = StaticPlatform(profile, identity),
            overrideSource = StoreAppCatalogOverrideSource(store),
            parentScope = backgroundScope,
        )
        val runtime = runtime(catalog, FakeHomeRoleGateway(), store)

        runtime.start(LauncherEntry.HOME)

        assertEquals(LauncherRuntimeStatus.ERROR, runtime.state.value.status)
        assertEquals(AppCatalogStatus.ERROR, runtime.state.value.catalogStatus)
        assertTrue(runtime.state.value.visibleApps.isEmpty())
        assertEquals(listOf("Alpha"), runtime.state.value.settingsApps.map { it.label })
        runtime.setLocalQuery("alpha")
        assertTrue(runtime.state.value.visibleApps.isEmpty())
        assertEquals(listOf("Alpha"), runtime.state.value.settingsApps.map { it.label })
        assertEquals(AppLaunchResult.Launched, runtime.launch(identity))
        runtime.close()
    }

    @Test
    fun `matching query does not return an app hidden from search`() = runTest {
        val runtime = runtime(
            catalog = FakeCatalog(alphaCollectionVisible = false, alphaSearchVisible = false),
            role = FakeHomeRoleGateway(),
        )
        runtime.start(LauncherEntry.HOME)

        runtime.setLocalQuery("alpha")

        assertTrue(runtime.state.value.visibleApps.isEmpty())
        assertEquals(listOf("Alpha", "Zeta"), runtime.state.value.settingsApps.map { it.label })
        runtime.close()
    }

    @Test
    fun `matching query reveals a collection-hidden app when search recovery is allowed`() = runTest {
        val runtime = runtime(
            catalog = FakeCatalog(alphaCollectionVisible = false, alphaSearchVisible = true),
            role = FakeHomeRoleGateway(),
        )
        runtime.start(LauncherEntry.HOME)

        assertEquals(listOf("Zeta"), runtime.state.value.visibleApps.map { it.label })
        runtime.setLocalQuery("alpha")

        assertEquals(listOf("Alpha"), runtime.state.value.visibleApps.map { it.label })
        assertTrue(runtime.state.value.visibleApps.single().collectionVisible.not())
        runtime.close()
    }

    @Test
    fun `map selection uses the retained snapshot when the store becomes unavailable`() = runTest {
        val store = ToggleStore()
        val runtime = runtime(FakeCatalog(), FakeHomeRoleGateway(), store)
        runtime.start(LauncherEntry.HOME)
        store.fail = true
        runtime.onResume()
        runtime.open(LauncherSurface.MAP_OVERVIEW)

        val selected = runtime.selectDestination(SafeLayoutIds.START_DESTINATION)

        assertTrue(selected)
        assertEquals(LauncherRuntimeStatus.ERROR, runtime.state.value.status)
        assertEquals(SafeLayoutIds.START_DESTINATION, runtime.state.value.currentDestinationId)
        runtime.close()
    }

    private fun TestScope.runtime(
        catalog: AppCatalog,
        role: FakeHomeRoleGateway,
        store: LauncherStore = InMemoryLauncherStore(),
        selectedLayoutRestorer: SelectedLayoutRestorer? = null,
    ): LauncherRuntime = DefaultLauncherRuntime(
        store = store,
        catalog = catalog,
        roleGateway = role,
        diagnostics = BoundedDiagnosticLog(MemoryDiagnosticStorage(), { 1L }),
        appVersion = "phase3-test",
        parentScope = backgroundScope,
        selectedLayoutRestorer = selectedLayoutRestorer,
    )

    private class FakeCatalog(
        private val alphaCollectionVisible: Boolean = true,
        private val alphaSearchVisible: Boolean = true,
    ) : AppCatalog {
        private val personal = ProfileSerial.of(0)
        private val mutableState = MutableStateFlow(AppCatalogSnapshot.Loading)
        override val state: StateFlow<AppCatalogSnapshot> = mutableState
        val launches = mutableListOf<AppActivityIdentity>()
        var refreshes = 0

        override suspend fun refresh() {
            refreshes += 1
            mutableState.value = AppCatalogSnapshot(
                AppCatalogStatus.READY,
                listOf(
                    CatalogProfile(
                        personal,
                        AppProfileKind.PERSONAL,
                        available = true,
                        badgeText = null,
                        apps = listOf(
                            app("org.example.zeta", "Zeta"),
                            app(
                                "org.example.alpha",
                                "Alpha",
                                collectionVisible = alphaCollectionVisible,
                                searchVisible = alphaSearchVisible,
                            ),
                        ),
                    ),
                ),
            )
        }

        override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult {
            launches += identity
            return AppLaunchResult.Launched
        }

        override fun close() = Unit

        private fun app(
            packageName: String,
            label: String,
            collectionVisible: Boolean = true,
            searchVisible: Boolean = true,
        ): CatalogApp = CatalogApp(
            identity = AppActivityIdentity(
                personal,
                PackageName.parse(packageName),
                ActivityName.parse("$packageName.MainActivity"),
            ),
            label = label,
            icon = AppIcon.of(byteArrayOf(1)),
            favorite = false,
            collectionVisible = collectionVisible,
            searchVisible = searchVisible,
        )
    }

    private class FakeHomeRoleGateway : HomeRoleGateway {
        var requests = 0
        var grantOnRequest = false
        private var state = HomeRoleState.AVAILABLE_NOT_HELD

        override fun currentState(): HomeRoleState = state

        override suspend fun requestHomeRole(
            reason: HomeRoleRequestReason,
        ): HomeRoleRequestDispatch {
            requests += 1
            if (grantOnRequest) state = HomeRoleState.HELD
            return HomeRoleRequestDispatch.Returned
        }

        override fun openHomeSettings(): HomeSettingsOpenResult = HomeSettingsOpenResult.Opened
    }

    private class FailingStore(
        private val policyAvailable: Boolean = true,
    ) : LauncherStore {
        override suspend fun read() = throw IllegalStateException("store unavailable")

        override suspend fun readAppOverrides() = if (policyAvailable) {
            emptyList<org.quicklauncher.host.data.store.AppOverrideRecord>()
        } else {
            throw IllegalStateException("app visibility policy unavailable")
        }

        override suspend fun commit(transaction: LauncherTransaction): CommitResult =
            throw IllegalStateException("store unavailable")

        override suspend fun reconcileConfigurations(): ConfigurationReconciliation =
            throw IllegalStateException("store unavailable")
    }

    private class ToggleStore(
        private val delegate: LauncherStore = InMemoryLauncherStore(),
    ) : LauncherStore {
        var fail = false

        override suspend fun read() = if (fail) {
            throw IllegalStateException("store unavailable")
        } else {
            delegate.read()
        }

        override suspend fun commit(transaction: LauncherTransaction): CommitResult = if (fail) {
            throw IllegalStateException("store unavailable")
        } else {
            delegate.commit(transaction)
        }

        override suspend fun reconcileConfigurations(): ConfigurationReconciliation = if (fail) {
            throw IllegalStateException("store unavailable")
        } else {
            delegate.reconcileConfigurations()
        }
    }

    private class StaticPlatform(
        profile: ProfileSerial,
        private val identity: AppActivityIdentity,
    ) : AppPlatform {
        override val invalidations = emptyFlow<AppPlatformInvalidation>()
        private val snapshot = AppPlatformSnapshot(
            profiles = listOf(AppPlatformProfile(profile, AppProfileKind.PERSONAL, false, true)),
            activities = listOf(AppPlatformActivity(identity, "Alpha", AppIcon.of(byteArrayOf(1)))),
        )

        override suspend fun snapshot(): AppPlatformSnapshot = snapshot

        override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult =
            if (identity == this.identity) AppLaunchResult.Launched else AppLaunchResult.ActivityUnavailable

        override fun close() = Unit
    }

    private class MemoryDiagnosticStorage : DiagnosticStorage {
        private var records: List<DiagnosticRecord> = emptyList()

        override suspend fun read(): List<DiagnosticRecord> = records

        override suspend fun replace(records: List<DiagnosticRecord>) {
            this.records = records
        }
    }
}
