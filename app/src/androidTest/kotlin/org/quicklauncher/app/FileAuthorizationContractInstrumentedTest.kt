package org.quicklauncher.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.annotation.XmlRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileAuthorizationContractInstrumentedTest {
    private val contract = FileAuthorizationContract()

    @Test
    fun authorizationPreservesReadFlagsAndRejectsInvalidResults() {
        val uri = Uri.parse("content://org.quicklauncher.synthetic/document")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val accepted = contract.parseResult(
            Activity.RESULT_OK,
            Intent().setData(uri).addFlags(flags),
        )

        assertEquals(uri, accepted?.uri)
        assertTrue(requireNotNull(accepted).flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(
            contract.parseResult(
                Activity.RESULT_OK,
                Intent().setData(Uri.parse("file:///synthetic")).addFlags(flags),
            ),
        )
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri).addFlags(flags)))
    }

    @Test
    fun authorizationRequestIsExplicitOpenDocumentWithPersistableReadAccess() {
        val intent = contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, Unit)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun androidBackupRulesIncludeOnlyDurableLauncherState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val durableState = setOf(
            BackupInclude("database", "quicklauncher.db", null),
            BackupInclude("file", "datastore/launcher-preferences.pb", null),
            BackupInclude("file", "theme-assets", null),
        )

        val extraction = backupIncludes(R.xml.data_extraction_rules)
        assertEquals(durableState, extraction.getValue("cloud-backup"))
        assertEquals(durableState, extraction.getValue("device-transfer"))
        assertEquals(setOf("cloud-backup", "device-transfer"), extraction.keys)

        val legacy = backupIncludes(R.xml.backup_rules)
        assertEquals(
            durableState.mapTo(linkedSetOf()) { include ->
                include.copy(requireFlags = "clientSideEncryption")
            },
            legacy.getValue("full-backup-content"),
        )
        assertEquals(setOf("full-backup-content"), legacy.keys)
        assertTrue(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    private fun backupIncludes(@XmlRes resource: Int): Map<String, Set<BackupInclude>> {
        val parser = InstrumentationRegistry.getInstrumentation().targetContext.resources.getXml(resource)
        return try {
            val result = linkedMapOf<String, MutableSet<BackupInclude>>()
            var scope: String? = null
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup", "device-transfer", "full-backup-content" -> {
                            scope = parser.name
                            result.getOrPut(parser.name) { linkedSetOf() }
                        }
                        "include" -> result.getValue(requireNotNull(scope)) += BackupInclude(
                            parser.getAttributeValue(null, "domain"),
                            parser.getAttributeValue(null, "path"),
                            parser.getAttributeValue(null, "requireFlags"),
                        )
                    }
                }
                parser.next()
            }
            result
        } finally {
            parser.close()
        }
    }

    private data class BackupInclude(
        val domain: String,
        val path: String,
        val requireFlags: String?,
    )
}
