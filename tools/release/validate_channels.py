#!/usr/bin/env python3
"""Validate channel and Obtainium metadata as a mutually-exclusive policy."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

try:
    from .release_lib import ReleaseError, load_json, require_exact_keys
except ImportError:
    from release_lib import ReleaseError, load_json, require_exact_keys

ROOT = Path(__file__).resolve().parents[2]
SOURCE_CONTRACT_PATH = ".github/release/obtainium-source-contract.json"
CHANNEL_KEYS = (
    "applicationId", "assetRegex", "certificateVariable", "environment", "keySecretPrefix",
    "obtainium", "releaseTitleRegex", "tagRegex", "workflow",
)
OBTAINIUM_KEYS = ("additionalSettings", "author", "id", "name", "overrideSource", "preferredApkIndex", "url")
SETTING_KEYS = (
    "includePrereleases", "fallbackToOlderReleases", "filterReleaseTitlesByRegEx", "verifyLatestTag",
    "trackOnly", "apkFilterRegEx", "invertAPKFilter", "autoApkFilterByArch", "allowInsecure",
)
SAMPLES = {
    "stable": {
        "tag": "v1.2.3", "asset": "quicklauncher-stable-v1.2.3-universal.apk", "title": "Quicklauncher stable v1.2.3"
    },
    "preview": {
        "tag": "v1.2.3-preview.4", "asset": "quicklauncher-preview-v1.2.3-preview.4-universal.apk", "title": "Quicklauncher preview v1.2.3-preview.4"
    },
}
SOURCE_RECORDS = [
    ("af286fa8d31d7406d6db167e2314d376d74f7696", "lib/models/app.dart", "b6b4cbb214cd413a5f24271f8a830e05ee2bfd0d1593750f1bb6f7bee92bba29", 10814),
    ("af286fa8d31d7406d6db167e2314d376d74f7696", "lib/app_sources/app_source.dart", "c41f838e22deeb23a7acc5812ae51dee87ce971e3e48c238d5f6f7cd888a78ad", 17436),
    ("af286fa8d31d7406d6db167e2314d376d74f7696", "lib/app_sources/github.dart", "93687f425212e94713ecafadbfcd41f1d50f44078ae3728b35ee4a0ed1fb27ba", 32874),
    ("2a62ed9288f9efe8e888f27be11d9f88e21df7a1", "CONTRIBUTING.md", "8465368ba77cbfe4fe5d2065317e70607dcfa092bbd39fba2b701cd6c89d12c2", 5758),
]
SELECTOR_FACTS = {
    "apkFilterRegExFiltersAssetNames": True,
    "draftReleasesAreExcluded": True,
    "filterReleaseTitlesByRegExUsesReleaseNameWithTagFallback": True,
    "includePrereleasesFalseExcludesPrereleases": True,
}


def _validate_source_contract(root: Path, contract_path: str) -> None:
    if contract_path != SOURCE_CONTRACT_PATH:
        raise ReleaseError("channel metadata must reference the checked Obtainium source contract")
    contract = load_json(root / contract_path)
    if not isinstance(contract, dict):
        raise ReleaseError("Obtainium source contract must be an object")
    require_exact_keys(
        contract,
        ("schemaVersion", "sources", "allowedTopLevelKeys", "allowedAdditionalSettingsKeys", "selectorFacts"),
        "Obtainium source contract",
    )
    if contract["schemaVersion"] != 1:
        raise ReleaseError("unsupported Obtainium source contract schema")
    if contract["allowedTopLevelKeys"] != sorted(OBTAINIUM_KEYS):
        raise ReleaseError("Obtainium source contract top-level keys differ from the checked config policy")
    if contract["allowedAdditionalSettingsKeys"] != sorted(SETTING_KEYS):
        raise ReleaseError("Obtainium source contract setting keys differ from the checked config policy")
    if contract["selectorFacts"] != SELECTOR_FACTS:
        raise ReleaseError("Obtainium selector facts differ from reviewed source behavior")
    sources = contract["sources"]
    if not isinstance(sources, list) or len(sources) != len(SOURCE_RECORDS):
        raise ReleaseError("Obtainium source contract has the wrong source inventory")
    actual_records = []
    for source in sources:
        if not isinstance(source, dict):
            raise ReleaseError("Obtainium source record must be an object")
        require_exact_keys(source, ("bytes", "commit", "path", "sha256", "url"), "Obtainium source record")
        if source["commit"] not in source["url"] or source["path"] not in source["url"]:
            raise ReleaseError("Obtainium source record URL is not pinned to its path and commit")
        actual_records.append((source["commit"], source["path"], source["sha256"], source["bytes"]))
    if actual_records != SOURCE_RECORDS:
        raise ReleaseError("Obtainium source blob hashes/sizes differ from reviewed revisions")


def validate(root: Path = ROOT) -> None:
    path = root / ".github/release/channels.json"
    document = load_json(path)
    if not isinstance(document, dict):
        raise ReleaseError("channels metadata must be an object")
    require_exact_keys(document, ("schemaVersion", "repository", "obtainiumSourceContract", "officialSources", "channels"), "channels metadata")
    if document["schemaVersion"] != 1 or document["repository"] != "Costeer/quicklauncher":
        raise ReleaseError("channel schema/repository mismatch")
    _validate_source_contract(root, document["obtainiumSourceContract"])
    sources = document["officialSources"]
    if not isinstance(sources, list) or len(sources) != 3:
        raise ReleaseError("exactly three primary-source records are required")
    for source in sources:
        if not isinstance(source, dict):
            raise ReleaseError("official source must be an object")
        require_exact_keys(source, ("commit", "url"), "official source")
        if (
            not isinstance(source["commit"], str)
            or re.fullmatch(r"[0-9a-f]{40}", source["commit"]) is None
            or not isinstance(source["url"], str)
            or not source["url"].startswith("https://github.com/")
            or source["commit"] not in source["url"]
        ):
            raise ReleaseError("official sources require immutable GitHub commit URLs")
    channels = document["channels"]
    if not isinstance(channels, dict) or set(channels) != {"stable", "preview"}:
        raise ReleaseError("exactly stable and preview channels are required")
    identities: set[str] = set()
    environments: set[str] = set()
    key_prefixes: set[str] = set()
    certificate_variables: set[str] = set()
    for name in ("stable", "preview"):
        channel = channels[name]
        if not isinstance(channel, dict):
            raise ReleaseError(f"{name} channel must be an object")
        require_exact_keys(channel, CHANNEL_KEYS, f"{name} channel")
        identities.add(channel["applicationId"])
        environments.add(channel["environment"])
        key_prefixes.add(channel["keySecretPrefix"])
        certificate_variables.add(channel["certificateVariable"])
        for kind in ("tag", "asset", "title"):
            regex_key = {"tag": "tagRegex", "asset": "assetRegex", "title": "releaseTitleRegex"}[kind]
            own = re.fullmatch(channel[regex_key], SAMPLES[name][kind])
            other_name = "preview" if name == "stable" else "stable"
            other = re.fullmatch(channel[regex_key], SAMPLES[other_name][kind])
            if own is None or other is not None:
                raise ReleaseError(f"{name} {kind} selector is not mutually exclusive")
        obtainium_path = root / channel["obtainium"]
        obtainium = load_json(obtainium_path)
        if not isinstance(obtainium, dict):
            raise ReleaseError(f"{name} Obtainium config must be an object")
        require_exact_keys(obtainium, OBTAINIUM_KEYS, f"{name} Obtainium config")
        if obtainium["id"] != channel["applicationId"] or obtainium["url"] != "https://github.com/Costeer/quicklauncher":
            raise ReleaseError(f"{name} Obtainium identity/repository mismatch")
        if obtainium["overrideSource"] != "GitHub" or obtainium["preferredApkIndex"] != 0:
            raise ReleaseError(f"{name} Obtainium source/index mismatch")
        try:
            settings = json.loads(obtainium["additionalSettings"])
        except (TypeError, json.JSONDecodeError) as error:
            raise ReleaseError(f"{name} additionalSettings is invalid JSON") from error
        if not isinstance(settings, dict):
            raise ReleaseError(f"{name} additionalSettings must be an object")
        require_exact_keys(settings, SETTING_KEYS, f"{name} additionalSettings")
        if settings["includePrereleases"] != (name == "preview"):
            raise ReleaseError(f"{name} prerelease policy mismatch")
        if any(settings[key] for key in ("fallbackToOlderReleases", "verifyLatestTag", "trackOnly", "invertAPKFilter", "autoApkFilterByArch", "allowInsecure")):
            raise ReleaseError(f"{name} unsafe/non-deterministic Obtainium setting")
        if settings["apkFilterRegEx"] != channel["assetRegex"] or settings["filterReleaseTitlesByRegEx"] != channel["releaseTitleRegex"]:
            raise ReleaseError(f"{name} Obtainium selectors differ from channel policy")
    if len(identities) != 2 or len(environments) != 2 or len(key_prefixes) != 2 or len(certificate_variables) != 2:
        raise ReleaseError("stable and preview identities, environments, keys, and certificates must all differ")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        validate(args.root.resolve())
    except ReleaseError as error:
        print(f"channel validation failed: {error}", file=os.sys.stderr)
        return 1
    print("Release channel metadata passed: stable and preview are mutually exclusive")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
