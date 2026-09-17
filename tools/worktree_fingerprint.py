#!/usr/bin/env python3
"""Hash the repository state used by a verification command."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
from pathlib import Path


def git(repo: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(repo), *args])


def fingerprint(repo: Path) -> str:
    repo = repo.resolve()
    digest = hashlib.sha256()
    digest.update(b"HEAD\0")
    digest.update(git(repo, "rev-parse", "HEAD").strip())
    digest.update(b"\0TRACKED\0")
    digest.update(git(repo, "diff", "--binary", "HEAD", "--"))

    untracked = git(
        repo, "ls-files", "--others", "--exclude-standard", "-z"
    ).split(b"\0")
    for raw_path in sorted(path for path in untracked if path):
        relative = raw_path.decode("utf-8", errors="surrogateescape")
        path = repo / relative
        digest.update(b"\0UNTRACKED\0")
        digest.update(raw_path)
        digest.update(b"\0")
        if path.is_file():
            with path.open("rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
        elif path.is_symlink():
            digest.update(path.readlink().as_posix().encode())
        else:
            digest.update(b"<non-file>")
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    args = parser.parse_args()
    print(fingerprint(args.repo))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

