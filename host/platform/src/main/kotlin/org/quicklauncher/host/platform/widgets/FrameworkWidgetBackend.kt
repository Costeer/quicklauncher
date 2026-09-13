package org.quicklauncher.host.platform.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.util.SizeF
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.widgets.WidgetProviderIdentity
import org.quicklauncher.host.runtime.widgets.WidgetSize

internal class FrameworkWidgetBackend(
    context: Context,
) : WidgetFrameworkBackend {
    private val applicationContext = context.applicationContext
    private val host = AppWidgetHost(applicationContext, QUICKLAUNCHER_WIDGET_HOST_ID)
    private val manager = AppWidgetManager.getInstance(applicationContext)
    private val userManager = applicationContext.getSystemService(UserManager::class.java)
    private val launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
    private val surfaceIds = AtomicLong(1L)
    private val surfaces = linkedMapOf<Long, AppWidgetHostView>()
    private val closed = AtomicBoolean(false)

    init {
        host.startListening()
    }

    override fun allocate(): Int {
        checkOpen()
        return host.allocateAppWidgetId()
    }

    override fun hasProfile(profile: ProfileSerial): Boolean =
        availableUser(profile) != null

    override fun providers(profile: ProfileSerial): List<WidgetBackendProvider> {
        val user = availableUser(profile) ?: return emptyList()
        return manager.getInstalledProvidersForProfile(user)
            .asSequence()
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .mapNotNull { info ->
                val provider = info.provider ?: return@mapNotNull null
                val label = info.loadLabel(applicationContext.packageManager)?.toString()?.trim()
                    .orEmpty()
                WidgetBackendProvider(
                    identity = WidgetProviderIdentity(
                        profile = profile,
                        packageName = org.quicklauncher.contracts.domain.PackageName.parse(provider.packageName),
                        className = provider.className,
                    ),
                    label = label.ifBlank { provider.packageName },
                    minimumSize = WidgetSize(
                        info.minWidth.coerceAtLeast(1),
                        info.minHeight.coerceAtLeast(1),
                    ),
                    configurationRequired = info.requiresConfiguration(),
                )
            }
            .toList()
    }

    override fun hasProvider(provider: WidgetProviderIdentity): Boolean {
        val user = availableUser(provider.profile) ?: return false
        val component = provider.componentName()
        return manager.getInstalledProvidersForProfile(user).any {
            it.provider == component &&
                it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
        }
    }

    override fun bind(binding: WidgetBackendBinding): Boolean {
        checkOpen()
        val user = availableUser(binding.provider.profile) ?: return false
        return manager.bindAppWidgetIdIfAllowed(
            binding.appWidgetId,
            user,
            binding.provider.componentName(),
            binding.size.options(),
        )
    }

    override fun bindingState(
        appWidgetId: Int,
        expectedProvider: WidgetProviderIdentity,
    ): WidgetBackendBindingState {
        checkOpen()
        if (availableUser(expectedProvider.profile) == null) {
            return WidgetBackendBindingState.PROFILE_UNAVAILABLE
        }
        val info = manager.getAppWidgetInfo(appWidgetId)
            ?: return if (hasProvider(expectedProvider)) {
                WidgetBackendBindingState.NOT_BOUND
            } else {
                WidgetBackendBindingState.PROVIDER_UNAVAILABLE
            }
        return if (info.provider == expectedProvider.componentName()) {
            WidgetBackendBindingState.VALID
        } else {
            WidgetBackendBindingState.PROVIDER_UNAVAILABLE
        }
    }

    override fun configurationRequired(appWidgetId: Int): Boolean {
        val info = requireNotNull(manager.getAppWidgetInfo(appWidgetId)) {
            "Widget provider is unavailable"
        }
        return info.requiresConfiguration()
    }

    override fun createSurface(appWidgetId: Int, size: WidgetSize): Long {
        checkOpen()
        val info = requireNotNull(manager.getAppWidgetInfo(appWidgetId)) {
            "Widget provider is unavailable"
        }
        manager.updateAppWidgetOptions(appWidgetId, size.options())
        val view = host.createView(applicationContext, appWidgetId, info)
        val id = surfaceIds.getAndIncrement()
        surfaces[id] = view
        return id
    }

    override fun surfaceView(surfaceId: Long): AppWidgetHostView? = surfaces[surfaceId]

    override fun releaseSurface(surfaceId: Long) {
        val view = surfaces.remove(surfaceId) ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        view.cancelPendingInputEvents()
        view.removeAllViews()
    }

    override fun updateSize(update: WidgetBackendSizeUpdate) {
        checkOpen()
        manager.updateAppWidgetOptions(update.appWidgetId, update.size.options())
    }

    override fun delete(appWidgetId: Int): Boolean {
        checkOpen()
        surfaces.filterValues { it.appWidgetId == appWidgetId }
            .keys
            .toList()
            .forEach(::releaseSurface)
        if (appWidgetId !in host.appWidgetIds) return false
        host.deleteAppWidgetId(appWidgetId)
        return true
    }

    override fun allocatedIds(): Set<Int> {
        checkOpen()
        return host.appWidgetIds.toSet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val retained = surfaces.keys.toList()
        retained.forEach(::releaseSurface)
        host.stopListening()
    }

    private fun checkOpen() = check(!closed.get()) { "Widget host is closed" }

    private fun WidgetProviderIdentity.componentName(): ComponentName =
        ComponentName(packageName.value, className)

    private fun availableUser(profile: ProfileSerial): android.os.UserHandle? {
        val user = userManager.getUserForSerialNumber(profile.value) ?: return null
        val type = launcherApps.getLauncherUserInfo(user)?.userType
            ?: if (user == Process.myUserHandle()) PERSONAL_USER_TYPE else return null
        if (type == UserManager.USER_TYPE_PROFILE_PRIVATE) return null
        if (!userManager.isUserUnlocked(user)) return null
        if (type.startsWith(PROFILE_TYPE_PREFIX) && userManager.isQuietModeEnabled(user)) return null
        return user
    }

    private fun AppWidgetProviderInfo.requiresConfiguration(): Boolean {
        val optional = widgetFeatures and
            AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        return configure != null && !optional
    }

    private fun WidgetSize.options(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        )
        putParcelableArrayList(
            AppWidgetManager.OPTION_APPWIDGET_SIZES,
            arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
        )
    }

}

// Stable per package. Changing this would abandon every existing framework binding.
internal const val QUICKLAUNCHER_WIDGET_HOST_ID = 0x514C
private const val PERSONAL_USER_TYPE = "android.os.usertype.full.SYSTEM"
private const val PROFILE_TYPE_PREFIX = "android.os.usertype.profile."
