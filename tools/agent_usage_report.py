#!/usr/bin/env python3
"""Report Codex token usage without double-counting forked transcript history."""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable


TOKEN_KEYS = (
    "input_tokens",
    "cached_input_tokens",
    "output_tokens",
    "reasoning_output_tokens",
    "total_tokens",
)
REVIEW_PATH = re.compile(
    r"(?:review|audit|recheck|final|requirements|checklist)", re.IGNORECASE
)


def empty_usage() -> dict[str, int]:
    return {key: 0 for key in TOKEN_KEYS}


def normalized_usage(value: dict[str, Any] | None) -> dict[str, int]:
    value = value or {}
    return {key: int(value.get(key, 0) or 0) for key in TOKEN_KEYS}


@dataclass
class SessionIdentity:
    created: str = ""
    cwd: str = ""
    session_id: str = ""
    agent_kind: str = "root"
    agent_path: str = "/root"
    depth: int = 0


@dataclass
class TurnObservation:
    turn_id: str
    transcript: str
    transcript_created: str
    started: str
    identity: SessionIdentity
    baseline: dict[str, int]
    latest: dict[str, int]
    accumulated: dict[str, int] = field(default_factory=empty_usage)
    counter_reset: bool = False
    complete: bool = False
    aborted: bool = False
    duration_ms: int = 0
    model_steps: int = 0
    tool_calls: int = 0
    tool_output_bytes: int = 0


@dataclass
class TurnUsage:
    turn_id: str
    date: str
    transcript: str
    agent_kind: str
    agent_path: str
    depth: int
    input_tokens: int
    cached_input_tokens: int
    output_tokens: int
    reasoning_output_tokens: int
    total_tokens: int
    duration_ms: int
    model_steps: int
    tool_calls: int
    tool_output_bytes: int
    counter_reset: bool
    review_family: bool


@dataclass
class Analysis:
    repo: str
    files_scanned: int
    files_matched: int
    turns: list[TurnUsage] = field(default_factory=list)


def read_json_lines(path: Path) -> Iterable[dict[str, Any]]:
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            try:
                item = json.loads(line)
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            if isinstance(item, dict):
                yield item


def session_identity(payload: dict[str, Any]) -> SessionIdentity:
    source = payload.get("source")
    spawn: dict[str, Any] = {}
    if isinstance(source, dict):
        spawn = source.get("subagent", {}).get("thread_spawn", {}) or {}
    return SessionIdentity(
        created=str(payload.get("timestamp") or ""),
        cwd=str(payload.get("cwd") or ""),
        session_id=str(payload.get("id") or ""),
        agent_kind="subagent" if spawn else "root",
        agent_path=str(spawn.get("agent_path") or "/root"),
        depth=int(spawn.get("depth") or 0),
    )


def cwd_in_repo(cwd: str, repo: Path) -> bool:
    if not cwd:
        return False
    try:
        resolved = Path(cwd).resolve()
    except (OSError, RuntimeError):
        return False
    return resolved == repo or repo in resolved.parents


def parse_transcript(path: Path, repo: Path) -> tuple[SessionIdentity, list[TurnObservation]]:
    identity: SessionIdentity | None = None
    last_usage = empty_usage()
    current: TurnObservation | None = None
    turns: list[TurnObservation] = []

    for item in read_json_lines(path):
        record_type = item.get("type")
        payload = item.get("payload") or {}
        if not isinstance(payload, dict):
            continue

        if record_type == "session_meta" and identity is None:
            identity = session_identity(payload)
            continue

        if record_type == "event_msg":
            event_type = payload.get("type")
            if event_type == "token_count":
                info = payload.get("info") or {}
                usage = normalized_usage(info.get("total_token_usage"))
                changed = usage["total_tokens"] != last_usage["total_tokens"]
                if current is not None:
                    current.latest = usage.copy()
                    if changed:
                        current.model_steps += 1
                        reset = usage["total_tokens"] < last_usage["total_tokens"]
                        current.counter_reset = current.counter_reset or reset
                        for key in TOKEN_KEYS:
                            if reset:
                                increment = usage[key]
                            else:
                                increment = max(0, usage[key] - last_usage[key])
                            current.accumulated[key] += increment
                last_usage = usage
                continue

            if event_type == "task_started":
                if identity is None:
                    identity = SessionIdentity(cwd=str(repo))
                current = TurnObservation(
                    turn_id=str(payload.get("turn_id") or ""),
                    transcript=str(path),
                    transcript_created=identity.created,
                    started=str(item.get("timestamp") or ""),
                    identity=identity,
                    baseline=last_usage.copy(),
                    latest=last_usage.copy(),
                )
                if current.turn_id:
                    turns.append(current)
                continue

            if event_type == "task_complete":
                turn_id = str(payload.get("turn_id") or "")
                target = next(
                    (turn for turn in reversed(turns) if turn.turn_id == turn_id), None
                )
                if target is not None:
                    target.complete = True
                    target.duration_ms = int(payload.get("duration_ms") or 0)
                if current is target:
                    current = None
                continue

            if event_type == "turn_aborted":
                if current is not None:
                    current.aborted = True
                current = None
                continue

        if record_type == "response_item" and current is not None:
            response_type = payload.get("type")
            if response_type in {"function_call", "custom_tool_call"}:
                current.tool_calls += 1
            elif response_type in {"function_call_output", "custom_tool_call_output"}:
                output = payload.get("output", payload.get("content", ""))
                if not isinstance(output, str):
                    output = json.dumps(output, ensure_ascii=False, default=str)
                current.tool_output_bytes += len(output.encode("utf-8"))

    if identity is None:
        identity = SessionIdentity()
    matched = cwd_in_repo(identity.cwd, repo)
    return identity, turns if matched else []


