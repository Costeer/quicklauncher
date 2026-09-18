package org.quicklauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.backup.archive.PortableBackupSection
import org.quicklauncher.host.backup.archive.PortableBackupSectionType
import org.quicklauncher.host.backup.library.RestoreSelection
import org.quicklauncher.host.backup.library.AuxiliaryStage
import org.quicklauncher.host.backup.library.RestoreAuxiliaryPort
import org.quicklauncher.host.backup.library.RestoreAuxiliaryStageRequest
import org.quicklauncher.host.backup.library.RestoreAuxiliaryValidationRequest
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.DestinationBackgroundRecord
import org.quicklauncher.host.data.store.ThemeProfileRecord
import org.quicklauncher.host.platform.theme.ThemeAssetBackupKind
import org.quicklauncher.host.runtime.theme.BackgroundDefinition
import org.quicklauncher.host.runtime.theme.DestinationBackgroundCodec
import org.quicklauncher.host.runtime.theme.FontSelection
import org.quicklauncher.host.runtime.theme.ImageDerivedTheme
import org.quicklauncher.host.runtime.theme.ModuleThemeOverride
import org.quicklauncher.host.runtime.theme.PaletteDefinition
import org.quicklauncher.host.runtime.theme.ThemeFontRole
import org.quicklauncher.host.runtime.theme.ThemeModePolicy
import org.quicklauncher.host.runtime.theme.ThemeProfile
import org.quicklauncher.host.runtime.theme.ThemeProfileCodec

