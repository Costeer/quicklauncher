#!/usr/bin/env python3
"""Sign an unsigned APK with a disposable key and verify it locally and over HTTP."""

from __future__ import annotations

import argparse
import functools
import http.server
import json
import os
import secrets
import socketserver
import subprocess
import tempfile
import threading
from pathlib import Path

try:
    from .release_lib import ReleaseError, inspect_apk, require_tool, run_checked
    from .verify_release import _download, verify_release
except ImportError:
    from release_lib import ReleaseError, inspect_apk, require_tool, run_checked
    from verify_release import _download, verify_release


def _create_metadata(apk: Path, channel: str, commit: str, certificate: str, aapt: str, apksigner: str, output: Path) -> dict:
    identity = inspect_apk(apk, aapt=aapt, apksigner=apksigner)
    tag = f"v{identity.version_name}"
    metadata = {
        "schemaVersion": 1,
        "channel": channel,
        "tag": tag,
        "sourceRepository": "Costeer/quicklauncher",
        "sourceRef": f"refs/tags/{tag}",
        "sourceCommit": commit,
        "workflow": f".github/workflows/release-{channel}.yml",
        "assetName": apk.name,
        "applicationId": identity.application_id,
        "versionCode": identity.version_code,
        "previousVersionCode": max(0, identity.version_code - 1),
        "versionName": identity.version_name,
        "minSdk": identity.min_sdk,
        "certificateSha256": certificate,
        "apkSha256": identity.sha256,
        "signatureSchemes": list(identity.schemes),
    }
    output.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return metadata


