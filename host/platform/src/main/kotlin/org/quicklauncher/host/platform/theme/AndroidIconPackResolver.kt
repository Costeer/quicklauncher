package org.quicklauncher.host.platform.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.theme.IconPackDialect
import org.quicklauncher.host.runtime.theme.IconPackSelection
import org.quicklauncher.contracts.domain.ThemeProfileId

data class AvailableIconPack(
    val packageName: String,
    val dialect: IconPackDialect,
) {
    override fun toString(): String = "AvailableIconPack(dialect=$dialect, package=redacted)"
}

class AndroidIconPackResolver(
    context: Context,
    private val timeoutMillis: Long = 2_000L,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val cache = LinkedHashMap<CacheKey, CachedIcon>(MAX_CACHE_ENTRIES, 0.75f, true)
    private var cachedBytes = 0
    private var configurationKey = configurationKey()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart ?: return
            invalidatePackage(packageName)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    suspend fun discover(): List<AvailableIconPack> = try {
        withTimeout(timeoutMillis) {
            runInterruptible(Dispatchers.IO) {
                val matches = listOf(
                    IconPackDialect.NOVA to Intent("com.novalauncher.THEME"),
                    IconPackDialect.ADW to Intent("org.adw.launcher.THEMES"),
                ).flatMap { (dialect, intent) ->
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                        .asSequence()
                        .take(MAX_PACKAGES)
                        .map { AvailableIconPack(it.activityInfo.packageName, dialect) }
                        .toList()
                }
                matches.distinctBy { it.packageName to it.dialect }
                    .sortedWith(compareBy({ it.packageName }, { it.dialect.name }))
                    .take(MAX_PACKAGES)
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        emptyList()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    suspend fun resolve(
        selection: IconPackSelection?,
        themeProfileId: ThemeProfileId,
        identity: AppActivityIdentity,
        fallback: AppIcon,
    ): AppIcon {
        val selected = selection ?: return fallback
        return try {
            withTimeout(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    invalidateForConfigurationChange()
                    val dialectIntent = Intent(selected.dialect.action).setPackage(selected.packageName)
                    val declared = packageManager.queryIntentActivities(
                        dialectIntent,
                        PackageManager.MATCH_DISABLED_COMPONENTS,
                    ).asSequence().take(MAX_PACKAGES).any {
                        it.activityInfo.packageName == selected.packageName && it.activityInfo.enabled
                    }
                    if (!declared) return@runInterruptible fallback
                    val application = packageManager.getApplicationInfo(
                        selected.packageName,
                        PackageManager.ApplicationInfoFlags.of(
                            PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                        ),
                    )
                    if (!application.enabled) return@runInterruptible fallback
                    val packageVersion = packageManager.getPackageInfo(
                        selected.packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    ).longVersionCode
                    val key = CacheKey(
                        selected.packageName,
                        selected.dialect,
                        packageVersion,
                        themeProfileId.value,
                        identity.profile.value,
                        identity.packageName.value,
                        identity.activityName.value,
                        configurationKey,
                    )
                    cachedIcon(key)?.let { return@runInterruptible it }
                    val resolved = resolveUncached(selected, identity, application) ?: return@runInterruptible fallback
                    cacheIcon(key, resolved)
                    resolved
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            fallback
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            fallback
        } catch (_: RuntimeException) {
            fallback
        }
    }

    fun invalidateProfileAndConfiguration() {
        clearCache()
        configurationKey = configurationKey()
    }

    internal fun invalidatePackage(packageName: String) {
        synchronized(cache) {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.packPackage == packageName) {
                    cachedBytes -= entry.value.byteCount
                    iterator.remove()
                }
            }
        }
    }

    internal fun cachedEntryCount(): Int = synchronized(cache) { cache.size }

    override fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
        clearCache()
    }

    private fun resolveUncached(
        selection: IconPackSelection,
        identity: AppActivityIdentity,
        application: android.content.pm.ApplicationInfo,
    ): AppIcon? {
        val resources = packageManager.getResourcesForApplication(application)
        val mappings = mappings(resources, selection) ?: return null
        val drawableName = mappings[
            IconComponentKey(identity.packageName.value, identity.activityName.value),
        ] ?: return null
        val resourceId = resources.getIdentifier(drawableName, "drawable", selection.packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(drawableName, "mipmap", selection.packageName).takeIf { it != 0 }
            ?: return null
        val drawable = resources.getDrawable(resourceId, null)
        return rasterize(drawable)
    }

    private fun mappings(
        resources: android.content.res.Resources,
        selection: IconPackSelection,
    ): Map<IconComponentKey, String>? {
        val names = when (selection.dialect) {
            IconPackDialect.NOVA -> listOf("appfilter")
            IconPackDialect.ADW -> listOf("drawable", "appfilter")
        }
        names.forEach { name ->
            val resourceId = resources.getIdentifier(name, "xml", selection.packageName)
            if (resourceId != 0) {
                val parser = resources.getXml(resourceId)
                val value = try {
                    DeclarativeIconPackParser.parse(parser)
                } finally {
                    parser.close()
                }
                if (value != null) return value
            }
        }
        names.forEach { name ->
            val value = runCatching {
                resources.assets.open("$name.xml").use(::readBoundedUtf8)
                    ?.let(DeclarativeIconPackParser::parse)
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun readBoundedUtf8(input: InputStream): String? {
        val bytes = input.readNBytes(DeclarativeIconPackParser.MAX_MAPPING_CHARS + 1)
        if (bytes.size > DeclarativeIconPackParser.MAX_MAPPING_CHARS) return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun rasterize(drawable: Drawable): AppIcon? {
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_DIMENSION)
            ?: DEFAULT_ICON_DIMENSION
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_DIMENSION)
            ?: DEFAULT_ICON_DIMENSION
        if (width.toLong() * height * 4L > MAX_ICON_MEMORY_BYTES) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            val bytes = output.toByteArray()
            if (bytes.size !in 1..MAX_ICON_BYTES) null else AppIcon.of(bytes)
        } finally {
            bitmap.recycle()
        }
    }

    private fun invalidateForConfigurationChange() {
        val current = configurationKey()
        if (current != configurationKey) {
            clearCache()
            configurationKey = current
        }
    }

    private fun cachedIcon(key: CacheKey): AppIcon? = synchronized(cache) { cache[key]?.icon }

    private fun cacheIcon(key: CacheKey, icon: AppIcon) {
        val byteCount = icon.bytes().size
        synchronized(cache) {
            cache.put(key, CachedIcon(icon, byteCount))?.let { cachedBytes -= it.byteCount }
            cachedBytes += byteCount
            val iterator = cache.entries.iterator()
            while ((cache.size > MAX_CACHE_ENTRIES || cachedBytes > MAX_CACHE_BYTES) && iterator.hasNext()) {
                val removed = iterator.next().value
                cachedBytes -= removed.byteCount
                iterator.remove()
            }
        }
    }

    private fun clearCache() {
        synchronized(cache) {
            cache.clear()
            cachedBytes = 0
        }
    }

    private fun configurationKey(): Int {
        val configuration = appContext.resources.configuration
        return 31 * appContext.resources.displayMetrics.densityDpi + configuration.uiMode
    }

    private data class CacheKey(
        val packPackage: String,
        val dialect: IconPackDialect,
        val packVersion: Long,
        val themeProfile: String,
        val profile: Long,
        val appPackage: String,
        val activity: String,
        val configuration: Int,
    )

    private data class CachedIcon(val icon: AppIcon, val byteCount: Int)

    private companion object {
        const val MAX_PACKAGES = 64
        const val MAX_CACHE_ENTRIES = 256
        const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        const val MAX_ICON_DIMENSION = 512
        const val DEFAULT_ICON_DIMENSION = 192
        const val MAX_ICON_MEMORY_BYTES = 4L * 1024 * 1024
        const val MAX_ICON_BYTES = 1024 * 1024
    }
}

private val IconPackDialect.action: String
    get() = when (this) {
        IconPackDialect.NOVA -> "com.novalauncher.THEME"
        IconPackDialect.ADW -> "org.adw.launcher.THEMES"
    }
