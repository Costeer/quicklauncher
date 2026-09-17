package org.quicklauncher.app

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle

/** Starts managed-profile provisioning from an app identity so Android can verify the caller. */
class ManagedWorkProvisioningLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(
                Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                        ComponentName(
                            this@ManagedWorkProvisioningLauncherActivity,
                            ManagedWorkFixtureAdminReceiver::class.java,
                        ),
                    )
                },
                PROVISIONING_REQUEST,
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROVISIONING_REQUEST) finish()
    }

    private companion object {
        const val PROVISIONING_REQUEST = 1
    }
}

/** Supplies the mandatory Android 12 and newer DPC provisioning callbacks for device tests. */
class ManagedWorkProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> setResult(
                RESULT_OK,
                Intent().putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE,
                ),
            )
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                initializeManagedWorkFixture(this)
                setResult(RESULT_OK)
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}

/** Handles the post-Android 8 provisioning completion activity path. */
class ManagedWorkProvisioningSuccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeManagedWorkFixture(this)
        finish()
    }
}

/** Lightweight synthetic launch target which deliberately does not open launcher storage. */
class ManagedWorkFixtureActivity : Activity()

/** Enables the disposable device-test profile and publishes one synthetic shortcut. */
class ManagedWorkFixtureAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        initializeManagedWorkFixture(context)
    }
}

/** Initializes current DPC provisioning and remains idempotent for the legacy callback path. */
private fun initializeManagedWorkFixture(context: Context) {
    val admin = ComponentName(context, ManagedWorkFixtureAdminReceiver::class.java)
    context.getSystemService(DevicePolicyManager::class.java).setProfileEnabled(admin)
    val target = ComponentName(context, ManagedWorkFixtureActivity::class.java)
    context.packageManager.setComponentEnabledSetting(
        target,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
    check(
        context.getSystemService(ShortcutManager::class.java).setDynamicShortcuts(
            listOf(
                ShortcutInfo.Builder(context, MANAGED_WORK_SHORTCUT_ID)
                    .setShortLabel(MANAGED_WORK_FIXTURE_LABEL)
                    .setActivity(target)
                    .setIntent(Intent(Intent.ACTION_VIEW).setComponent(target))
                    .build(),
            ),
        ),
    )
}

internal const val MANAGED_WORK_FIXTURE_LABEL = "Phase five managed work fixture"
internal const val MANAGED_WORK_SHORTCUT_ID = "phase-five-managed-work-fixture"
