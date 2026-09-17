---
name: implement
description: "Implement a piece of work based on a spec or set of tickets."
disable-model-invocation: true
---

Implement the work described by the user in the spec or tickets.

For work that can edit more than three files, read `docs/agents/execution.md` and create bounded packets from `docs/agents/phase-packet-template.md`. The main agent works through every packet required by the user's request. A checkpoint or subagent report does not end the task.

Delegate context-heavy exploration, large logs, broad diffs, research, and independent implementation slices to depth-one subagents. Give parallel subagents disjoint scopes and integrate their concise reports before declaring completion.
Delegate any command or read expected to exceed 200 lines or 20 KB, including full builds, full test suites, broad recursive searches, complete diffs, long logs, and long documents. Have workers save full evidence and return at most 400 words with status, findings, and evidence paths; bounded-summary wrappers may run in the main agent. Assign mechanical edits with exact acceptance criteria to a lower-reasoning worker when model selection exists. Use as many depth-one workers as independent scopes require.

Use /tdd where possible, at pre-agreed seams.

Run the smallest relevant typecheck or test while editing. Use `tools/gradle --summary ...` for Gradle. Run the full test suite once after the packet set is integrated, then record it with `docs/agents/verification-ledger-template.md`.

Once the release candidate is ready, use /code-review once. A fix reruns the affected review axis and focused checks. Repeat both axes only when the fix changes shared contracts or crosses the reviewed scope.

Commit your work to the current branch.
