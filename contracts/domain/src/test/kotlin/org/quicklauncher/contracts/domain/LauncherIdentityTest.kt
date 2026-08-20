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
}
