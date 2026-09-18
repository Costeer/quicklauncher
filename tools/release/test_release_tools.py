from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

try:
    from .release_lib import ReleaseError, require_non_debuggable_badging, verify_checksum_file
    from .previous_version_code import highest_version_code
    from .validate_channels import ROOT, SAMPLES, SOURCE_CONTRACT_PATH, _validate_source_contract, validate as validate_channels
    from .validate_release_assets import validate_assets, validate_downloaded_files
    from .validate_workflows import validate as validate_workflows, validate_text
    from .verify_release import _download, verify_metadata, verify_provenance
except ImportError:
    from release_lib import ReleaseError, require_non_debuggable_badging, verify_checksum_file
    from previous_version_code import highest_version_code
    from validate_channels import ROOT, SAMPLES, SOURCE_CONTRACT_PATH, _validate_source_contract, validate as validate_channels
    from validate_release_assets import validate_assets, validate_downloaded_files
    from validate_workflows import validate as validate_workflows, validate_text
    from verify_release import _download, verify_metadata, verify_provenance


def valid_metadata(channel: str = "stable") -> dict:
    if channel == "stable":
        version_name = "1.2.3"
        asset = "quicklauncher-stable-v1.2.3-universal.apk"
        application_id = "org.quicklauncher"
    else:
        version_name = "1.2.3-preview.4"
        asset = "quicklauncher-preview-v1.2.3-preview.4-universal.apk"
        application_id = "org.quicklauncher.preview"
    return {
        "schemaVersion": 1,
        "channel": channel,
        "tag": f"v{version_name}",
        "sourceRepository": "Costeer/quicklauncher",
        "sourceRef": f"refs/tags/v{version_name}",
        "sourceCommit": "0123456789abcdef0123456789abcdef01234567",
        "workflow": f".github/workflows/release-{channel}.yml",
        "assetName": asset,
        "applicationId": application_id,
        "versionCode": 9,
        "previousVersionCode": 8,
        "versionName": version_name,
        "minSdk": 35,
        "certificateSha256": "a" * 64,
        "apkSha256": "b" * 64,
        "signatureSchemes": ["v3"],
    }


class MetadataTests(unittest.TestCase):
    def test_stable_and_preview_metadata_pass(self) -> None:
        for channel in ("stable", "preview"):
            metadata = valid_metadata(channel)
            verify_metadata(
                metadata,
                channel=channel,
                tag=metadata["tag"],
                expected_certificate="a" * 64,
                previous_version_code=8,
            )

    def test_cross_channel_asset_fails(self) -> None:
        metadata = valid_metadata("stable")
        metadata["assetName"] = SAMPLES["preview"]["asset"]
        with self.assertRaises(ReleaseError):
            verify_metadata(metadata, channel="stable", tag=metadata["tag"], expected_certificate="a" * 64, previous_version_code=8)

    def test_non_monotonic_version_fails(self) -> None:
        metadata = valid_metadata()
        metadata["versionCode"] = 8
        with self.assertRaises(ReleaseError):
            verify_metadata(metadata, channel="stable", tag=metadata["tag"], expected_certificate="a" * 64, previous_version_code=8)

    def test_extra_metadata_field_fails(self) -> None:
        metadata = valid_metadata()
        metadata["unsignedDigest"] = "ignored"
        with self.assertRaises(ReleaseError):
            verify_metadata(metadata, channel="stable", tag=metadata["tag"], expected_certificate="a" * 64, previous_version_code=8)

    def test_missing_v3_fails(self) -> None:
        metadata = valid_metadata()
        metadata["signatureSchemes"] = []
        with self.assertRaises(ReleaseError):
            verify_metadata(metadata, channel="stable", tag=metadata["tag"], expected_certificate="a" * 64, previous_version_code=8)


