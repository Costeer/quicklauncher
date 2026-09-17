# Agent process

For work that edits more than three files, runs a full verification gate, continues an existing phase, or uses delegated agents, read [docs/agents/execution.md](docs/agents/execution.md) before acting.

- Work from one bounded packet in [docs/agents/phase-packet-template.md](docs/agents/phase-packet-template.md). A packet owns one to three acceptance IDs and a narrow file set.
- Use [docs/agents/validation-index.md](docs/agents/validation-index.md) to find governing sections. Open another document only when a concrete question requires it.
- Use `tools/gradle --summary ...` for Gradle commands. Record release gates with [docs/agents/verification-ledger-template.md](docs/agents/verification-ledger-template.md).
- The main agent owns the user's requested outcome and the shared manifest. Delegate context-heavy work to depth-one subagents with disjoint scopes. Each subagent returns a short finding list plus evidence paths; the main agent integrates every report.
- Delegate any command or read expected to exceed 200 lines or 20 KB, including full builds, full test suites, broad recursive searches, complete diffs, long logs, and long documents. Subagents save full evidence and return at most 400 words with status, findings, and evidence paths; bounded-summary wrappers may run in the main agent. Assign simple file changes with exact acceptance criteria to a lower-reasoning subagent when model selection exists; the main agent reviews and integrates. Use as many depth-one subagents as independent scopes require; no fixed count applies.
- Treat 8 million recorded tokens, 75 model steps, 100 tool calls, or 45 minutes as checkpoint thresholds. Save compact state, delegate the next bounded investigation when useful, and continue until the user's requested outcome is complete.
- A packet boundary, checkpoint, or subagent report is progress. Return a final response only when the user goal is complete or a concrete external blocker requires user action.
- Use `tools/agent_usage_report.py` to measure completed work. Its turn deduplication is required because forked transcripts copy parent history.
