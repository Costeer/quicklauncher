package org.quicklauncher.host.platform.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceActionResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceSettingsResult
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.ProfileCoordinatorState
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.WorkModeResult
import org.quicklauncher.host.runtime.search.SearchExecutionResult
import org.quicklauncher.host.runtime.search.SearchExecutionTarget
import org.quicklauncher.host.runtime.search.SearchSettingsRoute
import org.quicklauncher.host.runtime.search.WebProviderId

@RunWith(AndroidJUnit4::class)
class SearchPlatformInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val packageName = context.packageName
    private var syntheticRawContactId: Long? = null
    private var fileGrant: Uri? = null

    @Before
    fun requireSupportedApi() {
        assertTrue("Phase 6 search requires API 35 or newer", Build.VERSION.SDK_INT >= 35)
    }

    @After
    fun removeSyntheticState() {
        syntheticRawContactId?.let { id ->
            runCatching {
                context.contentResolver.delete(
                    ContactsContract.RawContacts.CONTENT_URI,
                    "${ContactsContract.RawContacts._ID}=?",
                    arrayOf(id.toString()),
                )
            }
        }
        fileGrant?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test
    fun contactsFailClosedThenRecoverWithSyntheticData() = runBlocking {
        val originalRead = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!originalRead) {
            val denied = AndroidContactSearchAdapter(context, personalCoordinator()).query(
                SearchQuery.of(CONTACT_QUERY),
                currentProfile(),
                null,
            )
            assertEquals(ContactSearchResult.Denied, denied)
        }

        shell("pm grant $packageName ${Manifest.permission.READ_CONTACTS}")
        shell("pm grant $packageName ${Manifest.permission.WRITE_CONTACTS}")
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS))
        syntheticRawContactId = insertSyntheticContact()

        val adapter = AndroidContactSearchAdapter(context, personalCoordinator())
        val available = adapter.query(SearchQuery.of(CONTACT_QUERY), currentProfile(), null)
            as ContactSearchResult.Available
        assertTrue(available.records.any { it.displayName == CONTACT_DISPLAY })
        assertFalse(available.records.any(ContactSearchRecord::work))
        assertFalse(available.toString().contains(CONTACT_DISPLAY))
    }

    @Test
    fun contactsRejectPrivateClassificationBeforeLookupAndUseQuickContactActions() = runBlocking {
        val profile = currentProfile()
        val adapter = AndroidContactSearchAdapter(
            context,
            FixtureProfileCoordinator(profile, ProfileKind.PRIVATE),
        )

        assertEquals(
            ContactSearchResult.Unavailable,
            adapter.query(SearchQuery.of(CONTACT_QUERY), profile, null),
        )
        val uri = Uri.parse("content://com.android.contacts/contacts/lookup/synthetic/1")
        val intent = adapter.quickContactIntent(uri)
        assertEquals(ContactsContract.QuickContact.ACTION_QUICK_CONTACT, intent.action)
        assertEquals(uri, intent.data)
    }

    @Test
    fun persistedSafGrantIsRequiredAndSessionReleaseInvalidatesTheTarget() = runBlocking {
        val adapter = AndroidFileSearchAdapter(context)
        val initiallyDenied = adapter.query(71L, SearchQuery.of("phase6"))
        if (context.contentResolver.persistedUriPermissions.isEmpty()) {
            assertEquals(FileSearchResult.Denied, initiallyDenied)
        }
        val uri = DocumentsContract.buildDocumentUri(
            PhaseSixFixtureDocumentsProvider.AUTHORITY,
            PhaseSixFixtureDocumentsProvider.DOCUMENT_ID,
        )
        context.grantUriPermission(
            packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fileGrant = uri

        val available = adapter.query(72L, SearchQuery.of("phase6")) as FileSearchResult.Available
        val record = available.records.single { it.displayName == PhaseSixFixtureDocumentsProvider.DISPLAY_NAME }
        assertFalse(record.toString().contains(PhaseSixFixtureDocumentsProvider.DISPLAY_NAME))
        val target = SearchExecutionTarget.File(72L, record.authorizationId, record.documentId)
        adapter.releaseSession(72L)
        assertEquals(SearchExecutionResult.Unavailable, adapter.launch(target))
    }

    @Test
    fun treeGrantUsesProviderSearchWhenTheRootDeclaresSupport() = runBlocking {
        val uri = DocumentsContract.buildTreeDocumentUri(
            PhaseSixFixtureDocumentsProvider.AUTHORITY,
            PhaseSixFixtureDocumentsProvider.DOCUMENT_ID,
        )
        context.grantUriPermission(
            packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fileGrant = uri

        val available = AndroidFileSearchAdapter(context).query(73L, SearchQuery.of("phase6"))
            as FileSearchResult.Available

        assertTrue(available.records.any {
            it.displayName == PhaseSixFixtureDocumentsProvider.DISPLAY_NAME
        })
    }

    @Test
    fun publicAndGrapheneSettingsRoutesAreResolvedWithoutPrivilegedAccess() {
        val settings = AndroidSettingsRouteLauncher(context)
        assertTrue(settings.isCallable(SearchExecutionTarget.Setting(SearchSettingsRoute.ROOT)))
        assertTrue(settings.isCallable(SearchExecutionTarget.Setting(SearchSettingsRoute.SECURITY)))
        val native = SearchExecutionTarget.Setting(
            SearchSettingsRoute.GRAPHENE_NATIVE_DEBUGGING,
            PackageName.parse("org.quicklauncher.fixture"),
            SearchSettingsRoute.APPLICATION_DETAILS,
        )
        assertTrue(
            settings.isCallable(
                native,
            ),
        )
        val malloc = SearchExecutionTarget.Setting(
            SearchSettingsRoute.GRAPHENE_HARDENED_MALLOC,
            PackageName.parse("org.quicklauncher.fixture"),
            SearchSettingsRoute.APPLICATION_DETAILS,
        )
        assertTrue(
            settings.isCallable(
                malloc,
            ),
        )
        assertEquals(
            "com.android.settings.Settings\$AppNativeDebuggingActivity",
            settings.requestedIntent(native)?.component?.className,
        )
        assertEquals(
            "com.android.settings.Settings\$AppHardenedMallocActivity",
            settings.requestedIntent(malloc)?.component?.className,
        )
    }

    @Test
    fun webUrisUseExactHttpsCatalogsAndRejectDangerousDestinations() {
        val launcher = AndroidWebSearchLauncher(context)
        val query = SearchQuery.of("phase6 & intent://fixture")
        val duck = launcher.buildUri(SearchExecutionTarget.Web(WebProviderId.DUCKDUCKGO, query))
        assertNotNull(duck)
        assertEquals("https", duck?.scheme)
        assertEquals("duckduckgo.com", duck?.host)
        assertEquals(query.value, duck?.getQueryParameter("q"))
        assertFalse(duck.toString().contains("intent://fixture"))

        val catalog = AndroidWebSearchLauncher.WebCatalog("duckduckgo.com", "/", "q")
        assertFalse(launcher.validate(Uri.parse("http://duckduckgo.com/?q=fixture"), catalog))
        assertFalse(launcher.validate(Uri.parse("intent://duckduckgo.com/#Intent;end"), catalog))
        assertFalse(launcher.validate(Uri.parse("https://user:pass@duckduckgo.com/?q=fixture"), catalog))
        assertFalse(launcher.validate(Uri.parse("https://duckduckgo.com.evil.invalid/?q=fixture"), catalog))
        assertFalse(launcher.validate(Uri.parse("https://duckduckgo.com/?q=fixture#fragment"), catalog))
        val oversized = Uri.Builder()
            .scheme("https")
            .authority("duckduckgo.com")
            .path("/")
            .appendQueryParameter("q", "x".repeat(5_000))
            .build()
        assertFalse(launcher.validate(oversized, catalog))
    }

    private fun insertSyntheticContact(): Long {
        val raw = android.content.ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
            put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
        }
        val rawUri = requireNotNull(context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw))
        val rawId = requireNotNull(rawUri.lastPathSegment).toLong()
        val name = android.content.ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, CONTACT_DISPLAY)
        }
        requireNotNull(context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, name))
        return rawId
    }

    private fun currentProfile(): ProfileSerial {
        val serial = context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        return ProfileSerial.of(serial)
    }

    private fun personalCoordinator(): ProfileCoordinator = FixtureProfileCoordinator(currentProfile())

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private companion object {
        const val CONTACT_QUERY = "QLP6Fixture"
        const val CONTACT_DISPLAY = "QLP6Fixture Contact"
    }
}

private class FixtureProfileCoordinator(
    serial: ProfileSerial,
    kind: ProfileKind = ProfileKind.PERSONAL,
) : ProfileCoordinator {
    override val state = MutableStateFlow(
        ProfileCoordinatorState(
            listOf(
                ProfileState(
                    serial,
                    kind,
                    ProfileAvailability.AVAILABLE,
                    ProfileTransition.IDLE,
                ),
            ),
            PrivateSpaceState(null, ProfileAvailability.UNRESOLVED, ProfileTransition.IDLE, emptyList()),
        ),
    )

    override suspend fun refresh() = Unit
    override suspend fun setWorkMode(profile: ProfileSerial, enabled: Boolean) = WorkModeResult.NotWorkProfile
    override suspend fun setPrivateSpaceLocked(locked: Boolean) = PrivateSpaceActionResult.Unavailable
    override suspend fun launchPrivate(identity: org.quicklauncher.contracts.domain.AppActivityIdentity) =
        PrivateLaunchResult.WRONG_PROFILE_KIND
    override suspend fun openPrivateSpaceSettings() = PrivateSpaceSettingsResult.Unavailable
    override fun close() = Unit
}
