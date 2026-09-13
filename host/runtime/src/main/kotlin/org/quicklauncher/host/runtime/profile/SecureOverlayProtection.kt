package org.quicklauncher.host.runtime.profile

/** Owns screenshot protection for one host overlay lifetime. */
fun interface SecureOverlayProtection {
    fun begin(): AutoCloseable
}
