#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import agent_usage_report


def record(timestamp: str, record_type: str, payload: dict) -> str:
    return json.dumps(
        {"timestamp": timestamp, "type": record_type, "payload": payload}
    )


def token(timestamp: str, total: int, cached: int = 0, output: int = 0) -> str:
    return record(
        timestamp,
        "event_msg",
        {
            "type": "token_count",
            "info": {
                "total_token_usage": {
                    "input_tokens": total - output,
                    "cached_input_tokens": cached,
                    "output_tokens": output,
                    "reasoning_output_tokens": 0,
                    "total_tokens": total,
                }
            },
        },
    )


class AgentUsageReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, name: str, lines: list[str]) -> None:
        (self.root / name).write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_forked_parent_history_is_counted_once(self) -> None:
        root_meta = {
            "id": "root",
            "timestamp": "2026-01-01T00:00:00Z",
            "cwd": str(self.repo),
            "source": "vscode",
        }
        root_lines = [
            record("2026-01-01T00:00:00Z", "session_meta", root_meta),
            record("2026-01-01T00:00:01Z", "event_msg", {"type": "task_started", "turn_id": "one"}),
            token("2026-01-01T00:00:02Z", 1_000, 800, 20),
            record("2026-01-01T00:00:03Z", "event_msg", {"type": "task_complete", "turn_id": "one", "duration_ms": 100}),
            record("2026-01-01T00:00:04Z", "event_msg", {"type": "task_started", "turn_id": "two"}),
            record("2026-01-01T00:00:05Z", "response_item", {"type": "function_call", "call_id": "c"}),
            record("2026-01-01T00:00:06Z", "response_item", {"type": "function_call_output", "call_id": "c", "output": "1234"}),
            token("2026-01-01T00:00:07Z", 2_500, 2_000, 50),
            record("2026-01-01T00:00:08Z", "event_msg", {"type": "task_complete", "turn_id": "two", "duration_ms": 200}),
        ]
        self.write("2026-01-01-root.jsonl", root_lines)

        child_meta = {
            "id": "child",
            "timestamp": "2026-01-01T00:01:00Z",
            "cwd": str(self.repo / "host"),
            "source": {
                "subagent": {
                    "thread_spawn": {
                        "depth": 1,
                        "agent_path": "/root/review",
                        "parent_thread_id": "root",
                    }
                }
            },
        }
        child_lines = [
            record("2026-01-01T00:01:00Z", "session_meta", child_meta),
            *root_lines[1:],
            record("2026-01-01T00:01:01Z", "event_msg", {"type": "task_started", "turn_id": "three"}),
            token("2026-01-01T00:01:02Z", 4_200, 3_400, 90),
            record("2026-01-01T00:01:03Z", "event_msg", {"type": "task_complete", "turn_id": "three", "duration_ms": 300}),
        ]
        self.write("2026-01-01-child.jsonl", child_lines)

        analysis = agent_usage_report.analyze(self.repo, [self.root])
        turns = {turn.turn_id: turn for turn in analysis.turns}

        self.assertEqual(set(turns), {"one", "two", "three"})
        self.assertEqual(turns["one"].total_tokens, 1_000)
        self.assertEqual(turns["two"].total_tokens, 1_500)
        self.assertEqual(turns["two"].tool_calls, 1)
        self.assertEqual(turns["two"].tool_output_bytes, 4)
        self.assertEqual(turns["three"].total_tokens, 1_700)
        self.assertEqual(turns["three"].agent_kind, "subagent")
        self.assertTrue(turns["three"].review_family)

    def test_counter_reset_uses_ending_value_and_incomplete_turn_is_omitted(self) -> None:
        lines = [
            record(
                "2026-02-01T00:00:00Z",
                "session_meta",
                {
                    "id": "reset",
                    "timestamp": "2026-02-01T00:00:00Z",
                    "cwd": str(self.repo),
                    "source": "vscode",
                },
            ),
            token("2026-02-01T00:00:01Z", 500),
            record("2026-02-01T00:00:02Z", "event_msg", {"type": "task_started", "turn_id": "reset-turn"}),
            token("2026-02-01T00:00:03Z", 100, output=10),
            record("2026-02-01T00:00:04Z", "event_msg", {"type": "task_complete", "turn_id": "reset-turn"}),
            record("2026-02-01T00:00:05Z", "event_msg", {"type": "task_started", "turn_id": "unfinished"}),
            token("2026-02-01T00:00:06Z", 200),
        ]
        self.write("2026-02-01-reset.jsonl", lines)

        analysis = agent_usage_report.analyze(self.repo, [self.root])

        self.assertEqual(len(analysis.turns), 1)
        self.assertEqual(analysis.turns[0].turn_id, "reset-turn")
        self.assertEqual(analysis.turns[0].total_tokens, 100)
        self.assertTrue(analysis.turns[0].counter_reset)


if __name__ == "__main__":
    unittest.main()
