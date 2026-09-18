#!/usr/bin/env python3
"""Checks the resolved benchmark graph against an exact coordinate/license allowlist."""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ALLOWED_LICENSES = {"Apache-2.0", "BSD-3-Clause", "EPL-1.0"}
EVIDENCE_REPOSITORIES = {
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
}
COORDINATE = re.compile(r"[^:\s]+:[^:\s]+:[^:\s]+")


def read_lines(path):
    try:
        return [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.lstrip().startswith("#")]
    except OSError as error:
        raise ValueError(f"cannot read {path}: {error}") from error


def pom_supports_license(pom_path, spdx):
    try:
        root = ET.parse(pom_path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"cannot read cached POM {pom_path}: {error}") from error
    licenses = [
        element
        for element in root.iter()
        if element.tag.rsplit("}", 1)[-1] == "license"
    ]
    if not licenses:
        return None
    evidence = " ".join(
        child.text or ""
        for license_element in licenses
        for child in license_element.iter()
        if child.tag.rsplit("}", 1)[-1] in {"name", "url"}
    ).lower()
    markers = {
        "Apache-2.0": ("apache", "license-2.0"),
        "BSD-3-Clause": ("bsd",),
        "EPL-1.0": ("eclipse public license 1.0", "epl-v10"),
    }[spdx]
    return any(marker in evidence for marker in markers)


def audit(resolved_path, allowlist_path, cache_root=None):
    resolved = set(read_lines(resolved_path))
    if not resolved:
        raise ValueError("resolved dependency inventory is empty")
    entries = {}
    for line in read_lines(allowlist_path):
        parts = line.split("|")
        if len(parts) != 3 or COORDINATE.fullmatch(parts[0]) is None:
            raise ValueError(f"invalid allowlist entry: {line}")
        if parts[1] not in ALLOWED_LICENSES:
            raise ValueError(f"unapproved SPDX license: {parts[1]}")
        if parts[2] not in EVIDENCE_REPOSITORIES:
            raise ValueError(f"unapproved license evidence source: {parts[2]}")
        expected_repository = (
            "https://dl.google.com/dl/android/maven2"
            if parts[0].startswith("androidx.")
            else "https://repo1.maven.org/maven2"
        )
        if parts[2] != expected_repository:
            raise ValueError(f"wrong evidence repository for {parts[0]}")
        if parts[0] in entries:
            raise ValueError(f"duplicate allowlist coordinate: {parts[0]}")
        entries[parts[0]] = (parts[1], parts[2])
    missing = sorted(resolved - entries.keys())
    stale = sorted(entries.keys() - resolved)
    if missing or stale:
        raise ValueError(f"dependency allowlist mismatch; missing={missing}, stale={stale}")
    cache = Path(cache_root or Path.home() / ".gradle/caches/modules-2/files-2.1")
    for coordinate, (spdx, _) in entries.items():
        group, module, version = coordinate.split(":")
        poms = list((cache / group / module / version).glob("*/*.pom"))
        if not poms:
            raise ValueError(f"cached POM license evidence missing for {coordinate}")
        pom_findings = [pom_supports_license(pom, spdx) for pom in poms]
        if any(finding is not None for finding in pom_findings) and not any(pom_findings):
            raise ValueError(f"cached POM does not support {spdx} for {coordinate}")
    return len(resolved)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resolved", required=True)
    parser.add_argument("--allowlist", required=True)
    args = parser.parse_args()
    try:
        count = audit(args.resolved, args.allowlist)
    except ValueError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {count} exact benchmark dependencies have approved SPDX licenses")
    return 0


if __name__ == "__main__":
    sys.exit(main())
