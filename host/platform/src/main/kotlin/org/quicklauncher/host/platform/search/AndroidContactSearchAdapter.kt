package org.quicklauncher.host.platform.search

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.search.SearchExecutionResult
import org.quicklauncher.host.runtime.search.SearchExecutionTarget

class ContactSearchRecord(
    val profile: ProfileSerial,
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val work: Boolean,
) {
    override fun toString(): String = "ContactSearchRecord(redacted)"
}

sealed interface ContactSearchResult {
    class Available(records: Collection<ContactSearchRecord>) : ContactSearchResult {
        val records: List<ContactSearchRecord> = java.util.Collections.unmodifiableList(ArrayList(records))
    }
    data object Denied : ContactSearchResult
    data object Unavailable : ContactSearchResult
}

interface ContactSearchSource {
    suspend fun query(
        query: SearchQuery,
        personalProfile: ProfileSerial,
        eligibleWorkProfile: ProfileSerial?,
    ): ContactSearchResult
}

class AndroidContactSearchAdapter(
    context: Context,
    private val profiles: ProfileCoordinator,
) : ContactTargetLauncher, ContactSearchSource {
    private val context = context.applicationContext

    override suspend fun query(
        query: SearchQuery,
        personalProfile: ProfileSerial,
        eligibleWorkProfile: ProfileSerial?,
    ): ContactSearchResult = withContext(Dispatchers.IO) {
        if (!isEligiblePersonal(personalProfile)) return@withContext ContactSearchResult.Unavailable
        if (!hasPermission()) return@withContext ContactSearchResult.Denied
        try {
            val personal = queryUri(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                query,
                personalProfile,
                work = false,
            )
            val work = if (eligibleWorkProfile != null && isEligibleWork(eligibleWorkProfile)) {
                queryUri(
                    ContactsContract.Contacts.ENTERPRISE_CONTENT_FILTER_URI,
                    query,
                    eligibleWorkProfile,
                    work = true,
                )
            } else {
                emptyList()
            }
            ContactSearchResult.Available((personal + work).take(MAX_RESULTS))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ContactSearchResult.Denied
        } catch (_: RuntimeException) {
            ContactSearchResult.Unavailable
        }
    }

    override suspend fun launch(target: SearchExecutionTarget.Contact): SearchExecutionResult {
        if (!hasPermission()) return SearchExecutionResult.MissingAccess
        if (!isEligibleProfile(target.profile)) return SearchExecutionResult.MissingAccess
        val lookup = ContactsContract.Contacts.getLookupUri(target.contactId, target.lookupKey)
            ?: return SearchExecutionResult.Unavailable
        val current = try {
            withContext(Dispatchers.IO) {
                ContactsContract.Contacts.lookupContact(context.contentResolver, lookup)
            }
        } catch (_: SecurityException) {
            return SearchExecutionResult.MissingAccess
        } ?: return SearchExecutionResult.Unavailable
        if (!hasPermission() || !isEligibleProfile(target.profile)) {
            return SearchExecutionResult.MissingAccess
        }
        return withContext(Dispatchers.Main.immediate) {
            val intent = quickContactIntent(current)
            if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                return@withContext SearchExecutionResult.Unavailable
            }
            try {
                context.startActivity(intent)
                SearchExecutionResult.Succeeded
            } catch (_: ActivityNotFoundException) {
                SearchExecutionResult.Unavailable
            } catch (_: SecurityException) {
                SearchExecutionResult.MissingAccess
            }
        }
    }

    internal fun quickContactIntent(uri: Uri): Intent =
        Intent(ContactsContract.QuickContact.ACTION_QUICK_CONTACT)
            .setData(uri)
            .apply {
                sourceBounds = Rect()
                putExtra(
                    ContactsContract.QuickContact.EXTRA_MODE,
                    ContactsContract.QuickContact.MODE_MEDIUM,
                )
                putExtra(ContactsContract.QuickContact.EXTRA_EXCLUDE_MIMES, emptyArray<String>())
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

    private suspend fun queryUri(
        base: Uri,
        query: SearchQuery,
        profile: ProfileSerial,
        work: Boolean,
    ): List<ContactSearchRecord> {
        currentCoroutineContext().ensureActive()
        val signal = android.os.CancellationSignal()
        val job = currentCoroutineContext().job
        val registration = job.invokeOnCompletion { signal.cancel() }
        return try {
            val uri = base.buildUpon()
                .appendPath(query.value)
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, MAX_RESULTS.toString())
                .build()
            val args = Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, MAX_RESULTS)
            }
            context.contentResolver.query(uri, PROJECTION, args, signal)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val keyIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                buildList {
                    while (cursor.moveToNext() && size < MAX_RESULTS) {
                        job.ensureActive()
                        val id = cursor.getLong(idIndex)
                        val key = cursor.getString(keyIndex) ?: continue
                        val name = cursor.getString(nameIndex)?.takeIf(String::isNotBlank) ?: continue
                        add(ContactSearchRecord(profile, id, key, name.take(MAX_TEXT_LENGTH), work))
                    }
                }
            }.orEmpty()
        } finally {
            registration.dispose()
        }
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun isEligibleProfile(profile: ProfileSerial): Boolean {
        val value = profiles.state.value.profiles.singleOrNull { it.serial == profile } ?: return false
        return value.kind != ProfileKind.PRIVATE && value.availability == ProfileAvailability.AVAILABLE
    }

    private fun isEligibleWork(profile: ProfileSerial): Boolean {
        val value = profiles.state.value.profiles.singleOrNull { it.serial == profile } ?: return false
        return value.kind == ProfileKind.WORK && value.availability == ProfileAvailability.AVAILABLE
    }

    private fun isEligiblePersonal(profile: ProfileSerial): Boolean {
        val value = profiles.state.value.profiles.singleOrNull { it.serial == profile } ?: return false
        return value.kind == ProfileKind.PERSONAL && value.availability == ProfileAvailability.AVAILABLE
    }

    private companion object {
        val PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        const val MAX_RESULTS = 25
        const val MAX_TEXT_LENGTH = 500
    }
}
