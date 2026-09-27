#!/usr/bin/env python3
"""Fail-closed security audit for Quicklauncher's release inputs.

The audit consumes AGP's text merged manifests after the two release manifest
tasks have run. It does not build, sign, install, download, or publish anything.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID_NS}}}"

PLATFORM_PERMISSIONS = {
    "android.permission.ACCESS_HIDDEN_PROFILES",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.READ_CONTACTS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.SET_WALLPAPER",
}

BACKUP_INCLUDES = {
    ("database", "quicklauncher.db"),
    ("file", "datastore/launcher-preferences.pb"),
    ("file", "theme-assets/fonts"),
    ("file", "theme-assets/images"),
    ("file", "theme-assets/previews"),
    ("file", "theme-assets/preserved-backup-assets.v1"),
    ("sharedpref", "portable-backup-web-adapters.xml"),
}

EXPECTED_COMPONENTS = {
    ("activity", "org.quicklauncher.app.MainActivity"): ("true", None),
    ("activity", "org.quicklauncher.host.platform.widgets.WidgetConfigurationActivity"): (
        "false",
        None,
    ),
    ("service", "org.quicklauncher.app.AutomaticBackupJobService"): (
        "false",
        "android.permission.BIND_JOB_SERVICE",
    ),
    (
        "service",
        "org.quicklauncher.host.platform.notifications.QuicklauncherNotificationListenerService",
    ): ("false", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
    ("service", "androidx.room3.MultiInstanceInvalidationService"): ("false", None),
    ("provider", "androidx.startup.InitializationProvider"): ("false", None),
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): (
        "true",
        "android.permission.DUMP",
    ),
}

IGNORED_DIRECTORY_NAMES = {".git", ".gradle", ".idea", ".kotlin", "__pycache__", "build"}
KEY_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".pk8", ".p8"}
KEY_FILE_NAMES = {
    "keystore.properties",
    "signing.properties",
    "secrets.properties",
    "id_rsa",
    "id_ed25519",
    ".env",
}


@dataclass(frozen=True)
class Finding:
    code: str
    path: str
    detail: str

    def render(self) -> str:
        return f"{self.code}: {self.path}: {self.detail}"


def _finding(code: str, path: Path | str, detail: str) -> Finding:
    return Finding(code, str(path), detail)


def _parse_xml(path: Path) -> tuple[ET.Element | None, list[Finding]]:
    try:
        return ET.parse(path).getroot(), []
    except (OSError, ET.ParseError) as error:
        return None, [_finding("xml.invalid", path, type(error).__name__)]


def audit_merged_manifest(path: Path, channel: str) -> list[Finding]:
    root, findings = _parse_xml(path)
    if root is None:
        return findings

    expected_package = "org.quicklauncher" if channel == "stable" else "org.quicklauncher.preview"
    package_name = root.get("package")
    if package_name != expected_package:
        findings.append(
            _finding("manifest.application_id", path, f"expected {expected_package}, got {package_name!r}")
        )

    expected_version_name = "0.1.1" if channel == "stable" else "0.1.1-preview"
    if root.get(A + "versionName") != expected_version_name:
        findings.append(
            _finding(
                "manifest.version_name",
                path,
                f"expected {expected_version_name}, got {root.get(A + 'versionName')!r}",
            )
        )
    if root.get(A + "versionCode") != "2":
        findings.append(
            _finding("manifest.version_code", path, "release versionCode must be exactly 2 for 0.1.1")
        )

    sdk = root.find("uses-sdk")
    if sdk is None or sdk.get(A + "minSdkVersion") != "35" or sdk.get(A + "targetSdkVersion") != "35":
        findings.append(_finding("manifest.sdk", path, "minSdk and targetSdk must both be 35"))

    dynamic_permission = f"{expected_package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    permission_elements = [element for element in root if element.tag.startswith("uses-permission")]
    unsupported_permission_elements = [
        element.tag for element in permission_elements if element.tag != "uses-permission"
    ]
    if unsupported_permission_elements:
        findings.append(
            _finding(
                "manifest.permission_variant",
                path,
                f"unsupported permission elements: {sorted(unsupported_permission_elements)}",
            )
        )
    permission_names = [element.get(A + "name") for element in permission_elements]
    if any(set(element.attrib) != {A + "name"} or len(element) != 0 for element in permission_elements):
        findings.append(
            _finding(
                "manifest.permission_attributes",
                path,
                "permission declarations must contain only android:name and no children",
            )
        )
    permissions = set(permission_names)
    expected_permissions = PLATFORM_PERMISSIONS | {dynamic_permission}
    if permissions != expected_permissions or len(permission_names) != len(expected_permissions):
        missing = sorted(expected_permissions - permissions)
        unexpected = sorted(permissions - expected_permissions, key=lambda item: str(item))
        findings.append(
            _finding(
                "manifest.permissions",
                path,
                f"missing={missing}; unexpected={unexpected}",
            )
        )
    if "android.permission.INTERNET" in permissions:
        findings.append(_finding("network.internet_permission", path, "INTERNET must remain absent"))

    declared_permission_elements = root.findall("permission")
    declared_permissions = [
        (element.get(A + "name"), element.get(A + "protectionLevel"))
        for element in declared_permission_elements
    ]
    if (
        declared_permissions != [(dynamic_permission, "signature")]
        or any(
            set(element.attrib) != {A + "name", A + "protectionLevel"} or len(element) != 0
            for element in declared_permission_elements
        )
    ):
        findings.append(
            _finding(
                "manifest.declared_permissions",
                path,
                "only the package-scoped signature dynamic-receiver permission is allowed",
            )
        )

    application = root.find("application")
    if application is None:
        findings.append(_finding("manifest.application", path, "application element is missing"))
        return findings
    if application.get(A + "allowBackup") != "true":
        findings.append(_finding("manifest.backup", path, "allowBackup must be explicit and true"))
    if application.get(A + "fullBackupContent") != "@xml/backup_rules":
        findings.append(_finding("manifest.backup", path, "fullBackupContent must name backup_rules"))
    if application.get(A + "dataExtractionRules") != "@xml/data_extraction_rules":
        findings.append(
            _finding("manifest.backup", path, "dataExtractionRules must name data_extraction_rules")
        )
    if application.get(A + "debuggable") == "true":
        findings.append(_finding("manifest.debuggable", path, "release manifest is debuggable"))
    if application.get(A + "usesCleartextTraffic") == "true":
        findings.append(_finding("network.cleartext", path, "cleartext traffic is enabled"))

    actual_components: dict[tuple[str, str | None], tuple[str | None, str | None]] = {}
    expected_component_attributes = _expected_component_attributes(expected_package)
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for component in application.findall(tag):
            name = component.get(A + "name")
            exported = component.get(A + "exported")
            permission = component.get(A + "permission")
            key = (tag, name)
            if key in actual_components:
                findings.append(
                    _finding("manifest.components", path, f"duplicate component declaration: {key}")
                )
            actual_components[key] = (exported, permission)
            if component.attrib != expected_component_attributes.get(key):
                findings.append(
                    _finding(
                        "manifest.component_attributes",
                        path,
                        f"unexpected exact attributes for {key}",
                    )
                )
            if exported not in {"true", "false"}:
                findings.append(
                    _finding(
                        "manifest.component_export",
                        path,
                        f"{tag} {name!r} lacks an explicit exported value",
                    )
                )
    if actual_components != EXPECTED_COMPONENTS:
        missing = sorted(set(EXPECTED_COMPONENTS) - set(actual_components), key=str)
        unexpected = sorted(set(actual_components) - set(EXPECTED_COMPONENTS), key=str)
        changed = sorted(
            key
            for key in set(actual_components) & set(EXPECTED_COMPONENTS)
            if actual_components[key] != EXPECTED_COMPONENTS[key]
        )
        findings.append(
            _finding(
                "manifest.components",
                path,
                f"missing={missing}; unexpected={unexpected}; changed={changed}",
            )
        )

    expected_filters = {
        ("activity", "org.quicklauncher.app.MainActivity"): (
            (
                ("android.intent.action.MAIN",),
                ("android.intent.category.LAUNCHER",),
                (),
                True,
            ),
            (
                ("android.intent.action.MAIN",),
                ("android.intent.category.HOME", "android.intent.category.DEFAULT"),
                (),
                True,
            ),
        ),
        (
            "service",
            "org.quicklauncher.host.platform.notifications.QuicklauncherNotificationListenerService",
        ): (
            (
                ("android.service.notification.NotificationListenerService",),
                (),
                (),
                True,
            ),
        ),
        ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): (
            (
                ("androidx.profileinstaller.action.INSTALL_PROFILE",),
                (),
                (),
                True,
            ),
            (
                ("androidx.profileinstaller.action.SKIP_FILE",),
                (),
                (),
                True,
            ),
            (
                ("androidx.profileinstaller.action.SAVE_PROFILE",),
                (),
                (),
                True,
            ),
            (
                ("androidx.profileinstaller.action.BENCHMARK_OPERATION",),
                (),
                (),
                True,
            ),
        ),
    }
    for key, component in _components(application).items():
        actual_filters = _intent_filter_shapes(component)
        if Counter(actual_filters) != Counter(expected_filters.get(key, ())):
            findings.append(
                _finding("manifest.intent_filters", path, f"unexpected intent-filter shape for {key}")
            )
        expected_metadata = {
            (
                "androidx.emoji2.text.EmojiCompatInitializer",
                "androidx.startup",
            ),
            (
                "androidx.lifecycle.ProcessLifecycleInitializer",
                "androidx.startup",
            ),
            (
                "androidx.profileinstaller.ProfileInstallerInitializer",
                "androidx.startup",
            ),
        } if key == ("provider", "androidx.startup.InitializationProvider") else set()
        metadata = component.findall("meta-data")
        actual_metadata = {
            (item.get(A + "name"), item.get(A + "value"))
            for item in metadata
        }
        unexpected_children = [
            child.tag for child in component if child.tag not in {"intent-filter", "meta-data"}
        ]
        if (
            len(metadata) != len(expected_metadata)
            or actual_metadata != expected_metadata
            or any(
                set(item.attrib) != {A + "name", A + "value"} or len(item) != 0
                for item in metadata
            )
            or unexpected_children
        ):
            findings.append(
                _finding("manifest.component_children", path, f"unexpected children for {key}")
            )

    queries = root.findall("queries")
    if len(queries) != 1 or not _has_exact_queries_shape(queries[0]):
        findings.append(_finding("manifest.queries", path, "queries must contain only DELETE package intent"))

    main = application.find("activity[@android:name='org.quicklauncher.app.MainActivity']", {"android": ANDROID_NS})
    if main is None or main.get(A + "launchMode") != "singleTask":
        findings.append(_finding("manifest.home", path, "MainActivity must be singleTask"))

    return findings


def _components(application: ET.Element) -> dict[tuple[str, str | None], ET.Element]:
    return {
        (tag, component.get(A + "name")): component
        for tag in ("activity", "activity-alias", "service", "receiver", "provider")
        for component in application.findall(tag)
    }


def _expected_component_attributes(package_name: str) -> dict[tuple[str, str], dict[str, str]]:
    return {
        ("activity", "org.quicklauncher.app.MainActivity"): {
            A + "name": "org.quicklauncher.app.MainActivity",
            A + "exported": "true",
            A + "launchMode": "singleTask",
        },
        ("activity", "org.quicklauncher.host.platform.widgets.WidgetConfigurationActivity"): {
            A + "name": "org.quicklauncher.host.platform.widgets.WidgetConfigurationActivity",
            A + "excludeFromRecents": "true",
            A + "exported": "false",
        },
        ("service", "org.quicklauncher.app.AutomaticBackupJobService"): {
            A + "name": "org.quicklauncher.app.AutomaticBackupJobService",
            A + "exported": "false",
            A + "permission": "android.permission.BIND_JOB_SERVICE",
        },
        (
            "service",
            "org.quicklauncher.host.platform.notifications.QuicklauncherNotificationListenerService",
        ): {
            A + "name": "org.quicklauncher.host.platform.notifications.QuicklauncherNotificationListenerService",
            A + "exported": "false",
            A + "label": "@string/notification_listener_label",
            A + "permission": "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        },
        ("service", "androidx.room3.MultiInstanceInvalidationService"): {
            A + "name": "androidx.room3.MultiInstanceInvalidationService",
            A + "directBootAware": "true",
            A + "exported": "false",
        },
        ("provider", "androidx.startup.InitializationProvider"): {
            A + "name": "androidx.startup.InitializationProvider",
            A + "authorities": f"{package_name}.androidx-startup",
            A + "exported": "false",
        },
        ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): {
            A + "name": "androidx.profileinstaller.ProfileInstallReceiver",
            A + "directBootAware": "false",
            A + "enabled": "true",
            A + "exported": "true",
            A + "permission": "android.permission.DUMP",
        },
    }


def _intent_filter_shapes(
    component: ET.Element,
) -> list[tuple[tuple[str | None, ...], tuple[str | None, ...], tuple[tuple[tuple[str, str], ...], ...], bool]]:
    filters: list[
        tuple[tuple[str | None, ...], tuple[str | None, ...], tuple[tuple[tuple[str, str], ...], ...], bool]
    ] = []
    for intent_filter in component.findall("intent-filter"):
        action_nodes = intent_filter.findall("action")
        category_nodes = intent_filter.findall("category")
        data_nodes = intent_filter.findall("data")
        actions = tuple(
            action.get(A + "name") for action in action_nodes
        )
        categories = tuple(
            category.get(A + "name") for category in category_nodes
        )
        data = tuple(
            tuple(sorted((key.removeprefix(A), value) for key, value in item.attrib.items()))
            for item in data_nodes
        )
        exact = (
            not intent_filter.attrib
            and all(set(item.attrib) == {A + "name"} and len(item) == 0 for item in action_nodes)
            and all(set(item.attrib) == {A + "name"} and len(item) == 0 for item in category_nodes)
            and all(len(item) == 0 for item in data_nodes)
            and all(child.tag in {"action", "category", "data"} for child in intent_filter)
        )
        filters.append((actions, categories, data, exact))
    return filters


def _has_exact_queries_shape(queries: ET.Element) -> bool:
    if queries.attrib or len(queries) != 1 or queries[0].tag != "intent":
        return False
    intent = queries[0]
    if intent.attrib or any(child.tag not in {"action", "category", "data"} for child in intent):
        return False
    actions = [child.get(A + "name") for child in intent.findall("action")]
    categories = intent.findall("category")
    data = intent.findall("data")
    return (
        actions == ["android.intent.action.DELETE"]
        and not categories
        and len(data) == 1
        and data[0].attrib == {A + "scheme": "package"}
        and len(data[0]) == 0
    )


def _validate_include_container(
    parent: ET.Element,
    path: Path,
    code: str,
    include_attributes: set[str],
) -> list[Finding]:
    findings: list[Finding] = []
    children = list(parent)
    entries = [(child.get("domain"), child.get("path")) for child in children]
    if (
        any(child.tag != "include" for child in children)
        or len(entries) != len(BACKUP_INCLUDES)
        or len(set(entries)) != len(entries)
        or set(entries) != BACKUP_INCLUDES
    ):
        findings.append(_finding(code, path, "include allowlist, uniqueness, or nesting changed"))
    expected_attributes = {"domain", "path"} | include_attributes
    for child in children:
        if child.tag != "include":
            continue
        if set(child.attrib) != expected_attributes or len(child) != 0:
            findings.append(_finding(code, path, "include attributes or descendants changed"))
            break
    return findings


def audit_backup_rules(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    legacy_path = root / "app/src/main/res/xml/backup_rules.xml"
    legacy, errors = _parse_xml(legacy_path)
    findings.extend(errors)
    if legacy is not None:
        if legacy.tag != "full-backup-content" or legacy.attrib:
            findings.append(_finding("backup.legacy_shape", legacy_path, "root shape changed"))
        findings.extend(
            _validate_include_container(
                legacy,
                legacy_path,
                "backup.legacy_allowlist",
                {"requireFlags"},
            )
        )
        for include in legacy.findall("include"):
            if include.get("requireFlags") != "clientSideEncryption":
                findings.append(
                    _finding(
                        "backup.legacy_encryption",
                        legacy_path,
                        "every legacy include must require clientSideEncryption",
                    )
                )
                break

    extraction_path = root / "app/src/main/res/xml/data_extraction_rules.xml"
    extraction, errors = _parse_xml(extraction_path)
    findings.extend(errors)
    if extraction is not None:
        children = list(extraction)
        cloud_nodes = [child for child in children if child.tag == "cloud-backup"]
        transfer_nodes = [child for child in children if child.tag == "device-transfer"]
        if (
            extraction.tag != "data-extraction-rules"
            or extraction.attrib
            or len(children) != 2
            or len(cloud_nodes) != 1
            or len(transfer_nodes) != 1
        ):
            findings.append(_finding("backup.extraction_shape", extraction_path, "root shape changed"))
        cloud = cloud_nodes[0] if len(cloud_nodes) == 1 else None
        transfer = transfer_nodes[0] if len(transfer_nodes) == 1 else None
        if cloud is None or cloud.attrib != {"disableIfNoEncryptionCapabilities": "true"}:
            findings.append(
                _finding("backup.cloud_encryption", extraction_path, "encrypted cloud backup is required")
            )
        if cloud is not None:
            findings.extend(
                _validate_include_container(cloud, extraction_path, "backup.cloud_allowlist", set())
            )
        if transfer is None or transfer.attrib:
            findings.append(_finding("backup.transfer_allowlist", extraction_path, "transfer shape changed"))
        if transfer is not None:
            findings.extend(
                _validate_include_container(transfer, extraction_path, "backup.transfer_allowlist", set())
            )
    return findings


SOURCE_PATTERNS = {
    "network.stack": re.compile(
        r"(?:(?:java|javax)\.net\.|android\.net\.(?:ConnectivityManager|Network|NetworkCapabilities|"
        r"NetworkRequest|TrafficStats)|android\.webkit\.|\b(?:URL|URLConnection|HttpURLConnection|"
        r"HttpsURLConnection|Socket|ServerSocket|DatagramSocket|SSLSocket|OkHttpClient|Retrofit|"
        r"WebView|CronetEngine)\b|\b(?:okhttp3|retrofit2|io\.ktor\.client|com\.android\.volley|"
        r"org\.apache\.http|org\.chromium\.net)\.)"
    ),
    "code_loading.risk": re.compile(
        r"(?:System\.load(?:Library)?\s*\(|Runtime\.getRuntime\(\)\.exec\s*\(|"
        r"\bProcessBuilder\s*\(|Class\.forName\s*\(|(?:DexClassLoader|PathClassLoader)\s*\(|"
        r"java\.lang\.reflect\.|kotlin\.reflect\.full\.|\bexternal\s+fun\b)"
    ),
    "logging.production": re.compile(
        r"(?:android\.util\.Log|\bLog\.(?:v|d|i|w|e|wtf)\s*\(|\bTimber\.|"
        r"\b(?:print|println)\s*\(|\.printStackTrace\s*\(|System\.(?:out|err))"
    ),
}


STORAGE_INVENTORY = {
    "app/src/main/java/org/quicklauncher/app/MainActivity.kt": (
        r"File\(noBackupFilesDir,\s*PrivateBackupDiagnosticLog\.FILE_NAME\)",
    ),
    "app/src/main/java/org/quicklauncher/app/AutomaticBackupJobService.kt": (
        r"File\(noBackupFilesDir,\s*PrivateBackupDiagnosticLog\.FILE_NAME\)",
    ),
    "app/src/main/java/org/quicklauncher/app/BackupAuxiliaryAdapters.kt": (
        r"applicationContext\.getSharedPreferences\(PREFERENCES,\s*Context\.MODE_PRIVATE\)",
    ),
    "host/platform/src/main/kotlin/org/quicklauncher/host/backup/android/AndroidBackupStorage.kt": (
        r"context\.getSharedPreferences\(PREFERENCES,\s*Context\.MODE_PRIVATE\)",
    ),
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/persistence/AndroidLauncherStoreFactory.kt": (
        r"applicationContext\.getDatabasePath\(name\)",
    ),
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/persistence/AndroidLauncherPreferencesFactory.kt": (
        r"File\(applicationContext\.filesDir,\s*DATASTORE_DIRECTORY\)",
    ),
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/diagnostics/AndroidDiagnosticLogFactory.kt": (
        r"applicationContext\.noBackupFilesDir",
    ),
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStore.kt": (
        r"File\(appContext\.filesDir,\s*\"theme-assets\"\)",
    ),
}

STORAGE_ROOT_PATTERN = re.compile(
    r"(?:\b(?:filesDir|noBackupFilesDir|cacheDir|externalCacheDir)\b|"
    r"\bget(?:External)?FilesDir\s*\(|\bgetSharedPreferences\s*\(|\bgetDatabasePath\s*\(|"
    r"\bopenFile(?:Input|Output)\s*\()"
)

SAF_INVENTORY = {
    "app/src/main/java/org/quicklauncher/app/MainActivity.kt": 4,
    "host/platform/src/main/kotlin/org/quicklauncher/host/backup/android/AndroidBackupStorage.kt": 25,
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidFileSearchAdapter.kt": 30,
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/SearchProviderAvailabilityCoordinator.kt": 1,
    "host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStore.kt": 1,
}

SAF_PATTERN = re.compile(
    r"(?:ACTION_OPEN_DOCUMENT(?:_TREE)?|DocumentsContract|persistedUriPermissions|"
    r"takePersistableUriPermission|releasePersistableUriPermission|openInputStream|openOutputStream)"
)


def _strip_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\r\n]*", "", text, flags=re.DOTALL)


def scan_source_text(path: Path, text: str) -> list[Finding]:
    source = _strip_comments(text)
    return [
        _finding(finding_code, path, "forbidden production source pattern")
        for finding_code, pattern in SOURCE_PATTERNS.items()
        if pattern.search(source)
    ]


def audit_production_sources(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    files, symlinks = _repository_entries(root)
    production_sources: dict[str, str] = {}
    for path in files:
        if path.suffix not in {".kt", ".java"}:
            continue
        relative = path.relative_to(root)
        if "src" not in relative.parts or "main" not in relative.parts:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            findings.append(_finding("source.encoding", relative, "production source is not UTF-8"))
            continue
        production_sources[str(relative)] = text
        findings.extend(scan_source_text(relative, text))

    dependency_files = [
        path
        for path in files
        if path == root / "gradle/libs.versions.toml" or path.name == "build.gradle.kts"
    ]
    forbidden_dependencies = re.compile(
        r"(?:okhttp|retrofit|ktor-client|androidx\.webkit|com\.android\.volley|"
        r"org\.apache\.httpcomponents|org\.chromium\.net|cronet)",
        re.IGNORECASE,
    )
    for path in dependency_files:
        if path.is_file() and forbidden_dependencies.search(path.read_text(encoding="utf-8")):
            findings.append(
                _finding("network.dependency", path.relative_to(root), "network dependency marker found")
            )

    for relative, patterns in STORAGE_INVENTORY.items():
        code = _strip_comments(production_sources.get(relative, ""))
        allowed_spans: list[tuple[int, int]] = []
        for pattern in patterns:
            matches = list(re.finditer(pattern, code))
            allowed_spans.extend(match.span() for match in matches)
            if len(matches) != 1:
                findings.append(_finding("storage.inventory", relative, "required private root is absent"))
    for relative, text in production_sources.items():
        code = _strip_comments(text)
        storage_matches = list(STORAGE_ROOT_PATTERN.finditer(code))
        allowed_spans = [
            match.span()
            for pattern in STORAGE_INVENTORY.get(relative, ())
            for match in re.finditer(pattern, code)
        ]
        if any(
            not any(start <= match.start() < end for start, end in allowed_spans)
            for match in storage_matches
        ):
            findings.append(_finding("storage.unapproved", relative, "storage root is not inventoried"))
        saf_count = len(SAF_PATTERN.findall(code))
        if saf_count != SAF_INVENTORY.get(relative, 0):
            findings.append(
                _finding("storage.saf_unapproved", relative, "SAF boundary count changed")
            )

    diagnostic_contracts = {
        "app/src/main/java/org/quicklauncher/app/PrivateBackupDiagnosticLog.kt": (
            r"private\s+fun\s+readEvents\([^)]*\).*?BackupDiagnosticLogCodec\.MAX_BYTES",
            r"takeLast\(capacity\)",
        ),
        "host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/diagnostics/Diagnostics.kt": (
            r"MAXIMUM_STACK_FRAMES",
            r"MAXIMUM_ALLOWED_RECORDS",
        ),
        "host/platform/src/main/kotlin/org/quicklauncher/host/platform/diagnostics/FileDiagnosticStorage.kt": (
            r"MAXIMUM_FILE_BYTES",
            r"MAXIMUM_RECORDS",
        ),
    }
    for relative, patterns in diagnostic_contracts.items():
        code = _strip_comments(production_sources.get(relative, ""))
        if any(re.search(pattern, code, re.DOTALL) is None for pattern in patterns):
            findings.append(
                _finding("diagnostics.private_bounded", relative, "bounded typed implementation changed")
            )

    action_view_occurrences = {
        relative: len(re.findall(r"Intent\.ACTION_VIEW", _strip_comments(text)))
        for relative, text in production_sources.items()
        if "Intent.ACTION_VIEW" in _strip_comments(text)
    }
    expected_action_views = {
        "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidFileSearchAdapter.kt": 1,
        "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidSearchTargetPlatform.kt": 1,
    }
    if action_view_occurrences != expected_action_views:
        findings.append(
            _finding("network.external_handoff", "production sources", "ACTION_VIEW inventory changed")
        )
    web_source = _strip_comments(
        production_sources.get(
            "host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidSearchTargetPlatform.kt",
            "",
        )
    )
    web_anchors = (
        r"Intent\(Intent\.ACTION_VIEW,\s*uri\)\.addCategory\(Intent\.CATEGORY_BROWSABLE\)",
        r"\.scheme\(\"https\"\)",
        r"uri\.host\?\.lowercase\(Locale\.ROOT\)\s*!=\s*catalog\.host",
        r"uri\.port\s*!=\s*-1\s*&&\s*uri\.port\s*!=\s*443",
    )
    if any(re.search(pattern, web_source) is None for pattern in web_anchors):
        findings.append(
            _finding("network.external_handoff", "AndroidSearchTargetPlatform.kt", "HTTPS handoff changed")
        )
    return findings


def _repository_entries(root: Path) -> tuple[list[Path], list[Path]]:
    """Walk without following any directory or file symlink."""
    if root.is_symlink():
        return [], [root]
    files: list[Path] = []
    symlinks: list[Path] = []
    stack = [root]
    while stack:
        directory = stack.pop()
        with os.scandir(directory) as entries:
            for entry in entries:
                path = Path(entry.path)
                relative = path.relative_to(root)
                if entry.is_symlink():
                    symlinks.append(path)
                elif entry.is_dir(follow_symlinks=False):
                    if entry.name not in IGNORED_DIRECTORY_NAMES:
                        stack.append(path)
                elif entry.is_file(follow_symlinks=False) and not any(
                    part in IGNORED_DIRECTORY_NAMES for part in relative.parts
                ):
                    files.append(path)
    return sorted(files), sorted(symlinks)


PRIVATE_KEY_PATTERN = re.compile(
    ("BEGIN" + r"\s+(?:(?:RSA|EC|OPENSSH|ENCRYPTED)\s+)?PRIV" + "ATE KEY")
)
TOKEN_PATTERNS = {
    "secret.github_token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
    "secret.github_fine_grained_token": re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    "secret.gitlab_token": re.compile(r"glpat-[A-Za-z0-9_-]{20,}"),
    "secret.npm_token": re.compile(r"npm_[A-Za-z0-9]{20,}"),
    "secret.slack_token": re.compile(r"xox[baprs]-[A-Za-z0-9-]{20,}"),
    "secret.stripe_token": re.compile(r"sk_live_[A-Za-z0-9]{20,}"),
    "secret.google_api_key": re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    "secret.aws_access_key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "secret.private_key": PRIVATE_KEY_PATTERN,
    "secret.signing_password": re.compile(
        r"(?i)(?:storePassword|keyPassword|KEYSTORE_PASSWORD|KEY_PASSWORD)\s*[=:]\s*"
        r"(?!(?:System\.(?:getenv|getProperty)|providers\.environmentVariable|findProperty|"
        r"project\.property|System\.console)\b)[\"']?[^\s#;\"']+"
    ),
}

BINARY_KEY_MAGICS = (
    bytes.fromhex("feedfeed"),  # Java KeyStore
    bytes.fromhex("cececece"),  # JCEKS
    b"openssh-key-v1\x00",
)


def _contains_pattern(path: Path, pattern: re.Pattern[str]) -> bool:
    overlap = b""
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(64 * 1024)
            if not chunk:
                return False
            window = overlap + chunk
            if pattern.search(window.decode("utf-8", errors="ignore")):
                return True
            overlap = window[-1024:]


def _has_binary_key_magic(path: Path) -> bool:
    with path.open("rb") as stream:
        prefix = stream.read(4096)
    if any(prefix.startswith(magic) for magic in BINARY_KEY_MAGICS):
        return True
    return (
        path.suffix.lower() in {"", ".bin", ".der", ".pk8"}
        and path.stat().st_size <= 2 * 1024 * 1024
        and prefix.startswith(b"\x30\x82")
    )


def audit_secret_material(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    files, symlinks = _repository_entries(root)
    findings.extend(
        _finding("secret.symlink", path.relative_to(root), "repository symlink is not followed")
        for path in symlinks
    )
    for path in files:
        relative = path.relative_to(root)
        lower_name = path.name.lower()
        if path.suffix.lower() in KEY_SUFFIXES or lower_name in KEY_FILE_NAMES or lower_name.startswith(".env."):
            findings.append(_finding("secret.key_file", relative, "key or secret material filename is forbidden"))
            continue
        if _has_binary_key_magic(path):
            findings.append(_finding("secret.binary_key", relative, "binary key material signature found"))
            continue
        for code, pattern in TOKEN_PATTERNS.items():
            if _contains_pattern(path, pattern):
                findings.append(_finding(code, relative, "possible secret material; value withheld"))
    return findings


def audit_release_configuration(root: Path) -> list[Finding]:
    path = root / "app/build.gradle.kts"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        return [_finding("release.config", path, type(error).__name__)]
    findings: list[Finding] = []
    # Application IDs, versions, SDK levels, permissions, and debuggability are
    # asserted from AGP's merged variant outputs. Source inspection is limited
    # to the one invariant that a merged manifest cannot expose: debug signing.
    try:
        release_blocks = _kotlin_named_blocks(_tokenize_kotlin(text), "release")
    except ValueError:
        findings.append(
            _finding(
                "release.config",
                path.relative_to(root),
                "Kotlin DSL is not structurally parseable",
            )
        )
        return findings
    if len(release_blocks) != 1:
        findings.append(
            _finding(
                "release.config",
                path.relative_to(root),
                f"expected exactly one structural release build type, found {len(release_blocks)}",
            )
        )
        return findings
    release_block = release_blocks[0]
    if _assignment_contains(release_block, "isDebuggable", {"true"}):
        findings.append(_finding("release.debuggable", path.relative_to(root), "debuggable build configured"))
    if _assignment_contains(release_block, "signingConfig", {"debug"}):
        findings.append(
            _finding("release.debug_signer", path.relative_to(root), "release must not use the debug signer")
        )
    return findings


@dataclass(frozen=True)
class _KotlinToken:
    kind: str
    value: str
    line: int


def _tokenize_kotlin(text: str) -> list[_KotlinToken]:
    """Tokenize the small Kotlin-DSL surface needed for release signing checks.

    Strings and comments are consumed structurally, so braces and `release {`
    text inside them cannot masquerade as a build-type declaration.
    """
    tokens: list[_KotlinToken] = []
    index = 0
    line = 1
    while index < len(text):
        character = text[index]
        if character == "\n":
            tokens.append(_KotlinToken("newline", "\n", line))
            line += 1
            index += 1
            continue
        if character.isspace():
            index += 1
            continue
        if text.startswith("//", index):
            newline = text.find("\n", index + 2)
            index = len(text) if newline < 0 else newline
            continue
        if text.startswith("/*", index):
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith("/*", index):
                    depth += 1
                    index += 2
                elif text.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    if text[index] == "\n":
                        tokens.append(_KotlinToken("newline", "\n", line))
                        line += 1
                    index += 1
            if depth:
                raise ValueError("unterminated block comment")
            continue
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            if end < 0:
                raise ValueError("unterminated triple-quoted string")
            value = text[index + 3:end]
            tokens.append(_KotlinToken("string", value, line))
            newline_count = value.count("\n")
            if newline_count:
                line += newline_count
                tokens.append(_KotlinToken("newline", "\n", line))
            index = end + 3
            continue
        if character in {'"', "'"}:
            quote = character
            start_line = line
            index += 1
            value: list[str] = []
            escaped = False
            while index < len(text):
                current = text[index]
                if escaped:
                    value.append(current)
                    escaped = False
                elif current == "\\":
                    escaped = True
                elif current == quote:
                    index += 1
                    break
                else:
                    value.append(current)
                    if current == "\n":
                        line += 1
                index += 1
            else:
                raise ValueError("unterminated quoted literal")
            tokens.append(_KotlinToken("string", "".join(value), start_line))
            continue
        if character == "`":
            end = text.find("`", index + 1)
            if end < 0:
                raise ValueError("unterminated quoted identifier")
            tokens.append(_KotlinToken("identifier", text[index + 1:end], line))
            index = end + 1
            continue
        identifier = re.match(r"[A-Za-z_$][A-Za-z0-9_$]*", text[index:])
        if identifier:
            value = identifier.group(0)
            tokens.append(_KotlinToken("identifier", value, line))
            index += len(value)
            continue
        tokens.append(_KotlinToken("symbol", character, line))
        index += 1
    return tokens


def _next_non_newline(tokens: Sequence[_KotlinToken], index: int) -> int:
    while index < len(tokens) and tokens[index].kind == "newline":
        index += 1
    return index


def _kotlin_named_blocks(tokens: Sequence[_KotlinToken], name: str) -> list[list[_KotlinToken]]:
    blocks: list[list[_KotlinToken]] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        brace_index = _next_non_newline(tokens, index + 1)
        if (
            token.kind == "identifier"
            and token.value == name
            and brace_index < len(tokens)
            and tokens[brace_index].value == "{"
        ):
            depth = 1
            cursor = brace_index + 1
            while cursor < len(tokens) and depth:
                if tokens[cursor].value == "{":
                    depth += 1
                elif tokens[cursor].value == "}":
                    depth -= 1
                cursor += 1
            if depth:
                raise ValueError("unterminated named block")
            blocks.append(list(tokens[brace_index + 1:cursor - 1]))
            index = cursor
            continue
        index += 1
    return blocks


def _assignment_contains(
    tokens: Sequence[_KotlinToken],
    name: str,
    forbidden_values: set[str],
) -> bool:
    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.value != name:
            continue
        equals = _next_non_newline(tokens, index + 1)
        if equals >= len(tokens) or tokens[equals].value != "=":
            continue
        cursor = _next_non_newline(tokens, equals + 1)
        expression: list[_KotlinToken] = []
        nesting = 0
        while cursor < len(tokens):
            current = tokens[cursor]
            if current.kind == "newline":
                lookahead = _next_non_newline(tokens, cursor + 1)
                continues = (
                    nesting > 0
                    or not expression
                    or (lookahead < len(tokens) and tokens[lookahead].value == ".")
                    or expression[-1].value in {".", "?", "=", ",", "("}
                )
                if not continues:
                    break
                cursor += 1
                continue
            if current.value in {"(", "["}:
                nesting += 1
            elif current.value in {")", "]"}:
                if nesting == 0:
                    break
                nesting -= 1
            elif current.value == ";" and nesting == 0:
                break
            expression.append(current)
            cursor += 1
        if any(item.value.lower() in forbidden_values for item in expression):
            return True
    return False


def audit_gitignore(root: Path) -> list[Finding]:
    path = root / ".gitignore"
    try:
        entries = {
            line.strip()
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
    except OSError as error:
        return [_finding("secret.gitignore", path, type(error).__name__)]
    required = {
        "*.jks",
        "*.keystore",
        "*.p12",
        "*.pfx",
        "*.pem",
        "*.key",
        "*.pk8",
        "*.p8",
        "id_rsa",
        "id_ed25519",
        "keystore.properties",
        "signing.properties",
        "secrets.properties",
        ".env",
        ".env.*",
    }
    missing = sorted(required - entries)
    return [] if not missing else [_finding("secret.gitignore", path, f"missing patterns: {missing}")]


def run_audit(root: Path, stable_manifest: Path, preview_manifest: Path) -> list[Finding]:
    findings: list[Finding] = []
    findings.extend(audit_merged_manifest(stable_manifest, "stable"))
    findings.extend(audit_merged_manifest(preview_manifest, "preview"))
    findings.extend(audit_backup_rules(root))
    findings.extend(audit_production_sources(root))
    findings.extend(audit_secret_material(root))
    findings.extend(audit_release_configuration(root))
    findings.extend(audit_gitignore(root))
    return findings


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stable-manifest", type=Path, required=True)
    parser.add_argument("--preview-manifest", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    root = Path(__file__).resolve().parents[2]
    expected = {
        "stable": root
        / "app/build/intermediates/merged_manifests/stableRelease/processStableReleaseManifest/AndroidManifest.xml",
        "preview": root
        / "app/build/intermediates/merged_manifests/previewRelease/processPreviewReleaseManifest/AndroidManifest.xml",
    }
    supplied = {"stable": args.stable_manifest, "preview": args.preview_manifest}
    path_findings: list[Finding] = []
    for channel, path in supplied.items():
        expected_path = expected[channel]
        try:
            supplied_absolute = Path(os.path.abspath(path))
            supplied_absolute.relative_to(root)
            resolved = path.resolve(strict=True)
            expected_resolved = expected_path.resolve(strict=True)
        except (OSError, ValueError) as error:
            path_findings.append(_finding("cli.manifest_path", path, type(error).__name__))
            continue
        if (
            supplied_absolute != expected_path
            or resolved != expected_resolved
            or not resolved.is_relative_to(root)
            or _path_has_symlink(root, supplied_absolute)
        ):
            path_findings.append(
                _finding("cli.manifest_path", path, f"must be the exact {channel}Release AGP output")
            )
    if path_findings:
        for finding in path_findings:
            print(f"- {finding.render()}", file=sys.stderr)
        return 2
    findings = run_audit(root, expected["stable"], expected["preview"])
    if findings:
        print(f"Release security audit failed with {len(findings)} finding(s):", file=sys.stderr)
        for finding in findings:
            print(f"- {finding.render()}", file=sys.stderr)
        return 1
    print("Release security audit passed: 2 manifests, backup, source, diagnostics, secrets, and config")
    return 0


def _path_has_symlink(root: Path, path: Path) -> bool:
    relative = path.relative_to(root)
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            return True
    return False


if __name__ == "__main__":
    raise SystemExit(main())