def usage_delta(observation: TurnObservation) -> TurnUsage:
    delta = observation.accumulated
    return TurnUsage(
        turn_id=observation.turn_id,
        date=observation.started[:10],
        transcript=observation.transcript,
        agent_kind=observation.identity.agent_kind,
        agent_path=observation.identity.agent_path,
        depth=observation.identity.depth,
        input_tokens=delta["input_tokens"],
        cached_input_tokens=delta["cached_input_tokens"],
        output_tokens=delta["output_tokens"],
        reasoning_output_tokens=delta["reasoning_output_tokens"],
        total_tokens=delta["total_tokens"],
        duration_ms=observation.duration_ms,
        model_steps=observation.model_steps,
        tool_calls=observation.tool_calls,
        tool_output_bytes=observation.tool_output_bytes,
        counter_reset=observation.counter_reset,
        review_family=bool(REVIEW_PATH.search(observation.identity.agent_path)),
    )


def discover_transcripts(roots: Iterable[Path]) -> list[Path]:
    paths: set[Path] = set()
    for root in roots:
        if root.is_file() and root.suffix == ".jsonl":
            paths.add(root.resolve())
        elif root.is_dir():
            paths.update(path.resolve() for path in root.rglob("*.jsonl"))
    return sorted(paths)


def analyze(repo: Path, roots: Iterable[Path]) -> Analysis:
    repo = repo.resolve()
    transcripts = discover_transcripts(roots)
    observations: dict[str, list[TurnObservation]] = {}
    matched_files = 0

    for transcript in transcripts:
        identity, turns = parse_transcript(transcript, repo)
        if turns or cwd_in_repo(identity.cwd, repo):
            matched_files += 1
        for turn in turns:
            observations.setdefault(turn.turn_id, []).append(turn)

    owned: list[TurnUsage] = []
    for candidates in observations.values():
        completed = [item for item in candidates if item.complete and not item.aborted]
        if not completed:
            continue
        owner = min(
            completed,
            key=lambda item: (
                item.transcript_created or "9999",
                item.transcript,
            ),
        )
        owned.append(usage_delta(owner))

    owned.sort(key=lambda turn: (turn.date, turn.turn_id))
    return Analysis(
        repo=str(repo),
        files_scanned=len(transcripts),
        files_matched=matched_files,
        turns=owned,
    )


