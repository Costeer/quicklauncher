package org.quicklauncher.host.platform.profile

import android.app.Activity
import android.view.WindowManager
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection

/** Reference-counted FLAG_SECURE owner which preserves a flag installed by another owner. */
class AndroidSecureOverlayProtection(activity: Activity) : SecureOverlayProtection {
    private val window = activity.window
    private var sessions = 0
    private var secureBeforeFirstSession = false

    @Synchronized
    override fun begin(): AutoCloseable {
        if (sessions == 0) {
            secureBeforeFirstSession =
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        sessions += 1
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (closed) return@synchronized
                closed = true
                sessions -= 1
                if (sessions == 0 && !secureBeforeFirstSession) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}
