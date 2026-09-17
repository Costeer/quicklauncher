# Bounded agent execution

Use this process for phase work, broad implementation, independent validation, and any task that delegates work or runs a full repository gate.

## 1. Select one packet

Start from [the packet template](phase-packet-template.md). A packet has:

- one to three acceptance IDs;
- a declared file area, normally no more than 15 changed files;
- the exact governing sections from [the validation index](validation-index.md);
- focused checks for the behavior being changed;
- one observable completion condition.

The main agent owns the full user goal. List the packets needed to reach it, then work through them until every requested acceptance condition is met. Treat newly discovered work outside the current bounds as a new packet. Record it and finish the current packet first unless it blocks the current acceptance IDs.

## 2. Load only the packet context

Read the indexed sections and source files named by the packet. Search before opening a long file. Read another ADR or status document when a concrete conflict, invariant, or missing fact points to it.

The environment remains the source of truth for file lists, diffs, task names, and build configuration. Generate those facts with commands instead of copying them into prose.

## 3. Checkpoint long work

Record a compact checkpoint when any threshold is reached:

| Measure | Limit |
| --- | ---: |
| Recorded tokens | 8,000,000 |
| Model steps | 75 |
| Tool calls | 100 |
| Wall time | 45 minutes |

The checkpoint contains the acceptance IDs, completed edits, remaining work, focused check results, current blockers, and exact next action. Keep it below 500 words.

A packet boundary is an internal checkpoint, not a stopping condition. The main agent continues with the next required packet. It may delegate the next context-heavy slice to a subagent so the main context retains only the concise report. Return a final response only when the user goal is complete or a concrete external blocker requires user action.

Hardware availability updates resume the blocked acceptance ID. They do not reopen every incomplete item in the phase.

## 4. Delegate disjoint work

Use delegation to keep repository exploration, long logs, large diffs, research, and independent implementation slices out of the main context.

- The root agent creates one shared changed-file manifest and divides it into disjoint scopes.
- Workers remain at depth one and do not create more workers.
- Each worker receives acceptance IDs, source paths, governing sections, and a checkable completion condition.
- A worker returns at most 400 words. Detailed evidence goes in a repository or temporary file and the report links to it.
- Independent workers may run in parallel. The main agent waits for their reports, integrates the results, resolves overlaps, and continues the remaining packets.
- Worker completion closes only the assigned slice. It never completes the user's request by itself.
- Delegate any command or read expected to exceed 200 lines or 20 KB, including full builds, full test suites, broad recursive searches, complete diffs, long logs, and long documents. Workers save full evidence and return at most 400 words with status, findings, and evidence paths; bounded-summary wrappers may run in the main agent. Assign mechanical edits with exact acceptance criteria to a lower-reasoning worker when model selection exists. Use as many depth-one workers as independent scopes require; no fixed worker count applies.

One release candidate gets one two-axis review. A fix reruns the affected axis and focused checks. Run both axes again only when the fix changes shared contracts or crosses the original scope boundary.

## 5. Control command output

- Use `tools/gradle --summary ...` through a subagent for full builds, full test suites, and other high-output commands. The worker saves the full log and evidence under the system temporary directory or repository and returns a short result; bounded-summary wrappers may run in the main agent.
- Use `rg` and bounded line ranges for main-agent checks. Delegate broad searches and complete diffs; the worker saves the full artifacts and returns only findings and evidence paths.
- Give long commands 20 to 30 seconds before polling. Give workers minutes between status checks.
- Inspect one image contact sheet at normal detail. Open original detail only when pixels decide a finding.

## 6. Verify in layers

During a packet, run the smallest check that can disprove the current change. Delegate the full local gate and any visual, license, workflow, or connected gate to a subagent when its output is expected to exceed 200 lines or 20 KB; the worker captures full evidence and reports a bounded summary. Run each applicable gate once per release candidate.

After a failure, rerun the failing task. Repeat a full gate only after a change that can affect that gate.

Copy [the verification ledger template](verification-ledger-template.md) into the phase evidence. Record the worktree fingerprint from `tools/worktree_fingerprint.py`, exact command, result, duration, and reason for the run. A passing result can be reused while the fingerprint and relevant environment identity remain equal.

## 7. Measure the result

Run:

```text
tools/agent_usage_report.py --repo . --top 15
```

The report assigns each turn to its earliest transcript and uses the change in cumulative token counters. This removes copied history from forks and resumed sessions. Track median, 90th percentile, largest turn, worker share, review share, model steps, and tool-output bytes.

## 8. Complete the user goal

Before the final response, confirm that every requested acceptance ID is complete, required focused checks passed, and the applicable final gate ran. Integrate all subagent findings and account for every remaining item. If an external dependency requires user action, name the exact dependency and preserve the next command in the checkpoint.