def drill(unsigned_apk: Path, *, channel: str, aapt: str | None, apksigner: str | None, zipalign: str | None, keytool: str | None) -> str:
    aapt_path = require_tool(aapt, "aapt")
    signer_path = require_tool(apksigner, "apksigner")
    aligner_path = require_tool(zipalign, "zipalign")
    keytool_path = require_tool(keytool, "keytool")
    if not unsigned_apk.is_file():
        raise ReleaseError("unsigned APK does not exist")
    env = os.environ.copy()
    env["QL_DUMMY_STORE_PASSWORD"] = secrets.token_urlsafe(36)
    env["QL_DUMMY_KEY_PASSWORD"] = secrets.token_urlsafe(36)
    with tempfile.TemporaryDirectory(prefix=f"quicklauncher-{channel}-dummy-") as temporary:
        root = Path(temporary)
        store = root / "dummy.jks"
        run_checked(
            [
                keytool_path, "-genkeypair", "-noprompt", "-storetype", "JKS", "-keystore", str(store),
                "-storepass:env", "QL_DUMMY_STORE_PASSWORD", "-keypass:env", "QL_DUMMY_KEY_PASSWORD",
                "-alias", "dummy", "-keyalg", "RSA", "-keysize", "3072", "-validity", "30",
                "-dname", f"CN=Quicklauncher {channel} dummy,O=Quicklauncher test-only,C=ZZ",
            ],
            env=env,
        )
        aligned = root / "aligned.apk"
        run_checked([aligner_path, "-f", "-p", "4", str(unsigned_apk), str(aligned)])
        unsigned_identity = run_checked([aapt_path, "dump", "badging", str(unsigned_apk)])
        version_name_match = __import__("re").search(r"versionName='([^']+)'", unsigned_identity)
        if not version_name_match:
            raise ReleaseError("unsigned APK has no versionName")
        version_name = version_name_match.group(1)
        tag = f"v{version_name}"
        asset = root / f"quicklauncher-{channel}-{tag}-universal.apk"
        run_checked(
            [
                signer_path, "sign", "--ks", str(store), "--ks-key-alias", "dummy",
                "--ks-pass", "env:QL_DUMMY_STORE_PASSWORD", "--key-pass", "env:QL_DUMMY_KEY_PASSWORD",
                "--min-sdk-version", "35", "--debuggable-apk-permitted", "false",
                "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
                "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                "--out", str(asset), str(aligned),
            ],
            env=env,
        )
        identity = inspect_apk(asset, aapt=aapt_path, apksigner=signer_path)
        metadata_path = root / "release-metadata.json"
        commit = "0123456789abcdef0123456789abcdef01234567"
        metadata = _create_metadata(asset, channel, commit, identity.certificate_sha256, aapt_path, signer_path, metadata_path)
        checksum_path = root / f"{asset.name}.sha256"
        checksum_path.write_text(f"{identity.sha256}  {asset.name}\n", encoding="ascii")
        provenance_path = root / "provenance.json"
        repository_url = "https://github.com/Costeer/quicklauncher"
        signer_identity = f"{repository_url}/{metadata['workflow']}@{metadata['sourceRef']}"
        provenance = [
            {
                "attestation": {"bundle": {"mediaType": "application/vnd.dev.sigstore.bundle.v0.3+json"}},
                "verificationResult": {
                    "signature": {
                        "certificate": {
                            "issuer": "https://token.actions.githubusercontent.com",
                            "subjectAlternativeName": signer_identity,
                            "buildSignerURI": signer_identity,
                            "buildSignerDigest": commit,
                            "runnerEnvironment": "github-hosted",
                            "sourceRepositoryURI": repository_url,
                            "sourceRepositoryDigest": commit,
                            "sourceRepositoryRef": metadata["sourceRef"],
                        }
                    },
                    "verifiedTimestamps": [{"type": "transparency-log", "uri": "test-only"}],
                    "statement": {
                        "_type": "https://in-toto.io/Statement/v1",
                        "predicateType": "https://slsa.dev/provenance/v1",
                        "subject": [{"name": asset.name, "digest": {"sha256": identity.sha256}}],
                        "predicate": {
                            "buildDefinition": {
                                "buildType": "https://actions.github.io/buildtypes/workflow/v1",
                                "externalParameters": {
                                    "workflow": {
                                        "repository": repository_url,
                                        "ref": metadata["sourceRef"],
                                        "path": metadata["workflow"],
                                    }
                                },
                                "internalParameters": {"github": {"event_name": "workflow_dispatch"}},
                                "resolvedDependencies": [{
                                    "uri": f"git+{repository_url}@{metadata['sourceRef']}",
                                    "digest": {"gitCommit": commit},
                                }],
                            },
                            "runDetails": {
                                "builder": {"id": signer_identity},
                                "metadata": {"invocationId": f"{repository_url}/actions/runs/1/attempts/1"},
                            },
                        },
                    }
                }
            }
        ]
        provenance_path.write_text(json.dumps(provenance), encoding="utf-8")
        common = {
            "channel": channel,
            "tag": tag,
            "expected_certificate": identity.certificate_sha256,
            "previous_version_code": metadata["previousVersionCode"],
            "aapt": aapt_path,
            "apksigner": signer_path,
            "expected_source_commit": commit,
        }
        verify_release(asset, checksum_path, metadata_path, provenance_path, **common)
        class QuietServer(socketserver.TCPServer):
            allow_reuse_address = False

        class QuietHandler(http.server.SimpleHTTPRequestHandler):
            def log_message(self, format: str, *args: object) -> None:
                return

        handler = functools.partial(QuietHandler, directory=str(root))
        # HTTP download behavior is exercised by copying the served bytes and verifying them;
        # the production CLI uses the same bounded downloader before verify_release.
        with QuietServer(("127.0.0.1", 0), handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            downloaded = root / "download" / asset.name
            downloaded.parent.mkdir()
            _download(f"http://127.0.0.1:{server.server_address[1]}/{asset.name}", downloaded, allow_test_http=True)
            server.shutdown()
            thread.join(timeout=10)
        verify_release(downloaded, checksum_path, metadata_path, provenance_path, **common)
        fingerprint = identity.certificate_sha256
    env["QL_DUMMY_STORE_PASSWORD"] = ""
    env["QL_DUMMY_KEY_PASSWORD"] = ""
    return fingerprint


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unsigned-apk", type=Path, required=True)
    parser.add_argument("--channel", choices=("stable", "preview"), required=True)
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    parser.add_argument("--zipalign")
    parser.add_argument("--keytool")
    args = parser.parse_args()
    try:
        fingerprint = drill(
            args.unsigned_apk,
            channel=args.channel,
            aapt=args.aapt,
            apksigner=args.apksigner,
            zipalign=args.zipalign,
            keytool=args.keytool,
        )
    except ReleaseError as error:
        print(f"dummy release drill failed: {error}", file=os.sys.stderr)
        return 1
    print(f"Disposable {args.channel} certificate SHA-256: {fingerprint}")
    print("Dummy release drill passed: signed bytes verified locally and after HTTP download")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
