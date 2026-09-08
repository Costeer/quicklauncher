package org.quicklauncher.host.runtime.catalog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.quicklauncher.contracts.domain.AppActivityIdentity

/** Fail-closed platform used when Android launcher services cannot be initialized. */
class UnavailableAppPlatform : AppPlatform {
    override val invalidations: Flow<AppPlatformInvalidation> = emptyFlow()

    override suspend fun snapshot(): AppPlatformSnapshot =
        throw IllegalStateException("app_platform_unavailable")

    override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult =
        AppLaunchResult.Failed("app_platform_unavailable")

    override fun close() = Unit
}
