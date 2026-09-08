package org.quicklauncher.contracts.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherIdentityTest {
    @Test
    fun `package name preserves a valid Android package name`() {
        val packageName = PackageName.parse("org.quicklauncher.preview")

        assertEquals("org.quicklauncher.preview", packageName.value)
        assertEquals("org.quicklauncher.preview", packageName.toString())
    }

    @Test
    fun `package name rejects values that cannot identify an Android package`() {
        listOf("", " ", "org quicklauncher", "org/quicklauncher", "org..quicklauncher").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                PackageName.parse(value)
            }
        }
    }

    @Test
    fun `profile serial accepts Android's primary profile serial`() {
        val profile = ProfileSerial.of(0)

        assertEquals(0L, profile.value)
        assertEquals("0", profile.toString())
    }

    @Test
    fun `profile serial rejects Android's invalid serial sentinel`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileSerial.of(-1)
        }
    }

    @Test
    fun `same package in two profiles has two identities`() {
        val packageName = PackageName.parse("org.example.calendar")

        val personal = ProfilePackageIdentity(ProfileSerial.of(0), packageName)
        val work = ProfilePackageIdentity(ProfileSerial.of(10), packageName)

        assertNotEquals(personal, work)
        assertEquals(personal, ProfilePackageIdentity(ProfileSerial.of(0), packageName))
    }

    @Test
    fun `activity name preserves a normalized Android activity class`() {
        val activityName = ActivityName.parse("org.example.calendar.MainActivity")

        assertEquals("org.example.calendar.MainActivity", activityName.value)
        assertEquals("org.example.calendar.MainActivity", activityName.toString())
    }

    @Test
    fun `activity name rejects relative and malformed class names`() {
        listOf(
            "",
            " ",
            ".MainActivity",
            "MainActivity",
            "org.example.Main Activity",
            "org/example/MainActivity",
            "org..example.MainActivity",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ActivityName.parse(value)
            }
        }
    }

    @Test
    fun `activity identity distinguishes activities within one profile and package`() {
        val profile = ProfileSerial.of(0)
        val packageName = PackageName.parse("org.example.calendar")
        val main = AppActivityIdentity(
            profile = profile,
            packageName = packageName,
            activityName = ActivityName.parse("org.example.calendar.MainActivity"),
        )
        val settings = AppActivityIdentity(
            profile = profile,
            packageName = packageName,
            activityName = ActivityName.parse("org.example.calendar.SettingsActivity"),
        )

        assertNotEquals(main, settings)
        assertEquals(
            main,
            AppActivityIdentity(
                profile = ProfileSerial.of(0),
                packageName = PackageName.parse("org.example.calendar"),
                activityName = ActivityName.parse("org.example.calendar.MainActivity"),
            ),
        )
    }
}
