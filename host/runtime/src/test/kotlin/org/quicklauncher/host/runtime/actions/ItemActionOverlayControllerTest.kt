package org.quicklauncher.host.runtime.actions

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial

class ItemActionOverlayControllerTest {
    @Test
    fun `open exposes only actions valid for the resolved item and current policy`() = runTest {
        val item = app(uninstallAllowed = false, disableAllowed = true)
        val platform = FakeItemActionPlatform().apply {
            eligibility = ItemActionEligibility(
                uninstall = ItemActionPermission.ALLOWED,
                disable = ItemActionPermission.ALLOWED,
            )
        }
        val controller = controller(item, platform = platform)

        val result = controller.open(item.id)

        assertEquals(ItemActionResult.Applied, result)
        val actions = (controller.state.value as ItemActionOverlayState.Open).actions
        assertTrue(ItemAction.FAVORITE in actions)
        assertTrue(ItemAction.HIDE in actions)
        assertTrue(ItemAction.OPEN_DETAILS in actions)
        assertTrue(ItemAction.DISABLE in actions)
        assertFalse(ItemAction.UNINSTALL in actions)
        assertFalse(ItemAction.REMOVE_SHORTCUT in actions)
        assertEquals(listOf(requireNotNull(item.appIdentity)), platform.eligibilityQueries)
        controller.close()
    }

    @Test
    fun `open exposes destructive actions only when host policy and platform both allow them`() = runTest {
        val item = app(uninstallAllowed = true, disableAllowed = true)
        val platform = FakeItemActionPlatform().apply {
            eligibility = ItemActionEligibility(
                uninstall = ItemActionPermission.DENIED,
                disable = ItemActionPermission.ALLOWED,
            )
        }
        val controller = controller(item, platform = platform)

        controller.open(item.id)

        val actions = (controller.state.value as ItemActionOverlayState.Open).actions
        assertFalse(ItemAction.UNINSTALL in actions)
        assertTrue(ItemAction.DISABLE in actions)
        controller.close()
    }

    @Test
    fun `destructive execution revalidates platform eligibility before routing`() = runTest {
        val item = app(uninstallAllowed = true)
        val platform = FakeItemActionPlatform().apply {
            eligibility = ItemActionEligibility(
                uninstall = ItemActionPermission.ALLOWED,
                disable = ItemActionPermission.DENIED,
            )
        }
        val controller = controller(item, platform = platform)
        controller.open(item.id)
        platform.eligibility = ItemActionEligibility.None

        val result = controller.execute(ItemActionCommand.Uninstall(confirmed = true))

        assertEquals(ItemActionResult.Rejected(ItemActionRejection.ACTION_NOT_ALLOWED), result)
        assertTrue(platform.uninstalls.isEmpty())
        assertEquals(2, platform.eligibilityQueries.size)
        controller.close()
    }

    @Test
    fun `hiding asks separately whether search may reveal the app`() = runTest {
        val item = app()
        val mutation = FakeItemActionMutation()
        val controller = controller(item, mutation = mutation)
        controller.open(item.id)

        val prompt = controller.execute(ItemActionCommand.Hide(searchVisible = null))
        val applied = controller.execute(ItemActionCommand.Hide(searchVisible = false))

        assertEquals(ItemActionResult.SearchVisibilityChoiceRequired, prompt)
        assertEquals(ItemActionResult.Applied, applied)
        assertEquals(listOf(Triple(item.id, false, false)), mutation.visibility)
        controller.close()
    }

    @Test
    fun `destructive shortcut removal requires confirmation and uses shortcut-specific mutation`() = runTest {
        val item = shortcut()
        val mutation = FakeItemActionMutation()
        val controller = controller(item, mutation = mutation)
        controller.open(item.id)

        val prompt = controller.execute(ItemActionCommand.RemoveShortcut(confirmed = false))
        val applied = controller.execute(ItemActionCommand.RemoveShortcut(confirmed = true))

        assertEquals(ItemActionResult.ConfirmationRequired(ItemAction.REMOVE_SHORTCUT), prompt)
        assertEquals(ItemActionResult.Applied, applied)
        assertEquals(listOf(item.id), mutation.removedShortcuts)
        assertEquals(ItemActionOverlayState.Closed, controller.state.value)
        controller.close()
    }

    @Test
    fun `shortcut exposes only actions backed by durable shortcut behavior`() = runTest {
        val item = shortcut()
        val controller = controller(item)

        controller.open(item.id)

        val actions = (controller.state.value as ItemActionOverlayState.Open).actions
        assertEquals(setOf(ItemAction.REMOVE_SHORTCUT), actions)
        controller.close()
    }