class ProvenanceTests(unittest.TestCase):
    def statement(self) -> list[dict]:
        fixture = ROOT / "tools/release/fixtures/github-attestation-verification.json"
        return copy.deepcopy(json.loads(fixture.read_text(encoding="utf-8")))

    def write(self, value: object, root: Path) -> Path:
        path = root / "provenance.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_verified_statement_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            verify_provenance(self.write(self.statement(), Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-stable.yml")

    def test_wrong_subject_digest_fails(self) -> None:
        statement = self.statement()
        statement[0]["verificationResult"]["statement"]["subject"][0]["digest"]["sha256"] = "c" * 64
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(statement, Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-stable.yml")

    def test_wrong_tag_ref_fails(self) -> None:
        statement = self.statement()
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(statement, Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v9.9.9", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-stable.yml")

    def test_wrong_source_commit_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(self.statement(), Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="f" * 40, workflow=".github/workflows/release-stable.yml")

    def test_wrong_workflow_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(self.statement(), Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-preview.yml")

    def test_expected_markers_in_untrusted_wrong_fields_fail(self) -> None:
        statement = self.statement()
        build = statement[0]["verificationResult"]["statement"]["predicate"]["buildDefinition"]
        build["externalParameters"]["workflow"]["path"] = ".github/workflows/other.yml"
        build["internalParameters"]["lookalike"] = ".github/workflows/release-stable.yml"
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(statement, Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-stable.yml")

    def test_wrong_certificate_source_digest_fails(self) -> None:
        statement = self.statement()
        statement[0]["verificationResult"]["signature"]["certificate"]["sourceRepositoryDigest"] = "f" * 40
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(statement, Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-stable.yml")

    def test_multiple_verified_attestations_fail(self) -> None:
        statement = self.statement()
        statement.append(copy.deepcopy(statement[0]))
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            verify_provenance(self.write(statement, Path(temporary)), asset_name="quicklauncher-stable-v1.2.3-universal.apk", digest="b" * 64, repository="Costeer/quicklauncher", source_ref="refs/tags/v1.2.3", source_commit="0123456789abcdef0123456789abcdef01234567", workflow=".github/workflows/release-stable.yml")


class ReleaseAssetTests(unittest.TestCase):
    def test_one_apk_and_supporting_assets_pass(self) -> None:
        metadata = valid_metadata()
        assets = [{"name": metadata["assetName"]}, {"name": "release-metadata.json"}, {"name": f"{metadata['assetName']}.sha256"}]
        validate_assets(assets, metadata)

    def test_second_apk_fails(self) -> None:
        metadata = valid_metadata()
        assets = [{"name": metadata["assetName"]}, {"name": "other.apk"}, {"name": "release-metadata.json"}]
        with self.assertRaises(ReleaseError):
            validate_assets(assets, metadata)

    def test_missing_checksum_asset_fails(self) -> None:
        metadata = valid_metadata()
        with self.assertRaises(ReleaseError):
            validate_assets([{"name": metadata["assetName"]}, {"name": "release-metadata.json"}], metadata)

    def test_checksum_syntax_name_and_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "example.apk"
            apk.write_bytes(b"signed bytes")
            import hashlib
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            checksum = root / "example.apk.sha256"
            checksum.write_text(f"{digest}  example.apk\n", encoding="ascii")
            verify_checksum_file(checksum, apk, digest)
            checksum.write_text(f"{digest} *example.apk\n", encoding="ascii")
            with self.assertRaises(ReleaseError):
                verify_checksum_file(checksum, apk, digest)

    def test_download_directory_requires_exact_three_files(self) -> None:
        metadata = valid_metadata()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / metadata["assetName"]
            apk.write_bytes(b"signed bytes")
            import hashlib
            metadata["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
            checksum = root / f"{apk.name}.sha256"
            checksum.write_text(f"{metadata['apkSha256']}  {apk.name}\n", encoding="ascii")
            (root / "release-metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            validate_downloaded_files(root, apk, checksum, metadata)
            (root / "unexpected.txt").write_text("unexpected", encoding="utf-8")
            with self.assertRaises(ReleaseError):
                validate_downloaded_files(root, apk, checksum, metadata)


class DownloadAndApkPolicyTests(unittest.TestCase):
    def test_http_requires_explicit_test_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ReleaseError):
            _download("http://127.0.0.1/example.apk", Path(temporary) / "example.apk")

    def test_debuggable_badging_fails(self) -> None:
        with self.assertRaises(ReleaseError):
            require_non_debuggable_badging("package: name='org.quicklauncher'\napplication-debuggable\n")

    def test_release_badging_passes(self) -> None:
        require_non_debuggable_badging("package: name='org.quicklauncher'\napplication-label:'Quicklauncher'\n")


class CheckedPolicyTests(unittest.TestCase):
    def test_repository_channel_metadata(self) -> None:
        validate_channels(ROOT)

    def test_obtainium_source_hash_tamper_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / SOURCE_CONTRACT_PATH
            target.parent.mkdir(parents=True)
            contract = json.loads((ROOT / SOURCE_CONTRACT_PATH).read_text(encoding="utf-8"))
            contract["sources"][0]["sha256"] = "0" * 64
            target.write_text(json.dumps(contract), encoding="utf-8")
            with self.assertRaises(ReleaseError):
                _validate_source_contract(root, SOURCE_CONTRACT_PATH)

    def test_repository_workflows(self) -> None:
        validate_workflows(ROOT)

    def test_pull_request_release_path_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("  workflow_dispatch:", "  pull_request:\n  workflow_dispatch:"), "stable")

    def test_mutable_action_reference_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6", "actions/attest@v4"), "stable")

    def test_other_channel_secret_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text + "\n# QUICKLAUNCHER_PREVIEW_KEYSTORE_B64\n", "stable")

    def test_tag_specific_concurrency_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("group: release-stable\n", "group: release-stable-${{ inputs.tag }}\n"), "stable")

    def test_downstream_tag_checkout_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("ref: ${{ needs.preflight.outputs.commit }}", "ref: ${{ format('refs/tags/{0}', inputs.tag) }}", 1), "stable")

    def test_missing_phase_nine_performance_gate_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("            checkPhase9Performance \\\n", "", 1), "stable")

    def test_incomplete_benchmark_license_audit_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("            --resolved benchmark/macrobenchmark/build/reports/licenses/stable-benchmark-dependencies.txt \\\n", "", 1), "stable")

    def test_missing_release_workflow_actionlint_fails(self) -> None:
        text = (ROOT / ".github/workflows/release-stable.yml").read_text(encoding="utf-8")
        with self.assertRaises(ReleaseError):
            validate_text(text.replace("          GOBIN=\"$RUNNER_TEMP/actionlint-bin\" go install github.com/rhysd/actionlint/cmd/actionlint@v1.7.7\n", "", 1), "stable")


