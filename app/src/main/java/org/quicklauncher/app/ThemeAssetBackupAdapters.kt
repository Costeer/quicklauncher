package org.quicklauncher.app

import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.backup.archive.PortableBackupSection
import org.quicklauncher.host.backup.archive.PortableBackupSectionType
import org.quicklauncher.host.backup.library.AuxiliaryStage
import org.quicklauncher.host.backup.library.BackupSectionSource
import org.quicklauncher.host.backup.library.RestoreAuxiliaryPort
import org.quicklauncher.host.backup.library.RestoreSelection
import org.quicklauncher.host.data.store.DestinationBackgroundRecord
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.ThemeProfileRecord
import org.quicklauncher.host.platform.theme.AndroidThemeAssetStore
import org.quicklauncher.host.platform.theme.ThemeAssetBackupEntry
import org.quicklauncher.host.platform.theme.ThemeAssetBackupIdentity
import org.quicklauncher.host.platform.theme.ThemeAssetBackupKind
import org.quicklauncher.host.runtime.theme.BackgroundDefinition
import org.quicklauncher.host.runtime.theme.DestinationBackgroundCodec
import org.quicklauncher.host.runtime.theme.DestinationBackgroundDecodeResult
import org.quicklauncher.host.runtime.theme.ThemeProfileCodec
import org.quicklauncher.host.runtime.theme.ThemeProfileDecodeResult

internal class ThemeAssetBackupSectionSource(
    private val assets: AndroidThemeAssetStore,
) : BackupSectionSource {
    override suspend fun export(snapshot: LauncherSnapshot): List<PortableBackupSection> {
        val sections = assets.exportBackupAssets().map { entry ->
            PortableBackupSection(
                PortableBackupSectionType.ASSET,
                ThemeAssetSectionName(entry.kind, entry.id).value,
                entry.bytesCopy(),
            )
        }
        check(ThemeAssetArchiveConsistency.isValid(snapshot, sections)) {
            "Backup assets do not satisfy current theme references"
        }
        return sections
    }
}

internal class ThemeAssetRestorePort(
    private val assets: AndroidThemeAssetStore,
) : RestoreAuxiliaryPort {
    override fun validate(snapshot: LauncherSnapshot, sections: List<PortableBackupSection>): Boolean =
        ThemeAssetArchiveConsistency.isValid(snapshot, sections)

    override suspend fun stage(sections: List<PortableBackupSection>): AuxiliaryStage =
        stage(sections, RestoreSelection())

    override suspend fun stage(
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
    ): AuxiliaryStage = stageAssets(
        sections,
        selection,
        ThemeAssetArchiveConsistency.availableNames(sections),
    )

    override suspend fun stage(
        snapshot: LauncherSnapshot,
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
        launcherExtensions: Map<String, ByteArray>,
    ): AuxiliaryStage = stageAssets(
        sections,
        selection,
        ThemeAssetArchiveConsistency.preservedNames(snapshot, sections, selection),
    )

    private suspend fun stageAssets(
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
        preservedNames: Set<ThemeAssetSectionName>,
    ): AuxiliaryStage {
        if (!selection.assets) return object : AuxiliaryStage {
            override suspend fun commit() = Unit
            override suspend fun discard() = Unit
        }
        val entries = sections.mapNotNull { section ->
            if (section.type != PortableBackupSectionType.ASSET || section.name == "manifest") {
                return@mapNotNull null
            }
            val name = ThemeAssetSectionName.parse(section.name)
                ?: error("Unsupported backup asset identity")
            ThemeAssetBackupEntry(name.id, name.kind, section.contentCopy())
        }
        val staged = assets.stageBackupAssets(
            entries,
            preservedNames.mapTo(linkedSetOf()) { ThemeAssetBackupIdentity(it.id, it.kind) },
        ) ?: error("Backup assets failed validation")
        return object : AuxiliaryStage {
            override suspend fun commit() {
                check(staged.commit()) { "Backup assets could not be installed" }
            }

            override suspend fun discard() = staged.discard()
        }
    }
}