class ThemeAssetBackupAdaptersTest {
    @Test
    fun `composite staging discards acquired stages when a later port fails`() = runTest {
        var discarded = false
        val first = stagedPort { discarded = true }
        val second = object : RestoreAuxiliaryPort {
            override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = true

            override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage =
                error("injected staging failure")
        }
        val request = RestoreAuxiliaryStageRequest(
            fixture().snapshot(),
            emptyList(),
            RestoreSelection(),
            emptyMap(),
        )

        val failure = runCatching {
            CompositeRestoreAuxiliaryPort(listOf(first, second)).stage(request)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(discarded)
    }

    @Test
    fun `all current profile and destination asset references require matching kinds`() {
        val fixture = fixture()
        val required = ThemeAssetArchiveConsistency.requiredNames(fixture.profiles, fixture.backgrounds)
        val sections = required.map(::asset)

        assertEquals(
            setOf(
                "font.body-font",
                "font.override-font",
                "image.profile-image",
                "preview.profile-preview",
                "preview.palette-preview",
                "image.destination-image",
                "preview.destination-preview",
            ),
            required.mapTo(linkedSetOf()) { it.value },
        )
        assertTrue(ThemeAssetArchiveConsistency.isValid(fixture.profiles, fixture.backgrounds, sections))
        required.forEach { missing ->
            assertFalse(
                "missing ${missing.value}",
                ThemeAssetArchiveConsistency.isValid(
                    fixture.profiles,
                    fixture.backgrounds,
                    sections.filterNot { it.name == missing.value },
                ),
            )
        }
    }

    @Test
    fun `same identity with the wrong kind does not satisfy a reference`() {
        val fixture = fixture()
        val required = ThemeAssetArchiveConsistency.requiredNames(fixture.profiles, fixture.backgrounds)
        val sections = required.map(::asset).filterNot { it.name == "font.body-font" } +
            asset(ThemeAssetSectionName(ThemeAssetBackupKind.IMAGE, StableKey.parse("body-font")))

        assertFalse(ThemeAssetArchiveConsistency.isValid(fixture.profiles, fixture.backgrounds, sections))
    }

    @Test
    fun `well formed extras are preserved but malformed identities are rejected`() {
        val fixture = fixture()
        val required = ThemeAssetArchiveConsistency.requiredNames(fixture.profiles, fixture.backgrounds)
        val sections = required.map(::asset) +
            asset(ThemeAssetSectionName(ThemeAssetBackupKind.PREVIEW, StableKey.parse("future-preview")))

        assertTrue(ThemeAssetArchiveConsistency.isValid(fixture.profiles, fixture.backgrounds, sections))
        assertFalse(
            ThemeAssetArchiveConsistency.isValid(
                fixture.profiles,
                fixture.backgrounds,
                sections + PortableBackupSection(PortableBackupSectionType.ASSET, "blob.unknown", byteArrayOf(1)),
            ),
        )
    }

    @Test
    fun `opaque preservation is derived from the archive being restored after interleaved previews`() {
        val archiveA = fixture()
        val archiveB = fixture(
            profileImage = "profile-image-b",
            profilePreview = "profile-preview-b",
        )
        val extraA = ThemeAssetSectionName(ThemeAssetBackupKind.PREVIEW, StableKey.parse("future-a"))
        val extraB = ThemeAssetSectionName(ThemeAssetBackupKind.PREVIEW, StableKey.parse("future-b"))
        val sectionsA = ThemeAssetArchiveConsistency
            .requiredNames(archiveA.profiles, archiveA.backgrounds)
            .map(::asset) + asset(extraA)
        val sectionsB = ThemeAssetArchiveConsistency
            .requiredNames(archiveB.profiles, archiveB.backgrounds)
            .map(::asset) + asset(extraB)

        val preservedAFirst = ThemeAssetArchiveConsistency.preservedNames(
            archiveA.snapshot(),
            sectionsA,
            RestoreSelection(),
        )
        val preservedB = ThemeAssetArchiveConsistency.preservedNames(
            archiveB.snapshot(),
            sectionsB,
            RestoreSelection(),
        )
        val preservedAAfterB = ThemeAssetArchiveConsistency.preservedNames(
            archiveA.snapshot(),
            sectionsA,
            RestoreSelection(),
        )

        assertEquals(setOf(extraA), preservedAFirst)
        assertEquals(setOf(extraB), preservedB)
        assertEquals(preservedAFirst, preservedAAfterB)
    }

    @Test
    fun `future and invalid theme records do not fabricate references`() {
        val invalidProfiles = listOf(
            ThemeProfileRecord(
                ThemeProfileId.parse("org.quicklauncher.theme/future"),
                "Future",
                "{\"asset\":\"invented-font\"}",
                schemaVersion = 2,
            ),
        )
        val invalidBackgrounds = listOf(
            DestinationBackgroundRecord(
                DestinationId.parse("org.quicklauncher.destination/future"),
                ThemeProfileId.parse("org.quicklauncher.theme/future"),
                "{\"asset\":\"invented-image\"}",
                schemaVersion = 2,
            ),
        )
        val extra = asset(ThemeAssetSectionName(ThemeAssetBackupKind.IMAGE, StableKey.parse("opaque-extra")))

        assertTrue(ThemeAssetArchiveConsistency.requiredNames(invalidProfiles, invalidBackgrounds).isEmpty())
        assertTrue(ThemeAssetArchiveConsistency.isValid(invalidProfiles, invalidBackgrounds, listOf(extra)))
    }

    private fun fixture(
        profileImage: String = "profile-image",
        profilePreview: String = "profile-preview",
    ): Fixture {
        val profileId = ThemeProfileId.parse("org.quicklauncher.theme/backup-test")
        val profile = ThemeProfile(
            id = profileId,
            name = "Backup test",
            mode = ThemeModePolicy.DARK,
            palette = PaletteDefinition.MaterialYou(ArgbColor.of(0xff336699L)),
            fonts = mapOf(
                ThemeFontRole.BODY to FontSelection(StableKey.parse("body-font")),
            ),
            overrides = listOf(
                ModuleThemeOverride(
                    ContributionId.parse("org.quicklauncher.block/test"),
                    CapabilityId.parse("org.quicklauncher.capability/theme-font"),
                    accent = null,
                    font = FontSelection(StableKey.parse("override-font")),
                ),
            ),
            background = imageBackground(profileImage, profilePreview),
            imageDerived = ImageDerivedTheme(
                ArgbColor.of(0xff336699L),
                StableKey.parse("palette-preview"),
            ),
        )
        val destination = DestinationBackgroundCodec.encode(
            DestinationId.parse("org.quicklauncher.destination/backup"),
            profileId,
            imageBackground("destination-image", "destination-preview"),
        )
        return Fixture(listOf(ThemeProfileCodec.encode(profile)), listOf(destination))
    }

    private fun imageBackground(asset: String, preview: String): BackgroundDefinition.Image =
        BackgroundDefinition.Image(
            StableKey.parse(asset),
            StableKey.parse(preview),
            ArgbColor.of(0xff112233L),
            ArgbColor.of(0xff000000L),
            0.2f,
        )

    private fun asset(name: ThemeAssetSectionName): PortableBackupSection =
        PortableBackupSection(PortableBackupSectionType.ASSET, name.value, byteArrayOf(1))

    private fun stagedPort(discard: suspend () -> Unit): RestoreAuxiliaryPort =
        object : RestoreAuxiliaryPort {
            override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = true

            override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage =
                object : AuxiliaryStage {
                    override suspend fun commit() = Unit
                    override suspend fun discard() = discard.invoke()
                }
        }

    private data class Fixture(
        val profiles: List<ThemeProfileRecord>,
        val backgrounds: List<DestinationBackgroundRecord>,
    ) {
        fun snapshot(): LauncherSnapshot = LauncherSnapshot.restored(
            startDestinationId = null,
            destinations = emptyList(),
            destinationLayouts = emptyList(),
            moduleInstances = emptyList(),
            configurationDocuments = emptyList(),
            placements = emptyList(),
            themeProfiles = profiles,
            destinationBackgrounds = backgrounds,
        )
    }
}