class PreviousVersionTests(unittest.TestCase):
    def test_highest_matching_channel_version_is_used(self) -> None:
        releases = [
            {"tag_name": "v1.0.0", "assets": [{"name": "release-metadata.json", "url": "https://api.github.com/assets/1"}]},
            {"tag_name": "v1.1.0", "assets": [{"name": "release-metadata.json", "url": "https://api.github.com/assets/2"}]},
            {"tag_name": "v1.2.0-preview.1", "assets": []},
        ]
        metadata = {
            "https://api.github.com/assets/1": {"channel": "stable", "tag": "v1.0.0", "versionCode": 10},
            "https://api.github.com/assets/2": {"channel": "stable", "tag": "v1.1.0", "versionCode": 12},
        }
        self.assertEqual(highest_version_code(releases, "stable", "v1.2.0", metadata.__getitem__), 12)

    def test_prior_channel_release_without_metadata_fails(self) -> None:
        releases = [{"tag_name": "v1.0.0", "assets": []}]
        with self.assertRaises(ReleaseError):
            highest_version_code(releases, "stable", "v1.1.0", lambda _: {})

    def test_existing_current_release_fails(self) -> None:
        releases = [{"tag_name": "v1.1.0", "assets": []}]
        with self.assertRaises(ReleaseError):
            highest_version_code(releases, "stable", "v1.1.0", lambda _: {})

    def test_verification_excludes_existing_current_release(self) -> None:
        releases = [
            {"tag_name": "v1.0.0", "assets": [{"name": "release-metadata.json", "url": "https://api.github.com/assets/1"}]},
            {"tag_name": "v1.1.0", "assets": []},
        ]
        metadata = {
            "https://api.github.com/assets/1": {"channel": "stable", "tag": "v1.0.0", "versionCode": 10},
        }
        self.assertEqual(
            highest_version_code(
                releases,
                "stable",
                "v1.1.0",
                metadata.__getitem__,
                exclude_existing_current=True,
            ),
            10,
        )

    def test_verification_exclusion_requires_existing_current_release(self) -> None:
        with self.assertRaises(ReleaseError):
            highest_version_code(
                [],
                "stable",
                "v1.1.0",
                lambda _: {},
                exclude_existing_current=True,
            )

    def test_verification_exclusion_rejects_duplicate_current_release(self) -> None:
        releases = [
            {"tag_name": "v1.1.0", "assets": []},
            {"tag_name": "v1.1.0", "assets": []},
        ]
        with self.assertRaises(ReleaseError):
            highest_version_code(
                releases,
                "stable",
                "v1.1.0",
                lambda _: {},
                exclude_existing_current=True,
            )


if __name__ == "__main__":
    unittest.main()
