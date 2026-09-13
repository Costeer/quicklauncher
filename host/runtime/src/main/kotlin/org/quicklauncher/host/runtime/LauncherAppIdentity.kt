package org.quicklauncher.host.runtime

import java.security.MessageDigest
import org.quicklauncher.contracts.domain.ContentItemId

/** Maps a profile-specific launcher activity to its stable host-owned content identity. */
fun contentItemId(app: LauncherApp): ContentItemId {
    val identity = app.identity
    val bytes = MessageDigest.getInstance("SHA-256").digest(
        "${identity.profile.value}:${identity.packageName.value}:${identity.activityName.value}"
            .toByteArray(Charsets.UTF_8),
    )
    val digest = bytes.take(10).joinToString("") { "%02x".format(it) }
    return ContentItemId.parse("org.quicklauncher.app/item-$digest")
}
