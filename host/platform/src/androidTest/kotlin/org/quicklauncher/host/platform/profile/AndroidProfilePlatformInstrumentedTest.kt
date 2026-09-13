package org.quicklauncher.host.platform.profile

import android.os.Build
import android.os.Process
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.profile.PrivateItemsResult
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileInvalidation
import org.quicklauncher.host.runtime.profile.ProfileKind

@RunWith(AndroidJUnit4::class)
class AndroidProfilePlatformInstrumentedTest {
    @Test
    fun currentDeviceProfileHasAStablePersonalClassification() = runBlocking {
        assertTrue("Profile behavior requires API 35 or newer", Build.VERSION.SDK_INT >= 35)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serial = context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        require(serial >= 0L)
        val platform = AndroidProfilePlatform(context, Dispatchers.IO)
        try {
            val current = platform.profiles().single { it.serial == ProfileSerial.of(serial) }
            assertEquals(ProfileKind.PERSONAL, current.kind)
            assertEquals(ProfileAvailability.AVAILABLE, current.availability)
        } finally {
            platform.close()
        }
    }

    @Test
    fun privateKindAndLockAreCheckedBeforeAnyPrivateMetadataRead() = runBlocking {
        val personal = MetadataCountingBackend(
            PlatformProfileRecord(0, FULL_SYSTEM_USER_TYPE, quiet = false, unlocked = true),
        )
        val personalPlatform = AndroidProfilePlatform(personal, Dispatchers.IO)
        assertEquals(PrivateItemsResult.SecurityDenied, personalPlatform.privateItems(ProfileSerial.of(0)))
        assertEquals(0, personal.privateMetadataReads)
        personalPlatform.close()

        val lockedPrivate = MetadataCountingBackend(
            PlatformProfileRecord(
                10,
                UserManager.USER_TYPE_PROFILE_PRIVATE,
                quiet = true,
                unlocked = false,
            ),
        )
        val privatePlatform = AndroidProfilePlatform(lockedPrivate, Dispatchers.IO)
        assertEquals(PrivateItemsResult.Locked, privatePlatform.privateItems(ProfileSerial.of(10)))
        assertEquals(0, lockedPrivate.privateMetadataReads)
        privatePlatform.close()
    }

    private class MetadataCountingBackend(
        private val record: PlatformProfileRecord,
    ) : ProfileBackend {
        override val invalidations = MutableSharedFlow<ProfileInvalidation>()
        var privateMetadataReads = 0

        override fun profiles(): List<PlatformProfileRecord> = listOf(record)

        override fun profile(serial: Long): PlatformProfileRecord? = record.takeIf { it.serial == serial }

        override fun privateActivities(serial: Long): List<PlatformPrivateActivity> {
            privateMetadataReads += 1
            error("Private metadata must not be read")
        }

        override fun requestQuietMode(serial: Long, quiet: Boolean): Boolean = false

        override fun launchPrivate(identity: AppActivityIdentity): Boolean = false

        override fun close() = Unit
    }

    private companion object {
        const val FULL_SYSTEM_USER_TYPE = "android.os.usertype.full.SYSTEM"
    }
}
