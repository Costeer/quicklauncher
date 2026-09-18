#!/usr/bin/env python3
"""Resolve the highest prior channel versionCode from GitHub release metadata."""

from __future__ import annotations

import argparse
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

try:
    from .release_lib import ReleaseError
except ImportError:
    from release_lib import ReleaseError

TAG_PATTERNS = {
    "stable": re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+$"),
    "preview": re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+-preview(?:\.[0-9]+)?$"),
}


def highest_version_code(
    releases: list[Any],
    channel: str,
    current_tag: str,
    fetch_asset,
    *,
    exclude_existing_current: bool = False,
) -> int:
    pattern = TAG_PATTERNS[channel]
    highest = 0
    current_tag_count = 0
    for release in releases:
        if not isinstance(release, dict):
            raise ReleaseError("GitHub releases response contains a non-object")
        tag = release.get("tag_name")
        if tag == current_tag:
            current_tag_count += 1
            if not exclude_existing_current:
                raise ReleaseError("a GitHub release already exists for the requested tag")
            if current_tag_count > 1:
                raise ReleaseError("GitHub returned duplicate releases for the requested tag")
            continue
        if not isinstance(tag, str) or pattern.fullmatch(tag) is None:
            continue
        assets = release.get("assets")
        if not isinstance(assets, list):
            raise ReleaseError(f"prior {channel} release {tag} has no asset inventory")
        metadata_assets = [asset for asset in assets if isinstance(asset, dict) and asset.get("name") == "release-metadata.json"]
        if len(metadata_assets) != 1:
            raise ReleaseError(f"prior {channel} release {tag} must have exactly one release-metadata.json")
        asset_url = metadata_assets[0].get("url")
        if not isinstance(asset_url, str) or not asset_url.startswith("https://api.github.com/"):
            raise ReleaseError(f"prior {channel} release {tag} has an invalid metadata asset URL")
        metadata = fetch_asset(asset_url)
        if not isinstance(metadata, dict) or metadata.get("channel") != channel or metadata.get("tag") != tag:
            raise ReleaseError(f"prior {channel} release {tag} metadata identity mismatch")
        version_code = metadata.get("versionCode")
        if not isinstance(version_code, int) or version_code <= 0:
            raise ReleaseError(f"prior {channel} release {tag} has invalid versionCode")
        highest = max(highest, version_code)
    if exclude_existing_current and current_tag_count != 1:
        raise ReleaseError("verification-only exclusion requires exactly one existing release for the requested tag")
    return highest


def _request_json(url: str, token: str, *, accept: str = "application/vnd.github+json") -> Any:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": accept,
            "Authorization": f"Bearer {token}",
            "User-Agent": "quicklauncher-release-verifier/1",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise ReleaseError(f"GitHub API returned HTTP {response.status}")
            payload = response.read(4 * 1024 * 1024 + 1)
            if len(payload) > 4 * 1024 * 1024:
                raise ReleaseError("GitHub API response exceeds 4 MiB")
            return json.loads(payload)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        raise ReleaseError(f"could not read GitHub release metadata: {error}") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=sorted(TAG_PATTERNS), required=True)
    parser.add_argument("--repository", default="Costeer/quicklauncher")
    parser.add_argument("--current-tag", required=True)
    parser.add_argument(
        "--exclude-existing-current",
        action="store_true",
        help="verification only: require and exclude the existing release for --current-tag",
    )
    args = parser.parse_args()
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        parser.error("GH_TOKEN is required")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("--repository must be owner/name")
    if TAG_PATTERNS[args.channel].fullmatch(args.current_tag) is None:
        parser.error("--current-tag does not match channel")
    try:
        releases: list[Any] = []
        for page in range(1, 11):
            url = f"https://api.github.com/repos/{args.repository}/releases?per_page=100&page={page}"
            batch = _request_json(url, token)
            if not isinstance(batch, list):
                raise ReleaseError("GitHub releases endpoint did not return an array")
            releases.extend(batch)
            if len(batch) < 100:
                break
        else:
            raise ReleaseError("more than 1,000 releases require an explicit policy update")
        highest = highest_version_code(
            releases,
            args.channel,
            args.current_tag,
            lambda url: _request_json(url, token, accept="application/octet-stream"),
            exclude_existing_current=args.exclude_existing_current,
        )
    except ReleaseError as error:
        print(f"previous version resolution failed: {error}", file=os.sys.stderr)
        return 1
    print(highest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