    @Test
    fun `execution revalidates profile and package availability`() = runTest {
        val item = app()
        val resolver = FakeItemActionResolver(item)
        val platform = FakeItemActionPlatform()
        val controller = DefaultItemActionOverlayController(resolver, FakeItemActionMutation(), platform)
        controller.open(item.id)
        resolver.item = item.copy(availability = ItemAvailability.PROFILE_LOCKED)

        val result = controller.execute(ItemActionCommand.OpenDetails)

        assertEquals(ItemActionResult.Rejected(ItemActionRejection.UNAVAILABLE), result)
        assertTrue(platform.details.isEmpty())
        controller.close()
    }

    @Test
    fun `folder favorite rename details uninstall and profile actions route through typed operations`() = runTest {
        val item = app(uninstallAllowed = true, profileAction = ProfileItemAction.PAUSE)
        val mutation = FakeItemActionMutation()
        val platform = FakeItemActionPlatform()
        val controller = controller(item, mutation, platform)
        controller.open(item.id)

        assertEquals(ItemActionResult.Applied, controller.execute(ItemActionCommand.SetFavorite(true)))
        assertEquals(ItemActionResult.Applied, controller.execute(ItemActionCommand.Rename("Mail work")))
        assertEquals(
            ItemActionResult.Applied,
            controller.execute(ItemActionCommand.AddToFolder(id("folder"))),
        )
        assertEquals(ItemActionResult.Applied, controller.execute(ItemActionCommand.OpenDetails))
        assertEquals(
            ItemActionResult.ConfirmationRequired(ItemAction.UNINSTALL),
            controller.execute(ItemActionCommand.Uninstall(confirmed = false)),
        )
        assertEquals(
            ItemActionResult.Applied,
            controller.execute(ItemActionCommand.Uninstall(confirmed = true)),
        )
        assertEquals(ItemActionResult.Applied, controller.execute(ItemActionCommand.PauseProfile))

        assertEquals(listOf(item.id to true), mutation.favorites)
        assertEquals(listOf(item.id to "Mail work"), mutation.renames)
        assertEquals(listOf(Triple(item.id, id("folder"), true)), mutation.folderChanges)
        assertEquals(listOf(requireNotNull(item.appIdentity)), platform.details)
        assertEquals(listOf(requireNotNull(item.appIdentity)), platform.uninstalls)
        assertEquals(listOf(ProfileSerial.of(10) to ProfileItemAction.PAUSE), platform.profileActions)
        controller.close()
    }

    @Test
    fun `predictive back and repeated home close item actions idempotently`() = runTest {
        val item = app()
        val controller = controller(item)
        controller.open(item.id)

        assertTrue(controller.dismiss())
        assertFalse(controller.dismiss())
        assertEquals(ItemActionOverlayState.Closed, controller.state.value)
        controller.close()
    }

    @Test
    fun `resolver and mutation failures become typed rejections while cancellation propagates`() = runTest {
        val item = app()
        val resolver = FakeItemActionResolver(item)
        val mutation = FakeItemActionMutation()
        val controller = DefaultItemActionOverlayController(resolver, mutation, FakeItemActionPlatform())

        resolver.failure = IllegalStateException("profile disappeared")
        assertEquals(
            ItemActionResult.Rejected(ItemActionRejection.UNAVAILABLE),
            controller.open(item.id),
        )

        resolver.failure = null
        controller.open(item.id)
        mutation.failure = IllegalStateException("store unavailable")
        assertEquals(
            ItemActionResult.Rejected(ItemActionRejection.PERSISTENCE_FAILURE),
            controller.execute(ItemActionCommand.SetFavorite(true)),
        )

        mutation.failure = CancellationException("cancelled")
        assertTrue(
            runCatching { controller.execute(ItemActionCommand.SetFavorite(true)) }
                .exceptionOrNull() is CancellationException,
        )
        controller.close()
    }

    private fun controller(
        item: ActionableItem,
        mutation: FakeItemActionMutation = FakeItemActionMutation(),
        platform: FakeItemActionPlatform = FakeItemActionPlatform(),
    ) = DefaultItemActionOverlayController(FakeItemActionResolver(item), mutation, platform)

