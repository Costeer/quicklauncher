# Phase packet template

Copy this file for any phase or validation slice that can touch more than three files.

```text
Packet ID:
Acceptance IDs: <one to three IDs from docs/agents/validation-index.md>
User goal: <the full requested outcome this packet contributes to>

Packet acceptance condition:
<One observable condition that closes this slice.>

Governing sections:
- <document path and heading>

Owned files:
- <directory or file>

Excluded work:
- <new packet ID and one-line reason>

Focused checks:
- <exact command>

Evidence output:
- <path or ledger section>

Checkpoint thresholds:
- 8,000,000 recorded tokens
- 75 model steps
- 100 tool calls
- 45 minutes
- about 15 changed files

Delegation:
- Delegate commands or reads expected to exceed 200 lines or 20 KB, including full builds, full test suites, broad recursive searches, complete diffs, long logs, and long documents.
- Workers save full evidence and return at most 400 words with status, findings, and evidence paths. Use lower-reasoning workers for mechanical edits with exact acceptance criteria when model selection exists. Run independent scopes in parallel without a fixed worker count.

Checkpoint at a threshold:
- completed edits
- remaining work
- check results
- blockers
- main agent's exact next action

Final response gate:
- all packets required by the user goal are complete
- subagent reports are integrated
- required focused checks and final gates have results
```
