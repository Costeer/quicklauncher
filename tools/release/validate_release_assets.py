#!/usr/bin/env python3
"""Fail unless a GitHub draft release has exactly one expected APK asset."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

try:
    from .release_lib import ReleaseError, load_json, verify_checksum_file
except ImportError:
    from release_lib import ReleaseError, load_json, verify_checksum_file


def validate_assets(assets: object, metadata: object) -> None:
    if not isinstance(assets, list) or not isinstance(metadata, dict):
        raise ReleaseError("assets and metadata must be JSON array/object")
    if not isinstance(metadata.get("assetName"), str):
        raise ReleaseError("metadata must contain a string assetName")
    names = [asset.get("name") for asset in assets if isinstance(asset, dict)]
    if len(names) != len(assets) or len(names) != len(set(names)) or not all(isinstance(name, str) for name in names):
        raise ReleaseError("release assets must have unique string names")
    apk_names = [name for name in names if name.lower().endswith(".apk")]
    if apk_names != [metadata.get("assetName")]:
        raise ReleaseError("draft release must contain exactly the metadata-named APK")
    allowed = {metadata["assetName"], "release-metadata.json", f"{metadata['assetName']}.sha256"}
    if set(names) != allowed or len(names) != 3:
        raise ReleaseError(f"draft release assets must be exactly {sorted(allowed)}")


def validate_downloaded_files(download_dir: Path, apk: Path, checksum: Path, metadata: dict) -> None:
    expected_names = {metadata["assetName"], "release-metadata.json", f"{metadata['assetName']}.sha256"}
    try:
        actual_names = {path.name for path in download_dir.iterdir() if path.is_file()}
    except OSError as error:
        raise ReleaseError(f"cannot inspect downloaded asset directory: {error}") from error
    if actual_names != expected_names:
        raise ReleaseError(f"downloaded files must be exactly {sorted(expected_names)}")
    if apk.resolve().parent != download_dir.resolve() or checksum.resolve().parent != download_dir.resolve():
        raise ReleaseError("APK and checksum must be in the downloaded asset directory")
    if apk.name != metadata["assetName"] or checksum.name != f"{apk.name}.sha256":
        raise ReleaseError("downloaded APK/checksum names do not match metadata")
    digest = metadata.get("apkSha256")
    if not isinstance(digest, str):
        raise ReleaseError("metadata lacks APK SHA-256")
    verify_checksum_file(checksum, apk, digest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-json", type=Path, required=True)
    parser.add_argument("--download-dir", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--checksum", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    args = parser.parse_args()
    try:
        assets = load_json(args.assets_json)
        metadata = load_json(args.metadata)
        validate_assets(assets, metadata)
        if not isinstance(metadata, dict):
            raise ReleaseError("metadata must be a JSON object")
        validate_downloaded_files(args.download_dir, args.apk, args.checksum, metadata)
    except ReleaseError as error:
        print(f"release asset validation failed: {error}", file=os.sys.stderr)
        return 1
    print(f"Draft release asset inventory passed: one APK, {metadata['assetName']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