    private fun app(
        uninstallAllowed: Boolean = false,
        disableAllowed: Boolean = false,
        profileAction: ProfileItemAction = ProfileItemAction.NONE,
    ) = ActionableItem(
        id = id("mail"),
        label = "Mail",
        kind = ActionableItemKind.APP,
        availability = ItemAvailability.AVAILABLE,
        appIdentity = AppActivityIdentity(
            ProfileSerial.of(10),
            PackageName.parse("org.example.mail"),
            ActivityName.parse("org.example.mail.MainActivity"),
        ),
        favorite = false,
        collectionVisible = true,
        searchVisible = true,
        renameAllowed = true,
        detailsAllowed = true,
        uninstallAllowed = uninstallAllowed,
        disableAllowed = disableAllowed,
        profileAction = profileAction,
        folderIds = setOf(id("folder")),
        memberOfFolderIds = emptySet(),
    )

    private fun shortcut() = ActionableItem(
        id = id("shortcut"),
        label = "Compose",
        kind = ActionableItemKind.SHORTCUT,
        availability = ItemAvailability.AVAILABLE,
        appIdentity = null,
        favorite = false,
        collectionVisible = true,
        searchVisible = true,
        renameAllowed = false,
        detailsAllowed = false,
        uninstallAllowed = false,
        disableAllowed = false,
        profileAction = ProfileItemAction.NONE,
        folderIds = emptySet(),
        memberOfFolderIds = emptySet(),
    )

    private fun id(value: String) = ContentItemId.parse("org.quicklauncher.content/$value")

    private class FakeItemActionResolver(var item: ActionableItem?) : ItemActionResolver {
        var failure: RuntimeException? = null

        override suspend fun resolve(id: ContentItemId): ActionableItem? {
            failure?.let { throw it }
            return item?.takeIf { it.id == id }
        }
    }

    private class FakeItemActionMutation : ItemActionMutation {
        val favorites = mutableListOf<Pair<ContentItemId, Boolean>>()
        val visibility = mutableListOf<Triple<ContentItemId, Boolean, Boolean>>()
        val renames = mutableListOf<Pair<ContentItemId, String>>()
        val folderChanges = mutableListOf<Triple<ContentItemId, ContentItemId, Boolean>>()
        val removedShortcuts = mutableListOf<ContentItemId>()
        var failure: RuntimeException? = null

        override suspend fun setFavorite(id: ContentItemId, favorite: Boolean): ItemMutationResult {
            failure?.let { throw it }
            favorites += id to favorite
            return ItemMutationResult.Committed
        }

        override suspend fun setVisibility(
            id: ContentItemId,
            collectionVisible: Boolean,
            searchVisible: Boolean,
        ): ItemMutationResult {
            visibility += Triple(id, collectionVisible, searchVisible)
            return ItemMutationResult.Committed
        }

        override suspend fun rename(id: ContentItemId, name: String): ItemMutationResult {
            renames += id to name
            return ItemMutationResult.Committed
        }

        override suspend fun setFolderMembership(
            id: ContentItemId,
            folderId: ContentItemId,
            member: Boolean,
        ): ItemMutationResult {
            folderChanges += Triple(id, folderId, member)
            return ItemMutationResult.Committed
        }

        override suspend fun removeShortcut(id: ContentItemId): ItemMutationResult {
            removedShortcuts += id
            return ItemMutationResult.Committed
        }
    }

    private class FakeItemActionPlatform : ItemActionPlatform {
        var eligibility = ItemActionEligibility(
            uninstall = ItemActionPermission.ALLOWED,
            disable = ItemActionPermission.ALLOWED,
        )
        val eligibilityQueries = mutableListOf<AppActivityIdentity>()
        val details = mutableListOf<AppActivityIdentity>()
        val uninstalls = mutableListOf<AppActivityIdentity>()
        val disables = mutableListOf<AppActivityIdentity>()
        val profileActions = mutableListOf<Pair<ProfileSerial, ProfileItemAction>>()

        override suspend fun eligibility(identity: AppActivityIdentity): ItemActionEligibility {
            eligibilityQueries += identity
            return eligibility
        }

        override suspend fun openDetails(identity: AppActivityIdentity): ItemPlatformResult {
            details += identity
            return ItemPlatformResult.Completed
        }

        override suspend fun uninstall(identity: AppActivityIdentity): ItemPlatformResult {
            uninstalls += identity
            return ItemPlatformResult.Completed
        }

        override suspend fun disable(identity: AppActivityIdentity): ItemPlatformResult {
            disables += identity
            return ItemPlatformResult.Completed
        }

        override suspend fun changeProfileMode(
            profile: ProfileSerial,
            action: ProfileItemAction,
        ): ItemPlatformResult {
            profileActions += profile to action
            return ItemPlatformResult.Completed
        }
    }
}
