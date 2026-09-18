#!/usr/bin/env python3
"""Shared, dependency-free release artifact inspection helpers."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


class ReleaseError(RuntimeError):
    """A release contract was not satisfied."""


@dataclass(frozen=True)
class ApkIdentity:
    application_id: str
    version_code: int
    version_name: str
    min_sdk: int
    certificate_sha256: str
    schemes: tuple[str, ...]
    sha256: str


def run_checked(command: list[str], *, env: dict[str, str] | None = None) -> str:
    try:
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=env,
        )
    except OSError as error:
        raise ReleaseError(f"could not execute {command[0]!r}: {error}") from error
    if result.returncode:
        safe_command = " ".join(Path(part).name if index == 0 else part for index, part in enumerate(command))
        raise ReleaseError(f"command failed ({safe_command}):\n{result.stdout.strip()}")
    return result.stdout


def require_tool(explicit: str | None, name: str) -> str:
    candidate = explicit or shutil.which(name)
    if not candidate or not Path(candidate).is_file():
        raise ReleaseError(f"required tool not found: {name}")
    return str(Path(candidate).resolve())


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_checksum_file(checksum_path: Path, apk: Path, expected_digest: str) -> None:
    try:
        payload = checksum_path.read_bytes()
    except OSError as error:
        raise ReleaseError(f"could not read checksum file: {error}") from error
    if len(payload) > 512 or not payload.endswith(b"\n") or b"\r" in payload or payload.count(b"\n") != 1:
        raise ReleaseError("checksum file must be one bounded LF-terminated line")
    try:
        line = payload[:-1].decode("ascii")
    except UnicodeDecodeError as error:
        raise ReleaseError("checksum file must be ASCII") from error
    match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*\.apk)", line)
    if not match:
        raise ReleaseError("checksum file must contain lowercase SHA-256, two spaces, and APK filename")
    digest, name = match.groups()
    if name != apk.name:
        raise ReleaseError("checksum filename does not match the APK")
    if digest != expected_digest or sha256_file(apk) != digest:
        raise ReleaseError("checksum digest does not match metadata and APK bytes")


def _single(pattern: str, text: str, label: str) -> str:
    values = re.findall(pattern, text, re.MULTILINE)
    if len(values) != 1:
        raise ReleaseError(f"expected exactly one {label}, found {len(values)}")
    return values[0]


def require_non_debuggable_badging(badging: str) -> None:
    if re.search(r"^application-debuggable\b", badging, re.MULTILINE):
        raise ReleaseError("release APK must not be debuggable")


def inspect_apk(apk: Path, *, aapt: str | None = None, apksigner: str | None = None) -> ApkIdentity:
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise ReleaseError(f"APK does not exist or has the wrong suffix: {apk}")
    aapt_path = require_tool(aapt, "aapt")
    signer_path = require_tool(apksigner, "apksigner")
    badging = run_checked([aapt_path, "dump", "badging", str(apk)])
    require_non_debuggable_badging(badging)
    package_line = _single(r"^package:\s+(.+)$", badging, "package line")
    application_id = _single(r"\bname='([^']+)'", package_line, "application ID")
    version_code_text = _single(r"\bversionCode='([0-9]+)'", package_line, "version code")
    version_name = _single(r"\bversionName='([^']+)'", package_line, "version name")
    min_sdk = int(_single(r"^sdkVersion:'([0-9]+)'$", badging, "minimum SDK"))

    verification = run_checked([signer_path, "verify", "--verbose", "--print-certs", "--Werr", str(apk)])
    certificate_sha256 = _single(
        r"^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
        verification,
        "signer certificate SHA-256",
    ).lower()
    if "Signer #2 certificate" in verification:
        raise ReleaseError("exactly one APK signer is required")
    schemes: list[str] = []
    for scheme, label in (("v1", "v1 scheme (JAR signing)"), ("v2", "v2 scheme (APK Signature Scheme v2)"), ("v3", "v3 scheme (APK Signature Scheme v3)"), ("v4", "v4 scheme (APK Signature Scheme v4)")):
        match = re.search(rf"^Verified using {re.escape(label)}:\s*(true|false)\s*$", verification, re.MULTILINE)
        if match and match.group(1) == "true":
            schemes.append(scheme)
    if not schemes:
        raise ReleaseError("no APK signature scheme was reported as verified")
    return ApkIdentity(
        application_id=application_id,
        version_code=int(version_code_text),
        version_name=version_name,
        min_sdk=min_sdk,
        certificate_sha256=certificate_sha256,
        schemes=tuple(schemes),
        sha256=sha256_file(apk),
    )


def load_json(path: Path) -> Any:
    try:
        with path.open("r", encoding="utf-8") as stream:
            return json.load(stream)
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"invalid JSON at {path}: {error}") from error


def require_exact_keys(value: dict[str, Any], required: Iterable[str], label: str) -> None:
    required_set = set(required)
    actual = set(value)
    if actual != required_set:
        missing = sorted(required_set - actual)
        extra = sorted(actual - required_set)
        raise ReleaseError(f"{label} keys differ; missing={missing}, unexpected={extra}")
