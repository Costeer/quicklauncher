#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

if __package__:
    from . import release_security_audit as audit
else:
    import release_security_audit as audit


def manifest(channel: str) -> str:
    package_name = "org.quicklauncher" if channel == "stable" else "org.quicklauncher.preview"
    version_name = "0.1.1" if channel == "stable" else "0.1.1-preview"
    dynamic_permission = f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    permission_xml = "\n".join(
        f'<uses-permission android:name="{permission}" />'
        for permission in sorted(audit.PLATFORM_PERMISSIONS | {dynamic_permission})
    )
    component_xml = "\n".join(
        component(tag, name, package_name)
        for tag, name in audit.EXPECTED_COMPONENTS
        if name != "org.quicklauncher.app.MainActivity"
    )
    return f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="{audit.ANDROID_NS}" package="{package_name}"
    android:versionCode="2" android:versionName="{version_name}">
  <uses-sdk android:minSdkVersion="35" android:targetSdkVersion="35" />
  {permission_xml}
  <permission android:name="{dynamic_permission}" android:protectionLevel="signature" />
  <queries><intent><action android:name="android.intent.action.DELETE" />
    <data android:scheme="package" /></intent></queries>
  <application android:allowBackup="true" android:fullBackupContent="@xml/backup_rules"
      android:dataExtractionRules="@xml/data_extraction_rules">
    <activity android:name="org.quicklauncher.app.MainActivity" android:exported="true"
        android:launchMode="singleTask">
      <intent-filter><action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
      <intent-filter><action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" /></intent-filter>
    </activity>
    {component_xml}
  </application>