internal object ThemeAssetArchiveConsistency {
    fun isValid(snapshot: LauncherSnapshot, sections: List<PortableBackupSection>): Boolean =
        isValid(snapshot.themeProfiles, snapshot.destinationBackgrounds, sections)

    fun isValid(
        profiles: Collection<ThemeProfileRecord>,
        backgrounds: Collection<DestinationBackgroundRecord>,
        sections: List<PortableBackupSection>,
    ): Boolean {
        val assetNames = ArrayList<ThemeAssetSectionName>()
        sections.forEach { section ->
            if (section.type != PortableBackupSectionType.ASSET || section.name == ASSET_MANIFEST_NAME) {
                return@forEach
            }
            assetNames += ThemeAssetSectionName.parse(section.name) ?: return false
        }
        if (assetNames.toSet().size != assetNames.size) return false
        val available = assetNames.toSet()
        return requiredNames(profiles, backgrounds).all { it in available }
    }

    internal fun requiredNames(
        profiles: Collection<ThemeProfileRecord>,
        backgrounds: Collection<DestinationBackgroundRecord>,
    ): Set<ThemeAssetSectionName> = buildSet {
        profiles.forEach { record ->
            val profile = (ThemeProfileCodec.decode(record) as? ThemeProfileDecodeResult.Valid)?.profile
                ?: return@forEach
            profile.fonts.values.forEach { add(ThemeAssetSectionName(ThemeAssetBackupKind.FONT, it.assetId)) }
            profile.overrides.mapNotNull { it.font }.forEach {
                add(ThemeAssetSectionName(ThemeAssetBackupKind.FONT, it.assetId))
            }
            addBackground(profile.background)
            profile.imageDerived?.let {
                add(ThemeAssetSectionName(ThemeAssetBackupKind.PREVIEW, it.previewId))
            }
        }
        backgrounds.forEach { record ->
            val background = (DestinationBackgroundCodec.decode(record) as?
                DestinationBackgroundDecodeResult.Valid)?.background ?: return@forEach
            addBackground(background)
        }
    }

    internal fun availableNames(sections: List<PortableBackupSection>): Set<ThemeAssetSectionName> =
        sections.mapNotNullTo(linkedSetOf()) { section ->
            if (section.type == PortableBackupSectionType.ASSET && section.name != ASSET_MANIFEST_NAME) {
                ThemeAssetSectionName.parse(section.name)
            } else {
                null
            }
        }

    internal fun preservedNames(
        snapshot: LauncherSnapshot,
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
    ): Set<ThemeAssetSectionName> {
        val available = availableNames(sections)
        if (!selection.themes) return available
        return available - requiredNames(snapshot.themeProfiles, snapshot.destinationBackgrounds)
    }

    private fun MutableSet<ThemeAssetSectionName>.addBackground(background: BackgroundDefinition?) {
        if (background !is BackgroundDefinition.Image) return
        add(ThemeAssetSectionName(ThemeAssetBackupKind.IMAGE, background.assetId))
        add(ThemeAssetSectionName(ThemeAssetBackupKind.PREVIEW, background.previewId))
    }

    private const val ASSET_MANIFEST_NAME = "manifest"
}

internal data class ThemeAssetSectionName(
    val kind: ThemeAssetBackupKind,
    val id: StableKey,
) {
    val value: String = "${kind.wirePrefix}.${id.value}"

    companion object {
        fun parse(value: String): ThemeAssetSectionName? = runCatching {
            val prefix = value.substringBefore('.', missingDelimiterValue = "")
            val identity = value.substringAfter('.', missingDelimiterValue = "")
            val kind = ThemeAssetBackupKind.entries.singleOrNull { it.wirePrefix == prefix }
                ?: return null
            ThemeAssetSectionName(kind, StableKey.parse(identity))
        }.getOrNull()
    }
}

private val ThemeAssetBackupKind.wirePrefix: String
    get() = when (this) {
        ThemeAssetBackupKind.FONT -> "font"
        ThemeAssetBackupKind.IMAGE -> "image"
        ThemeAssetBackupKind.PREVIEW -> "preview"
    }
