import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location("audit", ROOT / "audit_benchmark_dependencies.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class BenchmarkDependencyAuditTest(unittest.TestCase):
    def write(self, directory, name, value):
        path = Path(directory) / name
        path.write_text(value, encoding="utf-8")
        return path

    def pom(self, directory, coordinate, license_name):
        group, module, version = coordinate.split(":")
        path = Path(directory) / group / module / version / "hash" / f"{module}.pom"
        path.parent.mkdir(parents=True)
        path.write_text(
            f"<project><licenses><license><name>{license_name}</name></license></licenses></project>",
            encoding="utf-8",
        )

    def test_accepts_exact_approved_graph(self):
        with tempfile.TemporaryDirectory() as directory:
            resolved = self.write(directory, "resolved", "a:b:1\n")
            allowed = self.write(
                directory,
                "allowed",
                "a:b:1|Apache-2.0|https://repo1.maven.org/maven2\n",
            )
            cache = Path(directory) / "cache"
            self.pom(cache, "a:b:1", "The Apache Software License, Version 2.0")
            self.assertEqual(1, AUDIT.audit(resolved, allowed, cache))

    def test_rejects_unreviewed_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            resolved = self.write(directory, "resolved", "a:b:1\nc:d:2\n")
            allowed = self.write(directory, "allowed", "a:b:1|Apache-2.0|https://repo1.maven.org/maven2\n")
            with self.assertRaisesRegex(ValueError, "missing=.*c:d:2"):
                AUDIT.audit(resolved, allowed, Path(directory) / "cache")

    def test_rejects_unapproved_license(self):
        with tempfile.TemporaryDirectory() as directory:
            resolved = self.write(directory, "resolved", "a:b:1\n")
            allowed = self.write(directory, "allowed", "a:b:1|Proprietary|https://repo1.maven.org/maven2\n")
            with self.assertRaisesRegex(ValueError, "unapproved SPDX"):
                AUDIT.audit(resolved, allowed, Path(directory) / "cache")

    def test_rejects_missing_source_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            resolved = self.write(directory, "resolved", "a:b:1\n")
            allowed = self.write(directory, "allowed", "a:b:1|Apache-2.0\n")
            with self.assertRaisesRegex(ValueError, "invalid allowlist"):
                AUDIT.audit(resolved, allowed, Path(directory) / "cache")

    def test_rejects_cached_pom_license_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            resolved = self.write(directory, "resolved", "a:b:1\n")
            allowed = self.write(
                directory,
                "allowed",
                "a:b:1|Apache-2.0|https://repo1.maven.org/maven2\n",
            )
            cache = Path(directory) / "cache"
            self.pom(cache, "a:b:1", "Some Other License")
            with self.assertRaisesRegex(ValueError, "does not support"):
                AUDIT.audit(resolved, allowed, cache)


if __name__ == "__main__":
    unittest.main()
