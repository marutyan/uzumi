#!/usr/bin/env python3
"""段階2の道具（run_stage2.py）のうち、端末を使わない部分の確認。`python3 tools/phase3a/test_run_stage2.py`で流す。"""

import base64
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_stage2 as s2  # noqa: E402


class LatinScheduleTest(unittest.TestCase):
    """5回×5条件の順番。"""

    def test_schedule_is_latin_square_fixed_by_seed(self):
        rows = s2.latin_schedule()
        self.assertTrue(s2.is_latin(rows))
        self.assertEqual(rows, s2.latin_schedule(s2.LATIN_SEED))
        # 各条件が各位置に一度ずつ当たる
        for position in range(5):
            self.assertEqual(Counter(row[position] for row in rows), Counter(s2.CONDITIONS))

    def test_other_seed_gives_other_assignment(self):
        self.assertNotEqual(s2.latin_schedule(1), s2.latin_schedule(2))


class ExtrasTest(unittest.TestCase):
    """受信口へ渡す許容表記と最終文。"""

    def test_accepted_and_final_are_base64(self):
        task = s2.p2c.Task("VD03", "数値", "開発", "さんびゃくえん", ["300円", "三百円"])
        accepted = s2.start_extras(task)["accepted_b64"]
        self.assertEqual(base64.b64decode(accepted).decode("utf-8"), "300円|三百円")
        snap = mock.Mock(field_text="今から出る\n")
        final = s2.finish_extras(snap)["final_b64"]
        self.assertEqual(base64.b64decode(final).decode("utf-8"), "今から出る")

    def test_broadcast_rejects_unsafe_extra(self):
        with mock.patch.object(s2.p2c, "shell") as shell:
            with self.assertRaises(s2.p2c.Abort):
                s2.p2c.broadcast("EVAL_START", "VN01", {"accepted_b64": "a b; rm"})
            shell.assert_not_called()

    def test_broadcast_appends_safe_extras(self):
        with mock.patch.object(s2.p2c, "shell", return_value='Broadcast completed: result=-1, data="started"') as shell:
            s2.p2c.broadcast("EVAL_START", "VN01", {"accepted_b64": "5LuK"})
        self.assertIn("--es task VN01 --es accepted_b64 5LuK", shell.call_args[0][0])


class ParseTest(unittest.TestCase):
    """dumpsysと受信口の出力の読み取り。"""

    def test_status_and_memory_lines(self):
        self.assertEqual(s2.parse_status("ime=1\tneural_ready=0\tneural_load_reason=-1"),
                         {"ime": 1, "neural_ready": 0, "neural_load_reason": -1})

    def test_thermal_status(self):
        self.assertEqual(s2.parse_thermal_status("IsStatusOverride: false\nThermal Status: 0\n"), 0)
        self.assertEqual(s2.parse_thermal_status("Thermal Status: 2"), 2)
        self.assertIsNone(s2.parse_thermal_status("nothing"))

    def test_total_pss(self):
        self.assertEqual(s2.parse_total_pss("        TOTAL PSS:    81234            TOTAL RSS:   120000"), 81234)
        self.assertEqual(s2.parse_total_pss("  Native Heap  1000\n        TOTAL    54321    100\n"), 54321)
        self.assertIsNone(s2.parse_total_pss("No process found for: dev.uzumi.ime:neural"))

    def test_computed_drain(self):
        self.assertEqual(s2.parse_computed_drain("Capacity: 4700, Computed drain: 12.5, actual drain: 10-20"), 12.5)

    def test_count_columns_extend_phase2c_columns(self):
        self.assertEqual(s2.COUNT_COLUMNS_V3[:len(s2.p2c.COUNT_COLUMNS)], s2.p2c.COUNT_COLUMNS)
        self.assertEqual(s2.COUNT_COLUMNS_V3[-1], "digit_violation")


class TestSetLedgerTest(unittest.TestCase):
    """試験用の集合を同じ最終設定で二度流さない。"""

    def test_same_round_and_condition_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(s2, "LOCAL", Path(tmp)):
            s2.claim_test_run("abc123", 1, "ZS")
            s2.claim_test_run("abc123", 1, "ZX")
            with self.assertRaises(s2.p2c.Abort):
                s2.claim_test_run("abc123", 1, "ZS")
            s2.claim_test_run("def456", 1, "ZS")

    def test_default_tasks_are_development_set(self):
        tasks = s2.select_tasks(test_set=False, task_ids=None)
        self.assertEqual(len(tasks), 30)
        self.assertTrue(all(t.list_name == "開発" for t in tasks))


if __name__ == "__main__":
    unittest.main()
