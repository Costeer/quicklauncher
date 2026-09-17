# Verification ledger template

Add one row for each release gate. Focused checks can use the same shape when their result will be reused.

| Field | Value |
| --- | --- |
| Acceptance IDs | |
| Worktree fingerprint | `tools/worktree_fingerprint.py` |
| Environment identity | JDK, SDK/API, device serial or `none` |
| Exact command | |
| Started UTC | |
| Duration | |
| Result | pass, fail, or prerequisite unavailable |
| Tasks and tests | counts reported by the tool |
| Failures, errors, skips | |
| Full log | temporary path from `tools/gradle --summary` |
| Why this gate ran | first release-candidate run, or relevant change since prior run |

A prior pass remains usable while the worktree fingerprint, command, and relevant environment identity match. A focused fix invalidates the checks that cover its changed behavior. It does not invalidate unrelated gates.

