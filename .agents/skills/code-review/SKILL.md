---
name: code-review
description: "Review changes since a fixed point along two axes: documented standards and the originating spec. Delegates the axes to depth-one agents and reports them separately. Use for a branch, PR, work-in-progress change, or a request to review since a ref."
---

# Two-axis code review

Review the diff between `HEAD` and a fixed point supplied by the user:

- **Standards:** conformance with repository documentation and the smell baseline.
- **Spec:** conformance with the originating issue or spec.

Read `docs/agents/execution.md` before starting. Create one depth-one worker for each applicable axis. They do not spawn more agents. Run both axes once for a release candidate. A post-fix review reruns the affected axis unless shared contracts or the review boundary changed. The main agent integrates both reports and owns the final review result.

## 1. Pin and capture the review

Resolve the fixed point with `git rev-parse`. Ask for it only when the user supplied no usable ref. Stop on an empty diff.

Create one temporary review directory. Save these artifacts there:

```text
git diff <fixed-point>...HEAD
git diff --name-status <fixed-point>...HEAD
git log <fixed-point>..HEAD --oneline
```

Workers read the saved artifacts and their assigned file slice. They do not regenerate or rescan the complete diff.
Delegate any command or read expected to exceed 200 lines or 20 KB, including complete diffs, broad recursive searches, long logs, and long documents. Workers save full evidence and return at most 400 words with status, findings, and evidence paths; bounded-summary wrappers may run in the main agent. Use as many depth-one workers as independent review scopes require, with no fixed count.

## 2. Find the spec

Use the first available source:

1. An issue referenced by a commit message, fetched through `docs/agents/issue-tracker.md` when present.
2. A path supplied by the user.
3. A matching file under `docs/`, `specs/`, or `.scratch/`.
4. The user's statement that no spec exists. In that case, skip the Spec worker and report `no spec available`.

## 3. Find the standards

List repository files that document coding rules. Do not send material that tooling already enforces. The Standards worker also reads [SMELL-BASELINE.md](SMELL-BASELINE.md). Keep the baseline out of the worker prompt.

## 4. Run the axes in parallel

Give each worker:

- the saved diff, manifest, and commit-list paths;
- a disjoint file slice when the diff can be divided cleanly;
- the relevant acceptance IDs from `docs/agents/validation-index.md`;
- a 400 word return limit and an evidence-file path for longer details;
- an instruction to remain at depth one.

The Standards brief is:

```text
Report every documented-standard violation in your file slice with the standard path and rule. Then report judgement-call smells from SMELL-BASELINE.md with the hunk. Repository rules override the baseline. Skip tooling-enforced rules. Put detailed evidence in the assigned file and return at most 400 words.
```

The Spec brief is:

```text
Report missing or partial requirements, unrequested behavior, and implementation that appears to contradict a requirement. Quote the spec location for each finding. Put detailed evidence in the assigned file and return at most 400 words.
```

## 5. Aggregate and recheck

Present the two reports under `## Standards` and `## Spec`. Preserve their separate severity ordering. End with finding counts and the worst finding within each axis.

Record `tools/worktree_fingerprint.py` with the review. A post-fix recheck receives the prior findings and only the changed hunks that can affect them. Rerun both workers when the fingerprint changed outside the reviewed file slices or shared contracts changed.