</manifest>
"""


def component(tag: str, name: str, package_name: str) -> str:
    attributes = audit._expected_component_attributes(package_name)[(tag, name)]
    attribute_xml = " ".join(
        f'android:{key.removeprefix(audit.A)}="{value}"'
        for key, value in attributes.items()
    )
    filters = ""
    if name.endswith("QuicklauncherNotificationListenerService"):
        filters = (
            '<intent-filter><action android:name="android.service.notification.'
            'NotificationListenerService" /></intent-filter>'
        )
    elif name == "androidx.profileinstaller.ProfileInstallReceiver":
        filters = "".join(
            f'<intent-filter><action android:name="{action}" /></intent-filter>'
            for action in (
                "androidx.profileinstaller.action.INSTALL_PROFILE",
                "androidx.profileinstaller.action.SKIP_FILE",
                "androidx.profileinstaller.action.SAVE_PROFILE",
                "androidx.profileinstaller.action.BENCHMARK_OPERATION",
            )
        )
    metadata = ""
    if name == "androidx.startup.InitializationProvider":
        metadata = "".join(
            f'<meta-data android:name="{initializer}" android:value="androidx.startup" />'
            for initializer in (
                "androidx.emoji2.text.EmojiCompatInitializer",
                "androidx.lifecycle.ProcessLifecycleInitializer",
                "androidx.profileinstaller.ProfileInstallerInitializer",
            )
        )
    return f'<{tag} {attribute_xml}>{filters}{metadata}</{tag}>'


class ReleaseSecurityAuditTest(unittest.TestCase):
    def write(self, root: Path, relative: str, content: str) -> Path:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_production_inventory(self, root: Path) -> None:
        anchors = {
            "app/src/main/java/org/quicklauncher/app/MainActivity.kt": (
                "File(noBackupFilesDir, PrivateBackupDiagnosticLog.FILE_NAME); "
                "ACTION_OPEN_DOCUMENT; takePersistableUriPermission; persistedUriPermissions; "
                "openOutputStream"
            ),
            "app/src/main/java/org/quicklauncher/app/AutomaticBackupJobService.kt": (
                "File(noBackupFilesDir, PrivateBackupDiagnosticLog.FILE_NAME)"
            ),
            "app/src/main/java/org/quicklauncher/app/BackupAuxiliaryAdapters.kt": (
                "applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)"
            ),
            "app/src/main/java/org/quicklauncher/app/PrivateBackupDiagnosticLog.kt": (
                "private fun readEvents() { if (file.length() > BackupDiagnosticLogCodec.MAX_BYTES) return; "
                "events.takeLast(capacity) }"
            ),
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/backup/android/"
                "AndroidBackupStorage.kt"
            ): (
                "class AndroidSafBackupFolder { DocumentsContract.isChildDocument(resolver, parent, uri); "
                "resolver.openInputStream(uri); resolver.openOutputStream(uri); "
                "context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE); "
                + "DocumentsContract; " * 22
                + "}"
            ),
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/persistence/"
                "AndroidLauncherStoreFactory.kt"
            ): "applicationContext.getDatabasePath(name)",
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/persistence/"
                "AndroidLauncherPreferencesFactory.kt"
            ): "File(applicationContext.filesDir, DATASTORE_DIRECTORY)",
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/"
                "AndroidThemeAssetStore.kt"
            ): 'File(appContext.filesDir, "theme-assets"); resolver.openInputStream(uri)',
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/"
                "AndroidFileSearchAdapter.kt"
            ): (
                "Intent(Intent.ACTION_VIEW, document.uri); DocumentsContract.isTreeUri(uri); "
                + "DocumentsContract; " * 29
            ),
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/"
                "SearchProviderAvailabilityCoordinator.kt"
            ): "context.contentResolver.persistedUriPermissions",
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/"
                "AndroidSearchTargetPlatform.kt"
            ): (
                "Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE); "
                'Uri.Builder().scheme("https"); '
                "if (uri.host?.lowercase(Locale.ROOT) != catalog.host) return false; "
                "if (uri.port != -1 && uri.port != 443) return false"
            ),
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/diagnostics/"
                "AndroidDiagnosticLogFactory.kt"
            ): "applicationContext.noBackupFilesDir",
            (
                "host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/diagnostics/"
                "Diagnostics.kt"
            ): "MAXIMUM_STACK_FRAMES; MAXIMUM_ALLOWED_RECORDS",
            (
                "host/platform/src/main/kotlin/org/quicklauncher/host/platform/diagnostics/"
                "FileDiagnosticStorage.kt"
            ): "MAXIMUM_FILE_BYTES; MAXIMUM_RECORDS",
        }
        for relative, content in anchors.items():
            self.write(root, relative, content)

    def test_exact_stable_and_preview_manifests_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for channel in ("stable", "preview"):
                path = self.write(root, f"{channel}.xml", manifest(channel))
                self.assertEqual([], audit.audit_merged_manifest(path, channel))

    def test_manifest_rejects_internet_cleartext_and_unprotected_export(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = manifest("stable").replace(
                '<application android:allowBackup="true"',
                '<uses-permission android:name="android.permission.INTERNET" />\n'
                '<application android:allowBackup="true" android:usesCleartextTraffic="true"',
            ).replace(
                'android:excludeFromRecents="true" android:exported="false"',
                'android:excludeFromRecents="true" android:exported="true"',
            )
            findings = audit.audit_merged_manifest(self.write(root, "stable.xml", text), "stable")
            self.assertIn("manifest.permissions", {finding.code for finding in findings})
            self.assertIn("network.internet_permission", {finding.code for finding in findings})
            self.assertIn("network.cleartext", {finding.code for finding in findings})
            self.assertIn("manifest.components", {finding.code for finding in findings})

    def test_manifest_rejects_wrong_channel_identity_and_sdk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = manifest("preview").replace("org.quicklauncher.preview", "org.quicklauncher", 1)
            text = text.replace('android:minSdkVersion="35"', 'android:minSdkVersion="34"')
            findings = audit.audit_merged_manifest(self.write(root, "preview.xml", text), "preview")
            self.assertIn("manifest.application_id", {finding.code for finding in findings})
            self.assertIn("manifest.sdk", {finding.code for finding in findings})

    def test_manifest_rejects_permission_variants_data_and_test_activity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = manifest("stable").replace(
                '<uses-permission android:name="android.permission.READ_CONTACTS" />',
                '<uses-permission-sdk-23 android:name="android.permission.READ_CONTACTS" />',
            ).replace(
                '<category android:name="android.intent.category.LAUNCHER" />',
                '<category android:name="android.intent.category.LAUNCHER" />'
                '<data android:scheme="https" />',
            ).replace(
                "</application>",
                '<activity android:name="androidx.activity.ComponentActivity" '
                'android:exported="true" /></application>',
            )
            findings = audit.audit_merged_manifest(self.write(root, "stable.xml", text), "stable")
            codes = {finding.code for finding in findings}
            self.assertIn("manifest.permission_variant", codes)
            self.assertIn("manifest.intent_filters", codes)
            self.assertIn("manifest.components", codes)

    def test_manifest_rejects_permission_component_and_filter_attribute_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = manifest("stable").replace(
                '<uses-permission android:name="android.permission.READ_CONTACTS" />',
                '<uses-permission android:name="android.permission.READ_CONTACTS" '
                'android:maxSdkVersion="34" />',
            ).replace(
                'android:name="org.quicklauncher.app.AutomaticBackupJobService"',
                'android:name="org.quicklauncher.app.AutomaticBackupJobService" '
                'android:process=":unexpected"',
            ).replace(
                "<intent-filter><action",
                '<intent-filter android:priority="1"><action',
                1,
            )
            codes = {
                finding.code
                for finding in audit.audit_merged_manifest(self.write(root, "stable.xml", text), "stable")
            }
            self.assertIn("manifest.permission_attributes", codes)
            self.assertIn("manifest.component_attributes", codes)
            self.assertIn("manifest.intent_filters", codes)

    def test_manifest_rejects_duplicate_action_and_category_children(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = manifest("stable").replace(
                '<action android:name="android.intent.action.MAIN" />',
                '<action android:name="android.intent.action.MAIN" />'
                '<action android:name="android.intent.action.MAIN" />',
                1,
            ).replace(
                '<category android:name="android.intent.category.HOME" />',
                '<category android:name="android.intent.category.HOME" />'
                '<category android:name="android.intent.category.HOME" />',
                1,
            )
            findings = audit.audit_merged_manifest(self.write(root, "stable.xml", text), "stable")
            self.assertIn("manifest.intent_filters", {finding.code for finding in findings})

    def test_source_patterns_reject_network_and_logging_but_allow_browser_intent(self) -> None:
        self.assertEqual(
            [],
            audit.scan_source_text(
                Path("Browser.kt"),
                'val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))',
            ),
        )
        network = audit.scan_source_text(Path("Network.kt"), "import java.net.HttpURLConnection")
        qualified = audit.scan_source_text(Path("Socket.kt"), "val socket = java.net.Socket(host, 443)")
        native = audit.scan_source_text(Path("Loader.kt"), 'System.loadLibrary("hidden")')
        logging = audit.scan_source_text(Path("Log.kt"), 'android.util.Log.d("tag", "data")')
        self.assertEqual(["network.stack"], [finding.code for finding in network])
        self.assertEqual(["network.stack"], [finding.code for finding in qualified])
        self.assertEqual(["code_loading.risk"], [finding.code for finding in native])
        self.assertEqual(["logging.production"], [finding.code for finding in logging])

    def test_diagnostic_audit_requires_private_bounded_anchors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_production_inventory(root)
            self.assertEqual([], audit.audit_production_sources(root))
            missing = root / "app/src/main/java/org/quicklauncher/app/PrivateBackupDiagnosticLog.kt"
            missing.write_text("class UnboundedLog", encoding="utf-8")
            findings = audit.audit_production_sources(root)
            self.assertEqual(
                ["diagnostics.private_bounded"],
                [finding.code for finding in findings],
            )

    def test_storage_inventory_rejects_unknown_private_and_saf_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_production_inventory(root)
            self.write(
                root,
                "host/runtime/src/main/kotlin/example/Unexpected.kt",
                "val local = context.cacheDir; DocumentsContract.getDocumentId(uri)",
            )
            codes = {finding.code for finding in audit.audit_production_sources(root)}
            self.assertIn("storage.unapproved", codes)
            self.assertIn("storage.saf_unapproved", codes)

    def test_secret_scan_reports_location_without_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secret = "BEGIN " + "PRIVATE KEY"
            self.write(root, "src/value.md", secret)
            findings = audit.audit_secret_material(root)
            self.assertEqual(["secret.private_key"], [finding.code for finding in findings])
            self.assertNotIn(secret, findings[0].render())

    def test_secret_scan_rejects_key_file_even_when_binary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = ("channel.jks", "channel.p8", "id_rsa", "id_ed25519", "secrets.properties")
            for name in names:
                (root / name).write_bytes(b"fixture")
            with mock.patch.object(
                audit,
                "_has_binary_key_magic",
                side_effect=AssertionError("known key filename content must not be read"),
            ), mock.patch.object(
                audit,
                "_contains_pattern",
                side_effect=AssertionError("known key filename content must not be read"),
            ):
                findings = audit.audit_secret_material(root)
            self.assertEqual(
                ["secret.key_file"] * len(names),
                [finding.code for finding in findings],
            )

    def test_secret_scan_covers_extended_tokens_passwords_logs_and_large_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            encrypted = "BEGIN " + "ENCRYPTED PRIVATE KEY"
            github = "github" + "_pat_" + "A" * 24
            password = "store" + "Password=unquoted-value"
            large = "x" * (2 * 1024 * 1024 + 10) + github
            self.write(root, "encrypted.log", encrypted)
            self.write(root, "config.txt", password)
            self.write(root, "large.txt", large)
            codes = [finding.code for finding in audit.audit_secret_material(root)]
            self.assertIn("secret.private_key", codes)
            self.assertIn("secret.signing_password", codes)
            self.assertIn("secret.github_fine_grained_token", codes)

    def test_secret_scan_detects_token_straddling_chunk_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            token = "github" + "_pat_" + "A" * 24
            prefix = "x" * (64 * 1024 - 5)
            self.write(root, "boundary.txt", prefix + token)
            findings = audit.audit_secret_material(root)
            self.assertEqual(
                ["secret.github_fine_grained_token"],
                [finding.code for finding in findings],
            )

    def test_secret_scan_rejects_extensionless_binary_magic_without_following_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory)
            (root / "binary-key").write_bytes(bytes.fromhex("feedfeed") + b"fixture")
            external = Path(outside) / "external.md"
            external.write_text("BEGIN " + "PRIVATE KEY", encoding="utf-8")
            try:
                os.symlink(external, root / "linked.md")
            except OSError as error:
                self.skipTest(f"symlinks unavailable: {type(error).__name__}")
            findings = audit.audit_secret_material(root)
            codes = [finding.code for finding in findings]
            self.assertIn("secret.binary_key", codes)
            self.assertIn("secret.symlink", codes)
            self.assertNotIn("secret.private_key", codes)

    def test_release_config_rejects_debug_signing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = "\n".join(
                [
                    'applicationId = "org.quicklauncher"',
                    'applicationIdSuffix = ".preview"',
                    "versionCode = 2",
                    'versionName = "0.1.1"',
                    'buildConfigField("String", "CHANNEL", "\\"stable\\"")',
                    'buildConfigField("String", "CHANNEL", "\\"preview\\"")',
                    'release { signingConfig = signingConfigs.getByName("debug") }',
                ]
            )
            self.write(root, "app/build.gradle.kts", gradle)
            findings = audit.audit_release_configuration(root)
            self.assertEqual(["release.debug_signer"], [finding.code for finding in findings])

    def test_release_config_ignores_commented_fake_release_block(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(
                root,
                "app/build.gradle.kts",
                '// release { signingConfig = signingConfigs.getByName("debug") }\nrelease { }',
            )
            self.assertEqual([], audit.audit_release_configuration(root))

    def test_release_config_ignores_string_fake_and_rejects_multiline_debug_signing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = "\n".join(
                [
                    'val fake = "release { signingConfig = signingConfigs.getByName(\\"debug\\") }"',
                    "release {",
                    "  signingConfig =",
                    '      signingConfigs.getByName("debug")',
                    "}",
                ]
            )
            self.write(root, "app/build.gradle.kts", gradle)
            self.assertEqual(
                ["release.debug_signer"],
                [finding.code for finding in audit.audit_release_configuration(root)],
            )

    def test_backup_rules_reject_diagnostic_and_unencrypted_entries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy_entries = "\n".join(
                f'<include domain="{domain}" path="{path}" requireFlags="clientSideEncryption" />'
                for domain, path in sorted(audit.BACKUP_INCLUDES)
            )
            legacy_entries += '\n<include domain="file" path="launcher-diagnostics.bin" />'
            self.write(
                root,
                "app/src/main/res/xml/backup_rules.xml",
                f"<full-backup-content>{legacy_entries}</full-backup-content>",
            )
            includes = "\n".join(
                f'<include domain="{domain}" path="{path}" />'
                for domain, path in sorted(audit.BACKUP_INCLUDES)
            )
            self.write(
                root,
                "app/src/main/res/xml/data_extraction_rules.xml",
                "<data-extraction-rules>"
                '<cloud-backup disableIfNoEncryptionCapabilities="true">'
                f"{includes}</cloud-backup><device-transfer>{includes}</device-transfer>"
                "</data-extraction-rules>",
            )
            findings = audit.audit_backup_rules(root)
            codes = {finding.code for finding in findings}
            self.assertIn("backup.legacy_allowlist", codes)
            self.assertIn("backup.legacy_encryption", codes)

    def test_backup_rules_reject_duplicates_nested_entries_and_unexpected_attributes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy_entries = "".join(
                f'<include domain="{domain}" path="{path}" requireFlags="clientSideEncryption" />'
                for domain, path in sorted(audit.BACKUP_INCLUDES)
            )
            self.write(
                root,
                "app/src/main/res/xml/backup_rules.xml",
                f"<full-backup-content>{legacy_entries}</full-backup-content>",
            )
            entry_list = [
                f'<include domain="{domain}" path="{path}" />'
                for domain, path in sorted(audit.BACKUP_INCLUDES)
            ]
            includes = "".join(entry_list)
            nested_entries = entry_list.copy()
            nested_entries[0] = nested_entries[0].replace(" />", "><extra /></include>")
            nested = "".join(nested_entries + [entry_list[1]])
            self.write(
                root,
                "app/src/main/res/xml/data_extraction_rules.xml",
                "<data-extraction-rules>"
                '<cloud-backup disableIfNoEncryptionCapabilities="true" unexpected="x">'
                f"{nested}</cloud-backup><device-transfer>{includes}</device-transfer>"
                "</data-extraction-rules>",
            )
            codes = {finding.code for finding in audit.audit_backup_rules(root)}
            self.assertIn("backup.cloud_encryption", codes)
            self.assertIn("backup.cloud_allowlist", codes)

    def test_cli_rejects_external_synthetic_manifests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stable = self.write(root, "stable.xml", manifest("stable"))
            preview = self.write(root, "preview.xml", manifest("preview"))
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                result = audit.main(
                    [
                        "--stable-manifest",
                        str(stable),
                        "--preview-manifest",
                        str(preview),
                    ]
                )
            self.assertEqual(2, result)
            self.assertIn("cli.manifest_path", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
