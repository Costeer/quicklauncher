#!/usr/bin/env python3
"""Exercise disposable encrypted key backups, independent restores, and v3 lineage."""

from __future__ import annotations

import argparse
import hashlib
import os
import secrets
import subprocess
import tempfile
from pathlib import Path

try:
    from .release_lib import ReleaseError, require_tool, run_checked
except ImportError:
    from release_lib import ReleaseError, require_tool, run_checked


def _certificate_bytes(keytool: str, store: Path, alias: str, env: dict[str, str], password_env: str) -> bytes:
    result = subprocess.run(
        [keytool, "-exportcert", "-keystore", str(store), "-alias", alias, "-storepass:env", password_env],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if result.returncode or not result.stdout:
        raise ReleaseError("could not export disposable public certificate")
    return result.stdout


def _generate(keytool: str, store: Path, alias: str, env: dict[str, str], store_env: str, key_env: str) -> None:
    run_checked(
        [
            keytool, "-genkeypair", "-noprompt", "-keystore", str(store), "-storetype", "JKS",
            "-storepass:env", store_env, "-keypass:env", key_env, "-alias", alias,
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
            "-dname", f"CN=Quicklauncher disposable {alias},O=Quicklauncher test-only,C=ZZ",
        ],
        env=env,
    )


def drill(*, apksigner: str | None = None, keytool: str | None = None, openssl: str | None = None) -> tuple[str, str]:
    signer = require_tool(apksigner, "apksigner")
    keytool_path = require_tool(keytool, "keytool")
    openssl_path = require_tool(openssl, "openssl")
    env = os.environ.copy()
    env.update(
        {
            "QL_DRILL_OLD_STORE": secrets.token_urlsafe(36),
            "QL_DRILL_OLD_KEY": secrets.token_urlsafe(36),
            "QL_DRILL_NEW_STORE": secrets.token_urlsafe(36),
            "QL_DRILL_NEW_KEY": secrets.token_urlsafe(36),
            "QL_DRILL_BACKUP_A": secrets.token_urlsafe(48),
            "QL_DRILL_BACKUP_B": secrets.token_urlsafe(48),
        }
    )
    temporary_path: Path | None = None
    try:
        with tempfile.TemporaryDirectory(prefix="quicklauncher-key-recovery-") as temporary:
            temporary_path = Path(temporary)
            working = temporary_path / "working"
            backup_a = temporary_path / "custodian-a"
            backup_b = temporary_path / "custodian-b"
            rotation = temporary_path / "rotation"
            for directory in (working, backup_a, backup_b, rotation):
                directory.mkdir(mode=0o700)
            old_key = working / "old.jks"
            new_key = rotation / "new.jks"
            _generate(keytool_path, old_key, "old", env, "QL_DRILL_OLD_STORE", "QL_DRILL_OLD_KEY")
            _generate(keytool_path, new_key, "new", env, "QL_DRILL_NEW_STORE", "QL_DRILL_NEW_KEY")
            old_cert = _certificate_bytes(keytool_path, old_key, "old", env, "QL_DRILL_OLD_STORE")
            new_cert = _certificate_bytes(keytool_path, new_key, "new", env, "QL_DRILL_NEW_STORE")
            old_fingerprint = hashlib.sha256(old_cert).hexdigest()
            new_fingerprint = hashlib.sha256(new_cert).hexdigest()
            for destination, password_env in ((backup_a / "old.jks.enc", "QL_DRILL_BACKUP_A"), (backup_b / "old.jks.enc", "QL_DRILL_BACKUP_B")):
                run_checked(
                    [openssl_path, "enc", "-aes-256-cbc", "-pbkdf2", "-salt", "-in", str(old_key), "-out", str(destination), "-pass", f"env:{password_env}"],
                    env=env,
                )
            old_key.unlink()
            if old_key.exists():
                raise ReleaseError("working key deletion failed")
            for source, password_env in ((backup_a / "old.jks.enc", "QL_DRILL_BACKUP_A"), (backup_b / "old.jks.enc", "QL_DRILL_BACKUP_B")):
                restored = working / "restored.jks"
                run_checked(
                    [openssl_path, "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-in", str(source), "-out", str(restored), "-pass", f"env:{password_env}"],
                    env=env,
                )
                restored_cert = _certificate_bytes(keytool_path, restored, "old", env, "QL_DRILL_OLD_STORE")
                if hashlib.sha256(restored_cert).hexdigest() != old_fingerprint:
                    raise ReleaseError("independent backup restored a different public certificate")
                restored.unlink()
            restored_for_rotation = working / "old.jks"
            run_checked(
                [openssl_path, "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-in", str(backup_a / "old.jks.enc"), "-out", str(restored_for_rotation), "-pass", "env:QL_DRILL_BACKUP_A"],
                env=env,
            )
            lineage = rotation / "signing-lineage.bin"
            run_checked(
                [
                    signer, "rotate", "--out", str(lineage),
                    "--old-signer", "--ks", str(restored_for_rotation), "--ks-key-alias", "old",
                    "--ks-pass", "env:QL_DRILL_OLD_STORE", "--key-pass", "env:QL_DRILL_OLD_KEY",
                    "--new-signer", "--ks", str(new_key), "--ks-key-alias", "new",
                    "--ks-pass", "env:QL_DRILL_NEW_STORE", "--key-pass", "env:QL_DRILL_NEW_KEY",
                ],
                env=env,
            )
            if not lineage.is_file() or lineage.stat().st_size == 0:
                raise ReleaseError("apksigner rotate did not create a v3 lineage")
        if temporary_path.exists():
            raise ReleaseError("temporary recovery material was not removed")
    finally:
        for name in tuple(env):
            if name.startswith("QL_DRILL_"):
                env[name] = ""
                del env[name]
    return old_fingerprint, new_fingerprint


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner")
    parser.add_argument("--keytool")
    parser.add_argument("--openssl")
    args = parser.parse_args()
    try:
        old_fingerprint, new_fingerprint = drill(apksigner=args.apksigner, keytool=args.keytool, openssl=args.openssl)
    except ReleaseError as error:
        print(f"key recovery drill failed: {error}", file=os.sys.stderr)
        return 1
    print(f"Disposable original certificate SHA-256: {old_fingerprint}")
    print(f"Disposable rotation certificate SHA-256: {new_fingerprint}")
    print("Key recovery drill passed: two encrypted backups restored independently, v3 lineage created, plaintext cleaned")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