def percentile(values: list[int], fraction: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = int(fraction * (len(ordered) - 1))
    return ordered[index]


def sum_field(turns: Iterable[TurnUsage], name: str) -> int:
    return sum(int(getattr(turn, name)) for turn in turns)


def summary(analysis: Analysis, token_limit: int) -> dict[str, Any]:
    turns = analysis.turns
    root = [turn for turn in turns if turn.agent_kind == "root"]
    workers = [turn for turn in turns if turn.agent_kind == "subagent"]
    reviews = [turn for turn in turns if turn.review_family]
    totals = {
        key: sum_field(turns, key)
        for key in (
            "input_tokens",
            "cached_input_tokens",
            "output_tokens",
            "reasoning_output_tokens",
            "total_tokens",
            "duration_ms",
            "model_steps",
            "tool_calls",
            "tool_output_bytes",
        )
    }
    total_input = totals["input_tokens"]
    total_tokens = totals["total_tokens"]
    return {
        "repo": analysis.repo,
        "files_scanned": analysis.files_scanned,
        "files_matched": analysis.files_matched,
        "completed_turns": len(turns),
        "root_turns": len(root),
        "worker_turns": len(workers),
        "totals": totals,
        "cached_input_percent": round(
            100 * totals["cached_input_tokens"] / total_input, 1
        )
        if total_input
        else 0.0,
        "median_turn_tokens": int(statistics.median(
            [turn.total_tokens for turn in turns]
        ))
        if turns
        else 0,
        "p90_turn_tokens": percentile([turn.total_tokens for turn in turns], 0.9),
        "largest_turn_tokens": max((turn.total_tokens for turn in turns), default=0),
        "root_tokens": sum_field(root, "total_tokens"),
        "worker_tokens": sum_field(workers, "total_tokens"),
        "worker_percent": round(
            100 * sum_field(workers, "total_tokens") / total_tokens, 1
        )
        if total_tokens
        else 0.0,
        "review_turns": len(reviews),
        "review_tokens": sum_field(reviews, "total_tokens"),
        "review_percent": round(
            100 * sum_field(reviews, "total_tokens") / total_tokens, 1
        )
        if total_tokens
        else 0.0,
        "token_limit": token_limit,
        "turns_over_token_limit": sum(
            turn.total_tokens > token_limit for turn in turns
        ),
        "tokens_above_limit": sum(
            max(0, turn.total_tokens - token_limit) for turn in turns
        ),
    }


def compact_number(value: int) -> str:
    if value >= 1_000_000_000:
        return f"{value / 1_000_000_000:.3f}B"
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f}M"
    if value >= 1_000:
        return f"{value / 1_000:.1f}K"
    return str(value)


def print_report(analysis: Analysis, data: dict[str, Any], top: int) -> None:
    totals = data["totals"]
    print(f"Repository: {analysis.repo}")
    print(
        f"Transcripts: {data['files_matched']} matched / "
        f"{data['files_scanned']} scanned"
    )
    print(
        f"Completed turns: {data['completed_turns']} "
        f"({data['root_turns']} root, {data['worker_turns']} worker)"
    )
    print(
        "Tokens: "
        f"{compact_number(totals['total_tokens'])} total, "
        f"{compact_number(totals['input_tokens'])} input, "
        f"{compact_number(totals['output_tokens'])} output, "
        f"{data['cached_input_percent']:.1f}% input cached"
    )
    print(
        "Turn distribution: "
        f"median {compact_number(data['median_turn_tokens'])}, "
        f"p90 {compact_number(data['p90_turn_tokens'])}, "
        f"largest {compact_number(data['largest_turn_tokens'])}"
    )
    print(
        "Workers: "
        f"{compact_number(data['worker_tokens'])} tokens "
        f"({data['worker_percent']:.1f}%); reviews "
        f"{compact_number(data['review_tokens'])} "
        f"({data['review_percent']:.1f}%)"
    )
    print(
        "Activity: "
        f"{totals['model_steps']:,} model steps, "
        f"{totals['tool_calls']:,} tool calls, "
        f"{totals['tool_output_bytes'] / 1_000_000:.1f} MB tool output"
    )
    print(
        f"Limit: {data['turns_over_token_limit']} turns exceeded "
        f"{compact_number(data['token_limit'])}; "
        f"{compact_number(data['tokens_above_limit'])} tokens above it"
    )
    print("\nLargest turns:")
    for turn in sorted(
        analysis.turns, key=lambda item: item.total_tokens, reverse=True
    )[:top]:
        print(
            f"  {turn.date}  {compact_number(turn.total_tokens):>8}  "
            f"{turn.model_steps:>4} steps  {turn.tool_calls:>4} tools  "
            f"{turn.agent_path}  {turn.turn_id}"
        )


def default_roots() -> list[Path]:
    home = Path.home() / ".codex"
    return [home / "sessions", home / "archived_sessions"]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument(
        "--sessions-root",
        type=Path,
        action="append",
        help="JSONL file or directory. Repeat for multiple roots.",
    )
    parser.add_argument("--top", type=int, default=10)
    parser.add_argument("--token-limit", type=int, default=8_000_000)
    parser.add_argument("--json", type=Path, help="Write summary and per-turn data.")
    parser.add_argument("--summary-json", type=Path, help="Write aggregate data only.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    roots = args.sessions_root or default_roots()
    analysis = analyze(args.repo, roots)
    data = summary(analysis, args.token_limit)
    print_report(analysis, data, max(0, args.top))
    if args.summary_json:
        write_json(args.summary_json, data)
    if args.json:
        write_json(
            args.json,
            {"summary": data, "turns": [asdict(turn) for turn in analysis.turns]},
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
