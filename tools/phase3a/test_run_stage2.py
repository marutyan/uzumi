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
    """試験用の集合は、設定の内容に関係なく課題集合ごとに一度しか使えない。流し直しは探索として別に残す。"""

    def test_same_round_and_condition_is_refused_whatever_the_config(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(s2, "LOCAL", Path(tmp)):
            ledger = s2.ledger_directory("phase2c-tasks-abc", None)
            s2.claim_run(ledger, 1, "ZS", {"apk_sha256": "a"})
            s2.claim_run(ledger, 1, "ZX", {"apk_sha256": "a"})
            with self.assertRaises(s2.p2c.Abort):
                s2.claim_run(s2.ledger_directory("phase2c-tasks-abc", None), 1, "ZS", {"apk_sha256": "other"})
            # 探索は別の記録へ分かれ、本番の記録を使わない
            exploration = s2.ledger_directory("phase2c-tasks-abc", "prompt-v2")
            self.assertNotEqual(exploration, ledger)
            s2.claim_run(exploration, 1, "ZS", {"apk_sha256": "other"})

    def test_task_set_id_depends_on_file_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "phase2c-tasks.tsv"
            path.write_text("a")
            first = s2.task_set_id(path)
            path.write_text("b")
            self.assertNotEqual(first, s2.task_set_id(path))
            self.assertTrue(first.startswith("phase2c-tasks-"))

    def test_manifest_rejects_changed_apk_on_later_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.json"
            s2.check_manifest(manifest, {"apk_sha256": "a", "tool_commit": "c"})
            s2.check_manifest(manifest, {"apk_sha256": "a", "tool_commit": "c"})
            with self.assertRaises(s2.p2c.Abort):
                s2.check_manifest(manifest, {"apk_sha256": "b", "tool_commit": "c"})

    def test_default_tasks_are_development_set(self):
        tasks, path = s2.select_tasks(test_set=False, task_ids=None)
        self.assertEqual(path, s2.DEV_TASKS)
        self.assertEqual(len(tasks), 30)
        self.assertTrue(all(t.list_name == "開発" for t in tasks))


class ModelSelectionTest(unittest.TestCase):
    """条件のモデルを選んでから試験画面を開き、選ばれたモデルが条件と一致することを確かめる。"""

    def test_generation_matches_app_formula(self):
        # アプリのNeuralModelSpec.generation（NeuralPromptFormatTestで同じ値を固定）
        self.assertEqual(s2.expected_generation("ZS"), 734620824642353)
        self.assertEqual(s2.expected_generation("M"), 0)

    def test_select_happens_before_screen_is_opened(self):
        calls = []
        ready = "ime=1\tneural_selected=1\tneural_ready=1\tneural_load_reason=0\tneural_model_generation=%d" % s2.expected_generation("JX")
        with mock.patch.object(s2.p2c, "broadcast", side_effect=lambda action, *a, **k: calls.append(action) or ready), \
                mock.patch.object(s2.p2c, "prepare_condition", side_effect=lambda *a: calls.append("prepare")):
            s2.prepare_neural_condition("JX", Path("."))
        self.assertEqual(calls[:3], ["NEURAL_SELECT", "prepare", "EVAL_STATUS"])

    def test_mismatched_model_stops(self):
        previous = "ime=1\tneural_selected=1\tneural_ready=1\tneural_load_reason=0\tneural_model_generation=%d" % s2.expected_generation("ZS")
        with mock.patch.object(s2.p2c, "broadcast", return_value=previous), \
                mock.patch.object(s2.p2c, "prepare_condition"):
            with self.assertRaises(s2.p2c.Abort):
                s2.prepare_neural_condition("JX", Path("."))
        with self.assertRaises(s2.p2c.Abort):
            s2.check_selected_model("M", {"ime": 1, "neural_selected": 1, "neural_model_generation": 5}, 0)
        s2.check_selected_model("M", {"ime": 1, "neural_selected": 0, "neural_model_generation": 0}, 0)


class BatteryRuleTest(unittest.TestCase):
    """電池：各条件3回、基準をまたげば5回、なおまたげば不合格。回どうしは別の日または時間帯。"""

    def test_judgement(self):
        self.assertEqual(s2.battery_judgement([1.1, 1.0]), "incomplete")
        self.assertEqual(s2.battery_judgement([1.1, 1.0, 1.15]), "pass")
        self.assertEqual(s2.battery_judgement([1.3, 1.25, 1.4]), "fail")
        self.assertEqual(s2.battery_judgement([1.1, 1.3, 1.0]), "need_more")
        self.assertEqual(s2.battery_judgement([1.1, 1.3, 1.0, 1.0, 1.1]), "fail")

    def record(self, index, epoch, ratio):
        drains = {c: 10.0 * (ratio if c != "M" else 1.0) for c in s2.CONDITIONS}
        return {"round": index, "started_epoch": epoch, "drain_mah": drains}

    def test_rounds_need_gap_and_extra_rounds_need_straddle(self):
        day = 1_800_000_000.0
        self.assertEqual(s2.next_battery_round([], day), 1)
        with self.assertRaises(s2.p2c.Abort):
            s2.next_battery_round([self.record(1, day, 1.0)], day + 3600)
        self.assertEqual(s2.next_battery_round([self.record(1, day, 1.0)], day + 5 * 3600), 2)
        three = [self.record(1, day, 1.0), self.record(2, day + 86400, 1.1), self.record(3, day + 2 * 86400, 1.05)]
        with self.assertRaises(s2.p2c.Abort):
            s2.next_battery_round(three, day + 3 * 86400)
        straddling = three[:2] + [self.record(3, day + 2 * 86400, 1.3)]
        self.assertEqual(s2.next_battery_round(straddling, day + 3 * 86400), 4)

    def test_each_battery_round_uses_its_own_latin_row(self):
        rows = s2.latin_schedule()
        self.assertEqual(len({tuple(r) for r in rows[:3]}), 3)


if __name__ == "__main__":
    unittest.main()
