package org.quicklauncher.host.runtime.catalog

import java.util.Base64
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.host.data.store.AppOverrideRecord
import org.quicklauncher.host.data.store.LauncherStore

/** Maps durable host overrides into catalog policy without exposing store snapshots to the platform. */
class StoreAppCatalogOverrideSource(
    private val store: LauncherStore,
) : AppCatalogOverrideSource {
    override suspend fun read(): List<AppCatalogOverride> = mapAppOverrides(store.readAppOverrides())
}

internal fun mapAppOverrides(records: Collection<AppOverrideRecord>): List<AppCatalogOverride> =
    records.mapNotNull { record ->
        val activityName = runCatching { ActivityName.parse(record.activityName) }.getOrNull()
            ?: return@mapNotNull null
        AppCatalogOverride(
            identity = AppActivityIdentity(record.profile, record.packageName, activityName),
            customLabel = record.customLabel,
            icon = record.encodedIcon?.let(::decodeIcon),
            favorite = record.favorite,
            collectionVisible = record.collectionVisible,
            searchVisible = record.searchVisible,
        )
    }

private fun decodeIcon(encoded: String): AppIcon? = runCatching {
    AppIcon.of(Base64.getDecoder().decode(encoded))
}.getOrNull()
