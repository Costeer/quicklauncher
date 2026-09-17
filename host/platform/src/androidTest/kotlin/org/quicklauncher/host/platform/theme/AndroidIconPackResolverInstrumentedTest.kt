package org.quicklauncher.host.platform.theme

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.theme.IconPackDialect
import org.quicklauncher.host.runtime.theme.IconPackSelection

@RunWith(AndroidJUnit4::class)
class AndroidIconPackResolverInstrumentedTest {
    @Test
    fun novaAndAdwFixturesMapBoundedlyFallbackAndInvalidateOnPackageChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val resolver = AndroidIconPackResolver(context)
        val fallback = AppIcon.of(byteArrayOf(1, 2, 3))
        val mappedIdentity = AppActivityIdentity(
            ProfileSerial.of(0),
            PackageName.parse("org.quicklauncher.synthetic"),
            ActivityName.parse("org.quicklauncher.synthetic.MainActivity"),
        )
        val missingIdentity = AppActivityIdentity(
            ProfileSerial.of(0),
            PackageName.parse("org.quicklauncher.missing"),
            ActivityName.parse("org.quicklauncher.missing.MainActivity"),
        )
        val available = resolver.discover()
        assertTrue(available.any { it.packageName == context.packageName && it.dialect == IconPackDialect.NOVA })
        assertTrue(available.any { it.packageName == context.packageName && it.dialect == IconPackDialect.ADW })

        listOf(IconPackDialect.NOVA, IconPackDialect.ADW).forEach { dialect ->
            val selection = IconPackSelection(context.packageName, dialect)
            val profile = ThemeProfileId.parse("org.quicklauncher.theme/instrumented")
            assertNotEquals(fallback, resolver.resolve(selection, profile, mappedIdentity, fallback))
            assertEquals(fallback, resolver.resolve(selection, profile, missingIdentity, fallback))
        }
        assertTrue(resolver.cachedEntryCount() > 0)
        resolver.invalidatePackage(context.packageName)
        assertEquals(0, resolver.cachedEntryCount())
        resolver.close()
    }
}
