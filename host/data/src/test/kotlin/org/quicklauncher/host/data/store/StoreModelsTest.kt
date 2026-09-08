package org.quicklauncher.host.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId

class StoreModelsTest {
    @Test
    fun `shortcut content has one structural profile package and shortcut identity`() {
        val target = ShortcutTarget(
            profile = ProfileSerial.of(10),
            packageName = PackageName.parse("org.example.calendar"),
            shortcutId = ShortcutId.parse("open:next meeting"),
        )

        val item = ContentItemRecord.shortcut(
            id = ContentItemId.parse("org.quicklauncher.content/next-meeting"),
            encoded = "calendar shortcut",
            target = target,
        )

        assertEquals(ContentItemKind.SHORTCUT, item.kind)
        assertEquals(target, item.shortcutTarget)
        assertEquals(target.profile, item.profile)
        assertEquals(target.packageName, item.packageName)
    }

    @Test
    fun `shortcut content cannot omit its shortcut identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentItemRecord(
                id = ContentItemId.parse("org.quicklauncher.content/incomplete-shortcut"),
                kind = ContentItemKind.SHORTCUT,
                encoded = "incomplete",
                profile = ProfileSerial.of(10),
                packageName = PackageName.parse("org.example.calendar"),
            )
        }
    }

    @Test
    fun `non-shortcut content preserves optional package identity without a shortcut target`() {
        val item = ContentItemRecord(
            id = ContentItemId.parse("org.quicklauncher.content/calendar-favorite"),
            kind = ContentItemKind.FAVORITE,
            encoded = "favorite",
            profile = ProfileSerial.of(10),
            packageName = PackageName.parse("org.example.calendar"),
        )

        assertEquals(ProfileSerial.of(10), item.profile)
        assertEquals(PackageName.parse("org.example.calendar"), item.packageName)
        assertEquals(null, item.shortcutTarget)
    }
}
